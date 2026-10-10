import * as fs from 'node:fs';
import * as path from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { Octokit } from '@octokit/rest';
import {
  classifyCommentCommand,
  commentCommandNeedsGitDiff,
  isCommentCommandAllowed,
  type CommentCommand,
  type CommentTarget,
} from './comment_command';
import { DeterministicScanner, type ScanViolation } from './deterministic_scanner';
import {
  DEFAULT_PR_RECONCILE_SCOPE,
  normalizeLabelName,
  reconcileBotLabelsSafely,
  resolveAreaLabelsFromPaths,
  resolveLabelsFromTitle,
  resolveReviewLabels,
  sanitizeLabels,
  sanitizePrAiSuggestions,
  type GitHubLabelClient,
  type ReconcileScope,
} from './label_manager';
import {
  coverageSummary,
  filterReviewDiffFiles,
  MAX_CHANGED_FILES,
  MAX_DIFF_LENGTH,
  prioritizeFiles,
  truncateDiff,
  type ReviewCoverage,
} from './review_diff';
import { redactForModel } from './redact';

const REVIEW_MARKER = '<!-- PocketGuard-review -->';
const REVIEW_COUNT_MARKER_PREFIX = '<!-- PocketGuard-reviews:';
const REVIEW_CLAIM_MARKER_PREFIX = '<!-- PocketGuard-review-claim:';
// S4 execution matrix: at most two AI reviews per PR+head SHA (the first
// review plus one re-review); a new head SHA resets the budget. Issues are
// never counted. Stage 5 (P1 #1): the budget is consumed by an independent
// minimal-write claim-slot job that runs after prepare-tag and before
// review-send (one +1 per (repo,PR,full head SHA) plus a per-run claim
// marker); review-send only runs AI when its own claim is present, and
// publish only reconciles (never +1). A started-but-failed run (transport /
// artifact / publish failure after the claim) never refunds. The per-PR
// workflow concurrency group (cancel-in-progress: false) serializes runs as
// the primary mutex; GitHub offers no compare-and-swap on comments, so a
// residual race remains if two runs for the same PR ever overlap (see
// readStickyReviewCount and runClaimMode). The sticky comment carries a
// multi-marker ledger (one `PocketGuard-reviews:<sha>:<n>` line per head SHA,
// merged with max() so A→B→A never loses history) plus per-run claim markers
// `PocketGuard-review-claim:<sha>:<runId>:<attempt>` for retry idempotency (a
// retry that finds its own claim never +1+1; a new run with a new key
// consumes normally).
export const MAX_REVIEWS_PER_SHA = 2;
// Issue-mode context budget, following the existing constant style
// (MAX_DIFF_LENGTH / MAX_CHANGED_FILES in review_diff.ts).
export const MAX_ISSUE_CONTEXT_LENGTH = 20000;
// Phase 2 (H) issue chunking (P1 #1 + P2 #5/#6, Phase A): any truncated-but-
// transport-complete issue content is split into sequential chunks of at most
// MAX_ISSUE_CHUNK_LENGTH so every character is triaged with full coverage.
// Chunking requires (a) fetchComplete (live listComments fully read; a
// transport failure stays fail-closed INCONCLUSIVE with zero AI), (b) fresh
// issue verification via issues.get (P2 #5; legacy clients without `get` keep
// the prior fail-closed INCONCLUSIVE path), and (c) title within its 2000 cap
// (title is carried in every chunk for context, never sliced). Covered shapes:
// body 8001+ (single-field body cap), single comment 2001+ (single-field
// comment cap), multi-comment budget truncation past MAX_ISSUE_CONTEXT_LENGTH
// (e.g. total 20001), and 0-body plus long comments (comment-only chunks).
// Boundary: total 20000 stays single-turn, 20001 chunks; body 8000 stays
// single-turn, 8001 chunks. Multi-chunk single rounds still consume exactly
// one claim-slot (issues are never counted; claim semantics untouched).
// PR side keeps the 120k single-segment strategy (MAX_DIFF_LENGTH in
// review_diff.ts: one visible diff with truncation/omission recorded in
// coverage, never chunked); only issues use the chunked path below.
export const MAX_ISSUE_CHUNK_LENGTH = 8000;
// Per-chunk minimal retention + sticky bounds (Phase A #4): review-output
// stores redacted per-segment proofs, never full chunk text; sticky comments
// bound findings/summaries/chunk lines with omission notes.
export const MAX_ISSUE_CHUNK_SUMMARY_LENGTH = 500;
export const MAX_STICKY_SUMMARY_LENGTH = 2000;
export const MAX_STICKY_FINDINGS_PER_ROLE = 20;
export const MAX_STICKY_CHUNK_LINES = 10;
export const REVIEW_REPORT_JSON_NAME = 'review-report.json';
export const REVIEW_REPORT_MD_NAME = 'review-report.md';
export const REVIEW_REPORT_ARTIFACT_NAME = 'pocketguard-review-report';
// Phase D (P1 #4): identifiable report-write failure. writeReviewReports
// returns this message in its status (and logs it) instead of swallowing the
// error; saveReviewOutput/saveIssueOutput downgrade APPROVE to INCONCLUSIVE
// on failure so a missing report can never approve.
export const REVIEW_REPORT_WRITE_ERROR = 'PocketGuard: failed to write review reports.';
export type ReviewReportWriteResult = { ok: true } | { ok: false; reason: string };
const ROLE_NAMES = ['chief', 'android_sec', 'android_code'] as const;
const SAFE_MESSAGE = '自動審查未執行；請由維護者檢視變更。';

type ReviewRoleName = typeof ROLE_NAMES[number];
type RunnerVerdict = 'APPROVE' | 'NEEDS_CHANGES' | 'INCONCLUSIVE';

interface EventRepository {
  full_name?: string;
  default_branch?: string;
}

interface PullRequestPayload {
  number?: number;
  title?: string;
  base?: { sha?: string; ref?: string };
  head?: { sha?: string; ref?: string; repo?: { full_name?: string | null } | null };
}

interface GithubEvent {
  action?: string;
  repository?: EventRepository;
  pull_request?: PullRequestPayload;
  issue?: { number?: number; title?: string; body?: string; pull_request?: unknown };
  comment?: { body?: string; user?: { login?: string; type?: string }; author_association?: string };
  sender?: { login?: string; type?: string };
}

interface PullRequestData {
  number: number;
  base: { sha: string };
  head: { sha: string; repo?: { full_name?: string | null } | null };
  // Author identity for the manual-review self-approval path (S4): a comment
  // author whose login matches the PR author may re-review their own PR
  // without maintainer write permission. Absent author identity denies.
  user?: { login?: string | null } | null;
}

interface GithubComment {
  id: number;
  body?: string | null;
  user?: { login?: string | null; type?: string | null } | null;
  // P1 #2 Phase B: retained for content-hash binding (never persisted as
  // plaintext; only the SHA-256 hex is stored). Optional for backward
  // compatibility with fakes/clients that omit them.
  updated_at?: string | null;
  created_at?: string | null;
}

interface RunnerGitHubClient {
  rest: {
    pulls: { get(params: { owner: string; repo: string; pull_number: number }): Promise<{ data: PullRequestData }> };
    issues: {
      get?(params: { owner: string; repo: string; issue_number: number }): Promise<{ data: { number?: number; id?: number; title?: string; body?: string | null; updated_at?: string | null; created_at?: string | null } }>;
      listComments(params: { owner: string; repo: string; issue_number: number; per_page: number; page: number }): Promise<{ data: GithubComment[] }>;
      createComment(params: { owner: string; repo: string; issue_number: number; body: string }): Promise<unknown>;
      updateComment(params: { owner: string; repo: string; comment_id: number; body: string }): Promise<unknown>;
      addLabels(params: { owner: string; repo: string; issue_number: number; labels: string[] }): Promise<unknown>;
      listLabelsOnIssue?: GitHubLabelClient['rest']['issues']['listLabelsOnIssue'];
      removeLabel?: GitHubLabelClient['rest']['issues']['removeLabel'];
    };
    users: { getAuthenticated(): Promise<{ data: { login: string } }> };
    repos?: { getCollaboratorPermissionLevel(params: { owner: string; repo: string; username: string }): Promise<{ data: { permission?: string } }> };
  };
}

export interface RunnerContext {
  env?: NodeJS.ProcessEnv;
  event?: GithubEvent;
  githubClient?: RunnerGitHubClient;
  writeStdout?: (text: string) => void;
  runGit?: (args: string[]) => string;
}

interface ReviewTarget {
  target: CommentTarget | 'none';
  command: CommentCommand;
  needsDiff: boolean;
  safeReview: boolean;
  // P1 #1 fork-readable diff signal: true when the diff can be safely read
  // (valid SHAs + valid PR number, plus authorization for issue_comment),
  // regardless of same-repo vs fork. safeReview above stays same-repo-only so
  // the workflow can distinguish "readable diff" (diffSafe) from "same repo"
  // (safeReview). Fork code is never checked out: the checkout stays on the
  // default branch and fork objects arrive only via an explicit
  // `git fetch origin <base> +refs/pull/<N>/head` plus a FETCH_HEAD SHA-pin.
  diffSafe: boolean;
  authorized?: boolean;
  issueNumber?: number;
  pullRequest?: PullRequestData;
  title: string;
  // Head SHA observed for the per-SHA review budget (S4), independent of the
  // same-repo origin gate: the event payload for pull_request_target, or the
  // fresh pulls.get result for issue_comment. Fork runs share the budget.
  quotaHeadSha?: string;
}

// S4 review gate consumed by the workflow job conditions: auto needs no
// commenter gate (first review and owner commits still pass the origin/SHA
// checks in review mode), manual additionally requires authorized == true,
// and none schedules nothing and publishes nothing.
export type ReviewGate = 'auto' | 'manual' | 'none';

export interface TagResult {
  labels: string[];
  areaLabels: string[];
  changedFiles: string[];
  changedFilesComplete: boolean;
  target: CommentTarget | 'none';
  command: CommentCommand;
  needsDiff: boolean;
  safeReview: boolean;
  // P1 #1: readable-diff signal forwarded as `diff_safe`. safeReview stays
  // same-repo-only; diffSafe is true for same-repo and for fork PRs whose
  // diff is safely readable via the pinned PR-ref fetch.
  diffSafe: boolean;
  authorized: boolean;
  issueNumber?: number;
  shouldReview: boolean;
  shouldTag: boolean;
  reason: string;
  routeKind: RouteKind;
  reviewGate: ReviewGate;
  reviewsUsed: number;
  // Stage 5 claim input: the full head SHA whose budget tag checked (event
  // payload for pull_request_target, fresh pulls.get for issue_comment).
  // Forwarded as `quota_head_sha` so the claim-slot job can pin it against a
  // trusted pulls.get read before pre-occupying the slot.
  quotaHeadSha?: string;
  isOwner: boolean;
  actor?: string;
  repoOwner?: string;
  eventName?: string;
  action?: string;
}

export interface RunnerReviewOutput {
  verdict: RunnerVerdict;
  pullRequestNumber?: number;
  baseSha?: string;
  headSha?: string;
  headRepository?: string;
  roles: Array<{
    role: ReviewRoleName;
    modelUsed: string;
    verdict: RunnerVerdict;
    findings: Array<{
      severity: 'BLOCK' | 'WARN' | 'SUGGESTION';
      file?: string;
      line?: number;
      issue: string;
      suggestion?: string;
    }>;
  }>;
  coverage: ReviewCoverage;
  deterministicViolations: ScanViolation[];
  areaLabels: string[];
  changedFiles: string[];
  changedFilesComplete: boolean;
  // S5 AI label schema: raw merged model suggestions (string array, not yet
  // allowlisted). Publish sanitizes through the allowlist, discards unknown
  // with a log, and forces INCONCLUSIVE when any unknown entry is present so
  // illegal labels are never written and the verdict is never a wrong
  // APPROVE. Optional for backward compatibility with pre-S5 artifacts
  // (missing defaults to []).
  suggestedLabels?: string[];
}

function stdout(context: RunnerContext, text: string): void {
  (context.writeStdout ?? ((value) => process.stdout.write(value)))(text);
}

function eventFrom(context: RunnerContext): GithubEvent {
  if (context.event) return context.event;
  const env = context.env ?? process.env;
  const override = env.POCKETGUARD_EVENT_JSON;
  if (override) {
    const parsed: unknown = JSON.parse(override);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed as GithubEvent;
  }
  const eventPath = env.GITHUB_EVENT_PATH;
  if (!eventPath) throw new Error('event unavailable');
  const parsed: unknown = JSON.parse(fs.readFileSync(eventPath, 'utf8'));
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('event invalid');
  return parsed as GithubEvent;
}

function repositoryParts(env: NodeJS.ProcessEnv, event: GithubEvent): { owner: string; repo: string; fullName: string } | undefined {
  const fullName = (env.GITHUB_REPOSITORY ?? event.repository?.full_name ?? '').trim();
  const match = /^([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)$/.exec(fullName);
  return match ? { owner: match[1], repo: match[2], fullName } : undefined;
}

function apiClient(context: RunnerContext, token: string): RunnerGitHubClient | undefined {
  if (context.githubClient) return context.githubClient;
  if (!token) return undefined;
  return new Octokit({ auth: token }) as unknown as RunnerGitHubClient;
}

function sameRepository(left: string | null | undefined, right: string): boolean {
  return typeof left === 'string' && left.toLowerCase() === right.toLowerCase();
}

function safeRepositoryName(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(value);
}

function safeSha(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{40}$/i.test(value);
}

function validPullRequestData(value: unknown): value is PullRequestData {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const pullRequest = value as Record<string, unknown>;
  if (!Number.isSafeInteger(pullRequest.number) || Number(pullRequest.number) < 1) return false;
  if (!pullRequest.base || typeof pullRequest.base !== 'object' || Array.isArray(pullRequest.base)) return false;
  if (!pullRequest.head || typeof pullRequest.head !== 'object' || Array.isArray(pullRequest.head)) return false;
  const base = pullRequest.base as Record<string, unknown>;
  const head = pullRequest.head as Record<string, unknown>;
  if (!head.repo || typeof head.repo !== 'object' || Array.isArray(head.repo)) return false;
  const headRepo = head.repo as Record<string, unknown>;
  return safeSha(base.sha) && safeSha(head.sha) && safeRepositoryName(headRepo.full_name);
}

export function isBotLogin(login?: string): boolean {
  return typeof login === 'string' && login.trim().toLowerCase().endsWith('[bot]');
}

export function extractCommentUsername(event: GithubEvent): string | undefined {
  const raw = event.comment?.user?.login ?? event.sender?.login;
  if (typeof raw !== 'string') return undefined;
  const trimmed = raw.trim();
  return trimmed ? trimmed : undefined;
}

export function isTrustedCommentAuthor(event: GithubEvent, opts?: { authorAssociation?: string; eventName?: string }): boolean {
  void opts?.authorAssociation;
  // issue_comment accepts created or edited; deleted/missing deny fail-closed.
  // Other events keep the legacy rule (explicit non-created denies, missing
  // allowed) so older pull_request_target fixtures keep working; that path
  // does not use this gate for authorization anyway.
  // author_association is never trusted (see checkCommenterPermission).
  if (opts?.eventName === 'issue_comment') {
    if (event.action !== 'created' && event.action !== 'edited') return false;
  } else if (typeof event.action === 'string' && event.action !== 'created') return false;
  if (event.comment?.user?.type?.toLowerCase() === 'bot' || event.sender?.type?.toLowerCase() === 'bot') return false;
  const username = extractCommentUsername(event);
  if (!username) return false;
  if (isBotLogin(username)) return false;
  return true;
}

export async function checkCommenterPermission(
  client: RunnerGitHubClient | undefined,
  owner: string,
  repo: string,
  username: string | undefined,
): Promise<boolean> {
  if (!username || !username.trim()) return false;
  if (!client) return false;
  const repos = client.rest.repos;
  if (typeof repos?.getCollaboratorPermissionLevel !== 'function') return false;
  try {
    const response = await repos.getCollaboratorPermissionLevel({
      owner,
      repo,
      username: username.trim(),
    });
    const permission = response?.data?.permission;
    return permission === 'admin' || permission === 'write';
  } catch {
    // Fail closed on 404/403/429/network errors. Never fall back to
    // author_association, and never leak the username or permission detail.
    return false;
  }
}

export type RouteKind = 'first-review' | 'issue-update' | 'manual-pr-review' | 'owner-commit' | 'ignore';

export interface RouteResult {
  kind: RouteKind;
  reason: string;
}

// Actor snapshot for routing: workflow GITHUB_ACTOR first, event sender as
// fallback. Commit-author strings are never consulted.
export function resolveRouteActor(env: NodeJS.ProcessEnv | undefined, event: GithubEvent): string | undefined {
  const raw = env?.GITHUB_ACTOR ?? event.sender?.login;
  if (typeof raw !== 'string') return undefined;
  const trimmed = raw.trim();
  return trimmed ? trimmed : undefined;
}

// Repository-owner snapshot: explicit POCKETGUARD_REPO_OWNER first (workflow
// sets it from github.repository_owner), otherwise the owner segment of the
// repository full name. Never derived from commit authorship.
export function resolveRouteRepoOwner(env: NodeJS.ProcessEnv | undefined, event: GithubEvent): string | undefined {
  const explicit = env?.POCKETGUARD_REPO_OWNER;
  if (typeof explicit === 'string' && explicit.trim()) return explicit.trim();
  const fullName = (env?.GITHUB_REPOSITORY ?? event.repository?.full_name ?? '').trim();
  const match = /^([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)$/.exec(fullName);
  return match ? match[1] : undefined;
}

// Case-insensitive owner comparison. A missing field on either side is
// fail-closed (not owner), routing synchronize toward ignore.
export function isRepositoryOwner(actor: string | undefined, repoOwner: string | undefined): boolean {
  if (!actor || !repoOwner) return false;
  const left = actor.trim();
  const right = repoOwner.trim();
  if (!left || !right) return false;
  return left.toLowerCase() === right.toLowerCase();
}

// Bot-loop guard for routing: comment author or sender with Bot type, or a
// login ending in [bot] (case-insensitive via isBotLogin), counts as a bot.
// Reuses the same rules as the authorization gate but performs no permission
// lookup; read-only.
export function isBotEventActor(event: GithubEvent): boolean {
  if (event.comment?.user?.type?.toLowerCase() === 'bot' || event.sender?.type?.toLowerCase() === 'bot') return true;
  const commentLogin = event.comment?.user?.login;
  const senderLogin = event.sender?.login;
  if (typeof commentLogin === 'string' && isBotLogin(commentLogin)) return true;
  if (typeof senderLogin === 'string' && isBotLogin(senderLogin)) return true;
  return false;
}

function inferRouteEventName(event: GithubEvent, env: NodeJS.ProcessEnv | undefined): string {
  const explicit = env?.GITHUB_EVENT_NAME;
  if (typeof explicit === 'string' && explicit.trim()) return explicit.trim();
  if (event.pull_request) return 'pull_request_target';
  if (event.comment) return 'issue_comment';
  if (event.issue) return 'issues';
  return '';
}

// Read-only event router (S3). Classifies every event into first-review,
// issue-update, manual-pr-review, owner-commit, or ignore without performing
// review, labeling, counting, or sticky writes. S4/S5 consume kind plus the
// should_review/should_tag flags emitted by runTagMode. P2 #3 / Phase 3:
// opened and reopened share first-review here; the per-SHA quota in
// runTagMode still applies to reopened (reopening never resets the budget).
// Phase 3 reopened only supplements an incomplete first review: reopened
// with used>=1 closes to gate none with reopened-completed (only used==0
// may auto); opened keeps the two-review budget.
export function routeEvent(event: GithubEvent, env?: NodeJS.ProcessEnv): RouteResult {
  const eventName = inferRouteEventName(event, env);
  const action = typeof event.action === 'string' ? event.action : '';

  if (eventName === 'pull_request_target') {
    if (action === 'opened' || action === 'reopened') {
      return {
        kind: 'first-review',
        reason: `pull_request_target ${action}: first review regardless of fork or author permission`,
      };
    }
    if (action === 'synchronize') {
      const actor = resolveRouteActor(env, event);
      const repoOwner = resolveRouteRepoOwner(env, event);
      if (isRepositoryOwner(actor, repoOwner)) {
        return { kind: 'owner-commit', reason: 'pull_request_target synchronize by repository owner: owner commit review' };
      }
      return { kind: 'ignore', reason: 'pull_request_target synchronize by non-owner does not auto-review (owner-only)' };
    }
    return { kind: 'ignore', reason: `pull_request_target action '${action || 'missing'}' is not routed` };
  }

  if (eventName === 'issues') {
    if (action !== 'opened' && action !== 'edited' && action !== 'reopened') {
      return { kind: 'ignore', reason: `issues action '${action || 'missing'}' is not routed` };
    }
    if (isBotEventActor(event)) {
      return { kind: 'ignore', reason: 'issues event from a bot sender is ignored (loop protection)' };
    }
    if (action === 'opened') {
      return { kind: 'first-review', reason: 'issues opened by a human: first review (tagging context)' };
    }
    return { kind: 'issue-update', reason: `issues ${action} by a human: issue update` };
  }

  if (eventName === 'issue_comment') {
    if (action !== 'created' && action !== 'edited') {
      return { kind: 'ignore', reason: `issue_comment action '${action || 'missing'}' is not routed` };
    }
    if (isBotEventActor(event)) {
      return { kind: 'ignore', reason: 'issue_comment from a bot author or sender is ignored (loop protection)' };
    }
    const isPullRequest = Boolean(event.issue?.pull_request);
    const command = classifyCommentCommand(event.comment?.body ?? '');
    if (isPullRequest) {
      if (isCommentCommandAllowed(command, 'pull-request')) {
        return { kind: 'manual-pr-review', reason: `explicit '${command}' command on a pull request: manual review` };
      }
      return { kind: 'ignore', reason: `pull-request comment '${command}' is not an explicit review command (unsupported, ordinary, or triage)` };
    }
    return { kind: 'issue-update', reason: 'human comment on an issue: issue update' };
  }

  return { kind: 'ignore', reason: `event '${eventName || 'unknown'}' is not routed` };
}

export function routeReviewFlags(kind: RouteKind, eventName: string, event?: GithubEvent): { shouldReview: boolean; shouldTag: boolean } {
  if (kind === 'ignore') return { shouldReview: false, shouldTag: false };
  if (kind === 'manual-pr-review' || kind === 'owner-commit') return { shouldReview: true, shouldTag: true };
  if (kind === 'issue-update') return { shouldReview: true, shouldTag: true };
  // first-review: both pull-request opens and human issue opens request
  // review. PR execution is a gated diff review (origin/auth/quota checks in
  // review mode); issue execution is a single chief triage turn with no
  // auth/quota gate by owner decision (bot senders already routed to ignore,
  // per-issue serialization via the workflow concurrency group).
  // should_tag stays true so publish can complete deterministic tagging.
  void eventName;
  void event;
  return { shouldReview: true, shouldTag: true };
}

// S4 review gate for pull-request execution: manual-pr-review always needs
// the commenter authorization verdict; first-review and owner-commit proceed
// without it when they carry a review request (shouldReview). Issue targets
// never open a PR review gate (runTagMode forces none; issue scheduling uses
// the explicit issue-auto route instead — routeKind first-review/issue-update
// plus should_review — with no auth/quota gate by owner decision).
export function resolveReviewGate(kind: RouteKind, shouldReview: boolean): ReviewGate {
  if (kind === 'manual-pr-review') return 'manual';
  if (shouldReview && (kind === 'first-review' || kind === 'owner-commit')) return 'auto';
  return 'none';
}

export function formatReviewCountMarker(sha: string, count: number): string {
  return `${REVIEW_COUNT_MARKER_PREFIX}${sha.toLowerCase()}:${Math.max(0, Math.floor(count))} -->`;
}

// Multi-marker ledger (P1 #2): the sticky body may carry one count marker per
// head SHA on its own line. Parsing collects every marker (case-insensitive
// SHA, last/max wins per SHA); stamping merges instead of overwriting so
// A→B→A keeps A:2 while adding B:1.
export function parseReviewCountLedger(body: unknown): Map<string, number> {
  const ledger = new Map<string, number>();
  if (typeof body !== 'string') return ledger;
  const pattern = new RegExp(
    `${REVIEW_COUNT_MARKER_PREFIX.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}([0-9a-f]{40}):(\\d+)\\s*-->`,
    'gi',
  );
  for (const match of body.matchAll(pattern)) {
    const sha = match[1].toLowerCase();
    const parsed = Number.parseInt(match[2], 10);
    if (!safeSha(sha) || !Number.isSafeInteger(parsed) || parsed < 0) continue;
    const prev = ledger.get(sha) ?? 0;
    ledger.set(sha, Math.max(prev, parsed));
  }
  return ledger;
}

export function parseReviewCountMarker(body: unknown, sha: string): number {
  if (!safeSha(sha)) return 0;
  return parseReviewCountLedger(body).get(sha.toLowerCase()) ?? 0;
}

export function stripReviewCountMarkers(body: string): string {
  const pattern = new RegExp(
    `${REVIEW_COUNT_MARKER_PREFIX.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[0-9a-f]{40}:\\d+\\s*-->`,
    'gi',
  );
  return body.replace(pattern, '');
}

export function withReviewCountMarker(body: string, sha: string, count: number): string {
  if (!safeSha(sha)) return body;
  const target = sha.toLowerCase();
  const normalized = Math.max(0, Math.floor(count));
  const ledger = parseReviewCountLedger(body);
  ledger.set(target, Math.max(ledger.get(target) ?? 0, normalized));
  const stripped = stripReviewCountMarkers(body);
  const base = stripped.length === 0 || stripped.endsWith('\n') ? stripped : `${stripped}\n`;
  const sorted = [...ledger.entries()].sort((left, right) => left[0].localeCompare(right[0]));
  if (sorted.length === 0) return base;
  return `${base}${sorted.map(([entrySha, entryCount]) => formatReviewCountMarker(entrySha, entryCount)).join('\n')}\n`;
}

// Retry-dedup claim markers (P1 #2): `<!-- PocketGuard-review-claim:<sha>:
// <runId>:<attempt> -->`. A publish that finds its own (sha,runId,attempt)
// claim in the freshly re-read sticky never increments again, so a retry
// after a successful write (or the second write of the same run) stays
// idempotent. Without a run id (local runs) there is no claim and the writer
// falls back to max(existing,expected) sharing a single stamp per run.
export function formatReviewClaimMarker(sha: string, runId: string, attempt: string): string {
  return `${REVIEW_CLAIM_MARKER_PREFIX}${sha.toLowerCase()}:${runId}:${attempt} -->`;
}

export function parseReviewClaimSet(body: unknown): Set<string> {
  const claims = new Set<string>();
  if (typeof body !== 'string') return claims;
  const pattern = new RegExp(
    `${REVIEW_CLAIM_MARKER_PREFIX.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}([0-9a-f]{40}):([A-Za-z0-9_.-]{1,64}):([A-Za-z0-9_.-]{1,64})\\s*-->`,
    'gi',
  );
  for (const match of body.matchAll(pattern)) {
    const sha = match[1].toLowerCase();
    if (!safeSha(sha)) continue;
    claims.add(`${sha}:${match[2]}:${match[3]}`);
  }
  return claims;
}

export function stripReviewClaimMarkers(body: string): string {
  const pattern = new RegExp(
    `${REVIEW_CLAIM_MARKER_PREFIX.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[0-9a-f]{40}:[A-Za-z0-9_.-]{1,64}:[A-Za-z0-9_.-]{1,64}\\s*-->`,
    'gi',
  );
  return body.replace(pattern, '');
}

export function withReviewClaimMarker(body: string, sha: string, runId: string, attempt: string): string {
  if (!safeSha(sha)) return body;
  if (!/^[A-Za-z0-9_.-]{1,64}$/.test(runId) || !/^[A-Za-z0-9_.-]{1,64}$/.test(attempt)) return body;
  const id = `${sha.toLowerCase()}:${runId}:${attempt}`;
  if (parseReviewClaimSet(body).has(id)) return body;
  const base = body.length === 0 || body.endsWith('\n') ? body : `${body}\n`;
  return `${base}${formatReviewClaimMarker(sha.toLowerCase(), runId, attempt)}\n`;
}

// P1 #3 bot-written label ledger marker: the sticky body may carry one
// `<!-- PocketGuard-bot-labels:<csv> -->` line recording the last bot-written
// label set (canonical names, comma-separated). Publish reads it before
// reconciling and passes the parsed set as provenance (botWrittenLabels); an
// absent marker means unknown provenance (fail-closed, transitions retained).
// Marker writes are deferred (TODO): the timeline fallback (actor == botLogin
// inside reconcile) already provides provenance growth with zero extra sticky
// writes, so readers must treat a missing marker as unknown, never as proof
// of human authorship. Raw label values are allowlisted before formatting so
// model-controlled text can never inject marker content.
export const BOT_LABELS_MARKER_PREFIX = '<!-- PocketGuard-bot-labels:';

export function formatBotLabelsMarker(labels: readonly string[]): string {
  const kept = sanitizeLabels(
    (Array.isArray(labels) ? labels : []).filter((entry): entry is string => typeof entry === 'string'),
  );
  return `${BOT_LABELS_MARKER_PREFIX}${kept.join(',')} -->`;
}

export function parseBotLabelsMarker(body: unknown): Set<string> | undefined {
  if (typeof body !== 'string') return undefined;
  const pattern = new RegExp(
    `${BOT_LABELS_MARKER_PREFIX.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}([^\\n]*?)-->`,
    'gi',
  );
  let found = false;
  const proven = new Set<string>();
  for (const match of body.matchAll(pattern)) {
    found = true;
    const rawItems = match[1].split(',');
    for (const raw of rawItems) {
      const canonical = normalizeLabelName(raw);
      if (canonical) proven.add(canonical.toLowerCase());
    }
  }
  return found ? proven : undefined;
}

export function stripBotLabelsMarkers(body: string): string {
  const pattern = new RegExp(
    `${BOT_LABELS_MARKER_PREFIX.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[^\\n]*?-->`,
    'gi',
  );
  return body.replace(pattern, '');
}

export function withBotLabelsMarker(body: string, labels: readonly string[]): string {
  const stripped = stripBotLabelsMarkers(body);
  const marker = formatBotLabelsMarker(labels);
  const base = stripped.length === 0 || stripped.endsWith('\n') ? stripped : `${stripped}\n`;
  return `${base}${marker}\n`;
}

// Dedup key for the current publish run: (run_id, run_attempt). Prefers the
// GitHub Actions defaults (GITHUB_RUN_ID/GITHUB_RUN_ATTEMPT, always present
// in the workflow) with POCKETGUARD_* overrides for tests. Returns undefined
// when no run id is available (local runs without ids): callers then share a
// single max() stamp per run instead of claim dedup.
export function resolveCountClaimKey(env: NodeJS.ProcessEnv | undefined): string | undefined {
  const runId = env?.GITHUB_RUN_ID ?? env?.POCKETGUARD_RUN_ID;
  const attempt = env?.GITHUB_RUN_ATTEMPT ?? env?.POCKETGUARD_RUN_ATTEMPT ?? '1';
  if (typeof runId !== 'string' || !runId.trim()) return undefined;
  if (typeof attempt !== 'string' || !attempt.trim()) return undefined;
  const cleanRun = runId.trim();
  const cleanAttempt = attempt.trim();
  if (!/^[A-Za-z0-9_.-]{1,64}$/.test(cleanRun) || !/^[A-Za-z0-9_.-]{1,64}$/.test(cleanAttempt)) return undefined;
  return `${cleanRun}:${cleanAttempt}`;
}

// Merge a fresh review body with the freshly re-read sticky ledger: preserve
// every other SHA, set target to the merged count, and carry (plus add) claim
// markers. When target/expected are undefined the ledger is preserved
// unchanged (uncounted fallback: no new marker, no increment).
export function buildStampedBody(
  freshContent: string,
  freshLedger: Map<string, number>,
  freshClaims: Set<string>,
  targetSha: string | undefined,
  expectedCount: number | undefined,
  claimKey: string | undefined,
): string {
  const mergedLedger = new Map(freshLedger);
  const mergedClaims = new Set(freshClaims);
  if (targetSha && safeSha(targetSha) && typeof expectedCount === 'number') {
    const target = targetSha.toLowerCase();
    const freshExisting = mergedLedger.get(target) ?? 0;
    let mergedCount: number;
    if (!claimKey) {
      mergedCount = Math.max(freshExisting, Math.max(0, Math.floor(expectedCount)));
    } else {
      const claimId = `${target}:${claimKey}`;
      if (mergedClaims.has(claimId)) {
        mergedCount = Math.max(freshExisting, Math.max(0, Math.floor(expectedCount)));
      } else if (freshExisting >= MAX_REVIEWS_PER_SHA) {
        mergedCount = freshExisting;
        mergedClaims.add(claimId);
      } else {
        mergedCount = Math.max(freshExisting + 1, Math.max(0, Math.floor(expectedCount)));
        mergedClaims.add(claimId);
      }
    }
    mergedLedger.set(target, mergedCount);
  }
  const stripped = stripReviewCountMarkers(freshContent);
  const base = stripped.length === 0 || stripped.endsWith('\n') ? stripped : `${stripped}\n`;
  const ledgerPart = [...mergedLedger.entries()]
    .sort((left, right) => left[0].localeCompare(right[0]))
    .map(([entrySha, entryCount]) => formatReviewCountMarker(entrySha, entryCount))
    .join('\n');
  const claimMarkers = [...mergedClaims]
    .sort()
    .map((id) => {
      const parts = id.split(':');
      if (parts.length !== 3 || !safeSha(parts[0])) return undefined;
      return formatReviewClaimMarker(parts[0], parts[1], parts[2]);
    })
    .filter((marker): marker is string => typeof marker === 'string')
    .join('\n');
  let result = base;
  if (ledgerPart) result += `${ledgerPart}\n`;
  if (claimMarkers) result += `${claimMarkers}\n`;
  return result;
}

function isStickyReviewComment(comment: GithubComment, botLogin: string | undefined): boolean {
  const authorMatches = botLogin
    ? comment.user?.login === botLogin
    : comment.user?.login === 'github-actions[bot]' || comment.user?.type === 'Bot';
  return Boolean(authorMatches) &&
    typeof comment.body === 'string' && comment.body.includes(REVIEW_MARKER);
}

// Cross-run review budget read (S4, fail-closed): scans the sticky bot comment
// for the per-SHA counter marker. Distinguishes a successful read with no
// marker (first review → ok/0) from an unreadable state (unknown): missing
// client/APIs, invalid identity, any listComments throw, or malformed payloads
// all report unknown and must close the gate (tag → review_gate none with a
// quota-unknown reason; review → generic INCONCLUSIVE with zero OpenAI;
// publish → no sticky write, no count). A new SHA restarts the budget; the
// per-PR concurrency group serializes the normal case (no compare-and-swap on
// comments, so review mode re-checks immediately before any OpenAI call).
export type StickyReviewCount = { ok: true; used: number } | { ok: false };
export async function readStickyReviewCount(
  client: RunnerGitHubClient | undefined,
  owner: string,
  repo: string,
  issueNumber: number,
  headSha: string,
): Promise<StickyReviewCount> {
  if (!client || !safeSha(headSha) || !Number.isSafeInteger(issueNumber) || issueNumber < 1) return { ok: false };
  try {
    const issues = client.rest.issues as RunnerGitHubClient['rest']['issues'] | undefined;
    if (!issues || typeof issues.listComments !== 'function') return { ok: false };
    let botLogin: string | undefined;
    try {
      const identity = await client.rest.users.getAuthenticated();
      if (typeof identity?.data?.login === 'string' && identity.data.login.trim()) {
        botLogin = identity.data.login;
      }
    } catch {
      // Fall through to the marker plus GitHub bot-author metadata fallback.
    }
    for (let page = 1; ; page += 1) {
      let comments: { data: GithubComment[] };
      try {
        comments = await issues.listComments({
          owner,
          repo,
          issue_number: issueNumber,
          per_page: 100,
          page,
        });
      } catch {
        return { ok: false };
      }
      if (!comments || !Array.isArray(comments.data)) return { ok: false };
      const sticky = comments.data.find((comment) => isStickyReviewComment(comment, botLogin));
      if (sticky) return { ok: true, used: parseReviewCountMarker(sticky.body, headSha) };
      if (comments.data.length < 100) return { ok: true, used: 0 };
    }
  } catch {
    return { ok: false };
  }
}

// Full-ledger re-read for publish and claim (P1 #2, Stage 5): returns every
// per-SHA count plus every claim marker plus the sticky body text from the
// current sticky (or an empty ledger and empty body when no sticky exists
// yet). Any unreadable state reports unknown so callers stay fail-closed
// with zero writes.
export type StickyLedgerRead =
  | { ok: true; ledger: Map<string, number>; claims: Set<string>; body: string }
  | { ok: false };
export async function readStickyLedger(
  client: RunnerGitHubClient | undefined,
  owner: string,
  repo: string,
  issueNumber: number,
): Promise<StickyLedgerRead> {
  if (!client || !Number.isSafeInteger(issueNumber) || issueNumber < 1) return { ok: false };
  try {
    const issues = client.rest.issues as RunnerGitHubClient['rest']['issues'] | undefined;
    if (!issues || typeof issues.listComments !== 'function') return { ok: false };
    let botLogin: string | undefined;
    try {
      const identity = await client.rest.users.getAuthenticated();
      if (typeof identity?.data?.login === 'string' && identity.data.login.trim()) {
        botLogin = identity.data.login;
      }
    } catch {
      // Fall through to the marker plus GitHub bot-author metadata fallback.
    }
    for (let page = 1; ; page += 1) {
      let comments: { data: GithubComment[] };
      try {
        comments = await issues.listComments({
          owner,
          repo,
          issue_number: issueNumber,
          per_page: 100,
          page,
        });
      } catch {
        return { ok: false };
      }
      if (!comments || !Array.isArray(comments.data)) return { ok: false };
      const sticky = comments.data.find((comment) => isStickyReviewComment(comment, botLogin));
      if (sticky) {
        return {
          ok: true,
          ledger: parseReviewCountLedger(sticky.body),
          claims: parseReviewClaimSet(sticky.body),
          body: typeof sticky.body === 'string' ? sticky.body : '',
        };
      }
      if (comments.data.length < 100) return { ok: true, ledger: new Map(), claims: new Set(), body: '' };
    }
  } catch {
    return { ok: false };
  }
}

async function inspectTarget(context: RunnerContext): Promise<ReviewTarget> {
  const env = context.env ?? process.env;
  const event = eventFrom(context);
  const eventName = env.GITHUB_EVENT_NAME ?? '';
  const repository = repositoryParts(env, event);

  if (eventName === 'pull_request_target' || event.pull_request) {
    const pull = event.pull_request;
    if (!pull) return { target: 'none', command: 'unsupported', needsDiff: false, safeReview: false, diffSafe: false, authorized: false, title: '', };
    const headRepository = pull.head?.repo?.full_name;
    const baseSha = pull.base?.sha ?? '';
    const headSha = pull.head?.sha ?? '';
    const safeReview = Boolean(
      repository && sameRepository(headRepository, repository.fullName) && safeSha(baseSha) && safeSha(headSha),
    );
    const number = Number(pull.number ?? event.issue?.number);
    const numberValid = Number.isSafeInteger(number) && number > 0;
    // P1 #1: fork diffs are safely readable (no checkout, pinned PR-ref
    // fetch + SHA-pin in review/tag mode). First-review auto needs no write
    // permission, so diffSafe depends only on SHAs + PR number here.
    const diffSafe = Boolean(repository && safeSha(baseSha) && safeSha(headSha) && numberValid);
    return {
      target: 'pull-request',
      command: 'review',
      needsDiff: true,
      safeReview,
      diffSafe,
      // pull_request_target carries no commenter to authorize; the diffSafe
      // readability check above governs. authorized is always true here.
      authorized: true,
      ...(numberValid ? { issueNumber: number } : {}),
      ...(diffSafe ? {
        pullRequest: {
          number,
          base: { sha: baseSha },
          head: { sha: headSha, repo: { full_name: headRepository } },
        },
      } : {}),
      // Budget identity comes from the webhook payload so fork runs share the
      // same per-SHA limit even though their origin check denies review.
      ...(safeSha(headSha) ? { quotaHeadSha: headSha } : {}),
      title: typeof pull.title === 'string' ? pull.title : '',
    };
  }

  if (eventName === 'issue_comment' || event.comment) {
    const issueNumber = Number(event.issue?.number);
    const command = classifyCommentCommand(event.comment?.body ?? '');
    const isPullRequest = Boolean(event.issue?.pull_request);
    const target: CommentTarget = isPullRequest ? 'pull-request' : 'issue';
    const title = typeof event.issue?.title === 'string' ? event.issue.title : '';
    const issueRef = Number.isSafeInteger(issueNumber) && issueNumber > 0 ? { issueNumber } : {};
    const needsDiff = commentCommandNeedsGitDiff(command);
    // Fail-closed authorization gate: the synchronous author check runs before
    // any pulls.get so denied comments cost no API quota and never attach a
    // pull request. author_association is never trusted here. On
    // issue_comment the check also requires action === 'created' or 'edited'.
    if (!isTrustedCommentAuthor(event, { authorAssociation: event.comment?.author_association, eventName })) {
      return { target, command, needsDiff, safeReview: false, diffSafe: false, authorized: false, ...issueRef, title };
    }
    let authorized = false;
    let pullRequest: PullRequestData | undefined;
    if (isPullRequest && repository && Number.isSafeInteger(issueNumber) && issueNumber > 0) {
      const client = apiClient(context, env.GITHUB_TOKEN ?? '');
      const username = extractCommentUsername(event);
      const hasWrite = await checkCommenterPermission(
        client,
        repository.owner,
        repository.repo,
        username,
      );
      // S4 author self-review: the PR author may re-review their own PR
      // without maintainer write permission. The fresh pulls.get below is the
      // identity source; a missing author login denies. pulls.get runs for
      // every non-bot commenter now (not only writers) to resolve authorship.
      if (client) {
        try {
          const response = await client.rest.pulls.get({
            owner: repository.owner,
            repo: repository.repo,
            pull_number: issueNumber,
          });
          pullRequest = response.data;
        } catch {
          pullRequest = undefined;
        }
      }
      const authorLogin = pullRequest?.user?.login;
      const isAuthor = typeof authorLogin === 'string' && authorLogin.trim() !== '' &&
        typeof username === 'string' && username.trim() !== '' &&
        authorLogin.trim().toLowerCase() === username.trim().toLowerCase();
      authorized = hasWrite || isAuthor;
      if (!authorized) {
        return { target, command, needsDiff, safeReview: false, diffSafe: false, authorized: false, ...issueRef, title };
      }
    } else {
      return { target, command, needsDiff, safeReview: false, diffSafe: false, authorized: false, ...issueRef, title };
    }
    // safeReview stays same-repo-only (backward-compatible origin signal).
    // diffSafe unlocks the fork-legal path: authorized + valid SHAs,
    // regardless of head repo. Fork head code is still never executed or
    // checked out — only read as a diff via the pinned PR-ref fetch.
    const safeReview = Boolean(
      authorized && pullRequest && repository &&
      sameRepository(pullRequest.head.repo?.full_name, repository.fullName) &&
      safeSha(pullRequest.base.sha) && safeSha(pullRequest.head.sha),
    );
    const diffSafeRaw = Boolean(
      authorized && pullRequest && repository &&
      safeSha(pullRequest.base.sha) && safeSha(pullRequest.head.sha),
    );
    const quotaHeadSha = pullRequest && safeSha(pullRequest.head.sha) ? pullRequest.head.sha : undefined;
    return {
      target,
      command,
      needsDiff,
      safeReview: safeReview && isCommentCommandAllowed(command, target),
      diffSafe: diffSafeRaw && isCommentCommandAllowed(command, target),
      authorized,
      ...(Number.isSafeInteger(issueNumber) && issueNumber > 0 ? { issueNumber } : {}),
      ...(diffSafeRaw ? { pullRequest } : {}),
      ...(quotaHeadSha ? { quotaHeadSha } : {}),
      title,
    };
  }

  if (eventName === 'issues' || event.issue) {
    return {
      target: 'issue',
      command: 'unsupported',
      needsDiff: false,
      safeReview: false,
      diffSafe: false,
      authorized: false,
      ...(Number.isSafeInteger(Number(event.issue?.number)) && Number(event.issue?.number) > 0
        ? { issueNumber: Number(event.issue?.number) }
        : {}),
      title: typeof event.issue?.title === 'string' ? event.issue.title : '',
    };
  }

  return { target: 'none', command: 'unsupported', needsDiff: false, safeReview: false, diffSafe: false, authorized: false, title: '' };
}

function appendWorkflowOutputs(env: NodeJS.ProcessEnv, values: Record<string, string>): void {
  const outputPath = env.GITHUB_OUTPUT;
  if (!outputPath) return;
  const lines = Object.entries(values).map(([key, value]) => `${key}=${value}`);
  fs.appendFileSync(outputPath, `${lines.join('\n')}\n`, { encoding: 'utf8', mode: 0o600 });
}

export async function runTagMode(context: RunnerContext = {}): Promise<TagResult> {
  const env = context.env ?? process.env;
  const target = await inspectTarget(context);
  // P1 #1: changed-path context uses the readable-diff signal so fork PRs
  // (first-review + authorized manual) resolve area labels. Fork objects
  // arrive via the pinned PR-ref fetch; nothing is checked out or executed.
  const changed = target.diffSafe && target.pullRequest
    ? collectChangedPaths(context, target.pullRequest)
    : { changedFiles: [], complete: false };
  const areaLabels = target.diffSafe && target.pullRequest
    ? resolveAreaLabelsFromPaths(changed.changedFiles, changed.complete)
    : [];
  const labels = sanitizeLabels([...resolveLabelsFromTitle(target.title), ...areaLabels]);
  const event = eventFrom(context);
  const route = routeEvent(event, env);
  const eventName = inferRouteEventName(event, env);
  const flags = routeReviewFlags(route.kind, eventName, event);
  const actor = resolveRouteActor(env, event);
  const repoOwner = resolveRouteRepoOwner(env, event);
  const isOwner = isRepositoryOwner(actor, repoOwner);
  const action = typeof event.action === 'string' ? event.action : '';
  // S4 quota (Stage 5: consumed by the claim-slot job): an open PR gate
  // closes when this PR+head SHA already consumed its budget, or when the
  // counter is unreadable (fail-closed). Only claimed reviews consume
  // (routing/authorization denials never reach a sticky write), and a new SHA
  // restarts. Issue targets never open a PR gate and never consume quota:
  // consume quota: they are forced to none here and scheduled via the explicit
  // issue-auto route (routeKind first-review/issue-update plus should_review)
  // with no auth/quota gate by owner decision (this phase: no budget/rate
  // limit, only bot exclusion in routing plus per-issue concurrency
  // serialization).
  // P2 #3 / Phase 3 reopened semantics: routeEvent keeps opened||reopened
  // as first-review (no routing change). Reopened only supplements an
  // incomplete first review: reopened + used>=1 closes to gate none with
  // reopened-completed (zero OpenAI, zero writes); only reopened used==0
  // may auto. Opened keeps the two-review budget (used=1 stays auto,
  // used>=2 quota-exhausted). An unreadable ledger closes to gate none
  // with quota-unknown. Reopening never resets the budget — only a new
  // head SHA does.
  // Phase 3 diff-unavailable (P2 #5): a PR with a readable diff expected
  // but an incomplete changed list fails closed without scheduling AI
  // (changed.complete==false → gate none). Issues never use the diff path
  // (forced none below), and unauthorized manual keeps its auth gate so
  // the denial reason stays explicit.
  let reviewGate = resolveReviewGate(route.kind, flags.shouldReview);
  let shouldReview = flags.shouldReview;
  let reason = route.reason;
  let reviewsUsed = 0;
  if (target.target === 'issue') {
    reviewGate = 'none';
  }
  if (
    (reviewGate === 'auto' || reviewGate === 'manual') &&
    target.target === 'pull-request' &&
    target.authorized === true &&
    target.diffSafe &&
    !changed.complete
  ) {
    reviewGate = 'none';
    shouldReview = false;
    reason = 'diff-unavailable: changed file list incomplete; fail-closed without scheduling AI';
  }
  const quotaSha = target.quotaHeadSha ?? target.pullRequest?.head.sha;
  if ((reviewGate === 'auto' || reviewGate === 'manual') && quotaSha && safeSha(quotaSha) && target.issueNumber) {
    const repository = repositoryParts(env, event);
    if (!repository) {
      reviewGate = 'none';
      shouldReview = false;
      reason = 'quota-unknown: repository identity unavailable; review budget could not be verified';
    } else {
      const client = apiClient(context, env.GITHUB_TOKEN ?? '');
      const quota = await readStickyReviewCount(
        client,
        repository.owner,
        repository.repo,
        target.issueNumber,
        quotaSha,
      );
      if (!quota.ok) {
        reviewGate = 'none';
        shouldReview = false;
        reason = 'quota-unknown: sticky review counter unreadable; fail-closed without scheduling AI';
      } else {
        reviewsUsed = quota.used;
        if (reviewsUsed >= MAX_REVIEWS_PER_SHA) {
          reviewGate = 'none';
          shouldReview = false;
          reason = `quota-exhausted: head ${quotaSha.toLowerCase()} already reviewed ${reviewsUsed} times (limit ${MAX_REVIEWS_PER_SHA}); a new head SHA restarts the budget`;
        } else if (action === 'reopened' && eventName === 'pull_request_target' && reviewsUsed >= 1) {
          reviewGate = 'none';
          shouldReview = false;
          reason = `reopened-completed: head ${quotaSha.toLowerCase()} already reviewed ${reviewsUsed} time(s); reopened only supplements an incomplete first review`;
        }
      }
    }
  }
  const result: TagResult = {
    labels,
    areaLabels,
    changedFiles: changed.changedFiles,
    changedFilesComplete: changed.complete,
    target: target.target,
    command: target.command,
    needsDiff: target.needsDiff,
    safeReview: target.safeReview,
    diffSafe: target.diffSafe,
    // Fail-closed: only an explicit true counts as authorized.
    authorized: target.authorized === true,
    ...(target.issueNumber ? { issueNumber: target.issueNumber } : {}),
    shouldReview,
    shouldTag: flags.shouldTag,
    reason,
    routeKind: route.kind,
    reviewGate,
    reviewsUsed,
    ...(quotaSha && safeSha(quotaSha) ? { quotaHeadSha: quotaSha.toLowerCase() } : {}),
    isOwner,
    ...(actor ? { actor } : {}),
    ...(repoOwner ? { repoOwner } : {}),
    ...(eventName ? { eventName } : {}),
    ...(action ? { action } : {}),
  };
  appendWorkflowOutputs(env, {
    labels: JSON.stringify(result.labels),
    area_labels: JSON.stringify(result.areaLabels),
    changed_files: JSON.stringify(result.changedFiles),
    changed_files_complete: String(result.changedFilesComplete),
    target: result.target,
    command: result.command,
    needs_diff: String(result.needsDiff),
    safe_review: String(result.safeReview),
    diff_safe: String(result.diffSafe),
    authorized: String(result.authorized),
    ...(result.issueNumber ? { issue_number: String(result.issueNumber) } : {}),
    should_review: String(result.shouldReview),
    should_tag: String(result.shouldTag),
    reason: result.reason,
    route_kind: result.routeKind,
    review_gate: result.reviewGate,
    reviews_used: String(result.reviewsUsed),
    ...(result.quotaHeadSha ? { quota_head_sha: result.quotaHeadSha } : {}),
    is_owner: String(result.isOwner),
    ...(result.actor ? { actor: result.actor } : {}),
    ...(result.repoOwner ? { repo_owner: result.repoOwner } : {}),
    ...(result.eventName ? { event_name: result.eventName } : {}),
    ...(result.action ? { event_action: result.action } : {}),
  });
  stdout(context, `${JSON.stringify(result)}\n`);
  return result;
}

// Stage 5 claim-slot mode (P1 #1, scheme A): pre-occupies one per-SHA review
// slot before any AI work. Runs in its own minimal-write workflow job after
// prepare-tag and before review-send, with only issues:write (no OpenAI
// secrets, no fork checkout, no git). Inputs reuse the tag scheduling
// (route/authorized/review_gate) plus a trusted fresh pulls.get SHA pin; an
// optional POCKETGUARD_QUOTA_SHA (the tag `quota_head_sha` output) must match
// the fresh head when present, otherwise fail-closed with no write.
// Phase 3 (P2 #5) pre-flight runs before any slot is occupied: the diff must
// be readable (diffSafe plus a valid pull request, fork ref plus SHA pin,
// merge-base, and a changed list within MAX_CHANGED_FILES); any failure is
// unstarted with zero writes so transient git failures never consume.
// Phase 3 reopened only supplements an incomplete first review
// (reopened used>=1 claims nothing with reopened-completed).
//
// Semantics: claim = (repo,PR,full head SHA) first +1 plus a per-run
// (sha,run_id,attempt) marker. review-send only runs AI when its own claim
// marker is present; publish only reconciles and never +1. Routing,
// authorization, quota, diff-unavailable, or reopened-completed blocks
// (unstarted) write nothing and count zero; a
// reserved slot whose later transport/artifact/publish fails is never
// refunded. Same-PR workflow concurrency serializes runs as the primary
// mutex; the claim re-reads the ledger immediately before writing, the same
// (sha,run_id,attempt) re-entry never re-adds, and a new run with a new key
// consumes normally. GitHub offers no compare-and-swap on comments, so a
// residual race remains if two runs for the same PR ever overlap.
export interface ClaimResult {
  claimed: boolean;
  claimSha?: string;
  reviewsUsed: number;
  reason: string;
  issueNumber?: number;
}

const CLAIM_PLACEHOLDER_BODY = `${REVIEW_MARKER}\n\n## PocketGuard 審查\n\n**判定：INCONCLUSIVE**\n**審查預佔名額已保留，等待審查結果。**\n`;

export async function runClaimMode(context: RunnerContext = {}): Promise<ClaimResult> {
  const env = context.env ?? process.env;
  const emit = (result: ClaimResult): ClaimResult => {
    appendWorkflowOutputs(env, {
      claimed: String(result.claimed),
      ...(result.claimSha ? { claim_sha: result.claimSha } : {}),
      reviews_used: String(result.reviewsUsed),
      ...(result.issueNumber ? { issue_number: String(result.issueNumber) } : {}),
      claim_reason: result.reason,
    });
    stdout(context, `${JSON.stringify(result)}\n`);
    return result;
  };
  let event: GithubEvent;
  try {
    event = eventFrom(context);
  } catch {
    return emit({ claimed: false, reviewsUsed: 0, reason: 'claim-unknown: event unavailable' });
  }
  const repository = repositoryParts(env, event);
  const eventName = env.GITHUB_EVENT_NAME ?? '';
  if (!repository) {
    return emit({ claimed: false, reviewsUsed: 0, reason: 'quota-unknown: repository identity unavailable' });
  }
  const target = await inspectTarget(context);
  if (!target.issueNumber) {
    return emit({ claimed: false, reviewsUsed: 0, reason: 'unstarted: no issue number' });
  }
  const issueNumber = target.issueNumber;
  // Issues are never counted: no-op success so the issue-auto review still runs.
  if (target.target === 'issue') {
    return emit({ claimed: false, reviewsUsed: 0, reason: 'issue: never counted', issueNumber });
  }
  // Routed-ignore (non-owner synchronize, chatter, unsubscribed actions, bot)
  // never starts: zero count, no write.
  const claimRoute = routeEvent(event, env);
  if (claimRoute.kind === 'ignore') {
    return emit({ claimed: false, reviewsUsed: 0, reason: `unstarted: ${claimRoute.reason}`, issueNumber });
  }
  if (isBotEventActor(event)) {
    return emit({ claimed: false, reviewsUsed: 0, reason: 'unstarted: bot actor', issueNumber });
  }
  if (!eventCommentAllowed(target, eventName)) {
    return emit({ claimed: false, reviewsUsed: 0, reason: 'unstarted: event not reviewable', issueNumber });
  }
  // Defense in depth for the workflow claim gate: an issue_comment without an
  // explicit authorized verdict claims nothing.
  if (eventName === 'issue_comment' && target.authorized !== true) {
    return emit({ claimed: false, reviewsUsed: 0, reason: 'unstarted: unauthorized', issueNumber });
  }
  const client = apiClient(context, env.GITHUB_TOKEN ?? '');
  if (!client) {
    return emit({ claimed: false, reviewsUsed: 0, reason: 'quota-unknown: client unavailable', issueNumber });
  }
  // Trusted fresh SHA pin: the counting SHA is always the live pulls.get head,
  // never the webhook snapshot alone. An optional POCKETGUARD_QUOTA_SHA (tag
  // `quota_head_sha`) must match the fresh head when present; any mismatch or
  // fetch failure fails closed with no write.
  let freshPullRequest: PullRequestData | undefined;
  try {
    const response = await client.rest.pulls.get({
      owner: repository.owner,
      repo: repository.repo,
      pull_number: issueNumber,
    });
    if (!validPullRequestData(response?.data) || response.data.number !== issueNumber) {
      return emit({ claimed: false, reviewsUsed: 0, reason: 'quota-unknown: invalid pull request identity', issueNumber });
    }
    freshPullRequest = response.data;
  } catch {
    return emit({ claimed: false, reviewsUsed: 0, reason: 'quota-unknown: pull request fetch failed', issueNumber });
  }
  if (!freshPullRequest || !safeSha(freshPullRequest.head.sha)) {
    return emit({ claimed: false, reviewsUsed: 0, reason: 'quota-unknown: invalid head SHA', issueNumber });
  }
  const rawExpected = env.POCKETGUARD_QUOTA_SHA;
  if (typeof rawExpected === 'string' && rawExpected.trim() !== '') {
    const expectedSha = rawExpected.trim().toLowerCase();
    if (!safeSha(expectedSha) || expectedSha !== freshPullRequest.head.sha.toLowerCase()) {
      return emit({ claimed: false, reviewsUsed: 0, reason: 'quota-unknown: head SHA mismatch', issueNumber });
    }
  }
  const claimSha = freshPullRequest.head.sha.toLowerCase();
  // Phase 3 pre-flight (P2 #5): verify the diff is actually readable before
  // pre-occupying the slot (diffSafe plus a valid pull request, fork ref
  // plus SHA pin, merge-base, and a changed list within MAX_CHANGED_FILES).
  // Any failure is unstarted with zero writes so transient git failures
  // never consume the budget.
  if (!target.diffSafe || !target.pullRequest) {
    return emit({ claimed: false, reviewsUsed: 0, reason: 'diff-unavailable: readable diff not available; fail-closed without occupying a slot', issueNumber });
  }
  try {
    const runGit = context.runGit ?? defaultGit;
    fetchReviewCommitsForPR(context, freshPullRequest);
    const mergeBase = resolveMergeBase(runGit, freshPullRequest.base.sha, freshPullRequest.head.sha);
    const changedFiles = getChangedPaths(context, mergeBase, freshPullRequest.head.sha);
    if (changedFiles.length > MAX_CHANGED_FILES) {
      return emit({ claimed: false, reviewsUsed: 0, reason: 'diff-unavailable: changed file list exceeds limit; fail-closed without occupying a slot', issueNumber });
    }
  } catch {
    return emit({ claimed: false, reviewsUsed: 0, reason: 'diff-unavailable: diff pre-flight failed; fail-closed without occupying a slot', issueNumber });
  }
  // Read-then-write: an unreadable ledger fails closed with no write.
  const baseline = await readStickyLedger(client, repository.owner, repository.repo, issueNumber);
  if (!baseline.ok) {
    return emit({ claimed: false, reviewsUsed: 0, reason: 'quota-unknown: sticky ledger unreadable', issueNumber });
  }
  const existing = baseline.ledger.get(claimSha) ?? 0;
  if (existing >= MAX_REVIEWS_PER_SHA) {
    return emit({ claimed: false, reviewsUsed: existing, reason: `quota-exhausted: head ${claimSha} already at ${existing}`, issueNumber, claimSha });
  }
  // Phase 3 reopened: only supplements an incomplete first review.
  {
    const claimAction = typeof event.action === 'string' ? event.action : '';
    if (inferRouteEventName(event, env) === 'pull_request_target' && claimAction === 'reopened' && existing >= 1) {
      return emit({ claimed: false, reviewsUsed: existing, reason: `reopened-completed: head ${claimSha} already reviewed ${existing} time(s); reopened only supplements an incomplete first review`, issueNumber, claimSha });
    }
  }
  const claimKey = resolveCountClaimKey(env);
  const claimPresent = claimKey ? baseline.claims.has(`${claimSha}:${claimKey}`) : false;
  const expected = claimPresent ? existing : existing + 1;
  const baseText = baseline.body.includes(REVIEW_MARKER) ? baseline.body : CLAIM_PLACEHOLDER_BODY;
  const cleanText = stripReviewClaimMarkers(baseText);
  const initialBody = buildStampedBody(cleanText, baseline.ledger, baseline.claims, claimSha, expected, claimKey);
  const buildFreshBody = async (): Promise<string> => {
    const fresh = await readStickyLedger(client, repository.owner, repository.repo, issueNumber);
    if (!fresh.ok) throw new Error('PocketGuard: failed to claim review slot.');
    const freshExisting = fresh.ledger.get(claimSha) ?? 0;
    const freshPresent = claimKey ? fresh.claims.has(`${claimSha}:${claimKey}`) : false;
    const freshExpected = freshPresent ? freshExisting : freshExisting + 1;
    const freshBase = fresh.body.includes(REVIEW_MARKER) ? fresh.body : CLAIM_PLACEHOLDER_BODY;
    return buildStampedBody(stripReviewClaimMarkers(freshBase), fresh.ledger, fresh.claims, claimSha, freshExpected, claimKey);
  };
  try {
    await publishStickyComment(client, repository, issueNumber, initialBody, buildFreshBody);
  } catch {
    throw new Error('PocketGuard: failed to claim review slot.');
  }
  return emit({ claimed: true, reviewsUsed: expected, reason: `claimed: head ${claimSha} now at ${expected}`, issueNumber, claimSha });
}

function defaultGit(args: string[]): string {
  return execFileSync('git', args, {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'ignore'],
    maxBuffer: 64 * 1024 * 1024,
  });
}

function getChangedPaths(context: RunnerContext, mergeBaseSha: string, headSha: string): string[] {
  const runGit = context.runGit ?? defaultGit;
  return runGit(['diff', '--name-only', '-z', mergeBaseSha, headSha])
    .split('\0')
    .filter(Boolean);
}

// Resolve the merge-base of base and head so a PR that lags the default
// branch is compared against its fork point, not the base tip. Any failure
// (non-zero exit, non-SHA output) throws a generic error; callers fail
// closed and must never silently fall back to a base..head comparison.
export function resolveMergeBase(
  runGit: (args: string[]) => string,
  baseSha: string,
  headSha: string,
): string {
  let output: string;
  try {
    output = runGit(['merge-base', baseSha, headSha]);
  } catch {
    throw new Error('PocketGuard: unable to resolve merge base.');
  }
  const sha = output.trim();
  if (!/^[0-9a-f]{40}$/i.test(sha)) throw new Error('PocketGuard: unable to resolve merge base.');
  return sha;
}

function fetchReviewCommits(
  runGit: (args: string[]) => string,
  baseSha: string,
  headSha: string,
): void {
  // Fetch immutable base/head objects only. The checkout stays on the
  // default branch (fork code is never checked out). No --depth flag: the
  // workflow provides full default-branch history (fetch-depth: 0) and this
  // fetch supplies the head objects needed to compute the merge-base.
  runGit(['fetch', '--no-tags', 'origin', baseSha, headSha]);
}

// P1 #1 fork-readable diff: explicit PR-head refspec plus SHA-pin.
//
// The checkout stays on the default branch (never checkout/checkout-index/
// switch/clone of fork code). Only two fetch sources are ever allowed here:
// the immutable base SHA and exactly `+refs/pull/<N>/head` for the PR under
// review. After fetching, FETCH_HEAD must resolve to the webhook/pulls.get
// expected head SHA; any mismatch (TOCTOU push, wrong PR, tampered ref)
// fails closed with zero OpenAI.
export function buildForkFetchRefspec(prNumber: number): string {
  if (!Number.isSafeInteger(prNumber) || prNumber < 1) {
    throw new Error('PocketGuard: invalid pull request number.');
  }
  return `+refs/pull/${prNumber}/head`;
}

export function isAllowedForkFetchRefspec(value: unknown, prNumber: number): boolean {
  if (typeof value !== 'string') return false;
  let expected: string;
  try {
    expected = buildForkFetchRefspec(prNumber);
  } catch {
    return false;
  }
  return value === expected && /^\+refs\/pull\/\d+\/head$/.test(value);
}

export function fetchForkReviewCommits(
  runGit: (args: string[]) => string,
  baseSha: string,
  expectedHeadSha: string,
  prNumber: number,
): void {
  if (!safeSha(baseSha) || !safeSha(expectedHeadSha)) {
    throw new Error('PocketGuard: invalid SHAs.');
  }
  const refspec = buildForkFetchRefspec(prNumber);
  if (!isAllowedForkFetchRefspec(refspec, prNumber)) {
    throw new Error('PocketGuard: disallowed fetch refspec.');
  }
  // Two explicit fetches (base SHA, then the single allowlisted PR ref) so
  // FETCH_HEAD after the second fetch holds exactly the PR head candidate.
  // Never checkout, switch, clone, or reset to fork objects.
  runGit(['fetch', '--no-tags', 'origin', baseSha]);
  runGit(['fetch', '--no-tags', 'origin', refspec]);
  let pinned: string;
  try {
    pinned = runGit(['rev-parse', 'FETCH_HEAD']);
  } catch {
    throw new Error('PocketGuard: unable to verify fork head.');
  }
  const pinnedSha = pinned.trim().split(/[\s\n]+/)[0] ?? '';
  if (!safeSha(pinnedSha) || pinnedSha.toLowerCase() !== expectedHeadSha.toLowerCase()) {
    throw new Error('PocketGuard: fork head SHA mismatch.');
  }
}

function isSameRepoPullRequest(
  context: RunnerContext,
  pullRequest: PullRequestData,
): boolean {
  try {
    const event = eventFrom(context);
    const repository = repositoryParts(context.env ?? process.env, event);
    if (!repository) return false;
    return sameRepository(pullRequest.head.repo?.full_name, repository.fullName);
  } catch {
    return false;
  }
}

function fetchReviewCommitsForPR(
  context: RunnerContext,
  pullRequest: PullRequestData,
): void {
  const runGit = context.runGit ?? defaultGit;
  if (isSameRepoPullRequest(context, pullRequest)) {
    fetchReviewCommits(runGit, pullRequest.base.sha, pullRequest.head.sha);
    return;
  }
  fetchForkReviewCommits(runGit, pullRequest.base.sha, pullRequest.head.sha, pullRequest.number);
}

function collectChangedPaths(
  context: RunnerContext,
  pullRequest: PullRequestData,
): { changedFiles: string[]; complete: boolean } {
  try {
    const runGit = context.runGit ?? defaultGit;
    fetchReviewCommitsForPR(context, pullRequest);
    const mergeBase = resolveMergeBase(runGit, pullRequest.base.sha, pullRequest.head.sha);
    const changedFiles = getChangedPaths(context, mergeBase, pullRequest.head.sha);
    if (changedFiles.length > MAX_CHANGED_FILES) return { changedFiles: [], complete: false };
    return {
      changedFiles,
      complete: true,
    };
  } catch {
    return { changedFiles: [], complete: false };
  }
}

// Callers must pass the resolveMergeBase() result as compareBaseSha, never
// the raw PR base SHA: buildReviewDiff compares fork-point..head so
// default-branch-only changes are excluded. A missing per-file patch for a
// review file throws (fail-closed); truncation/omission is reported via
// coverage and can never approve (see validateReviewOutput/orchestrator).
export function buildReviewDiff(
  context: RunnerContext,
  compareBaseSha: string,
  headSha: string,
  changedFiles: string[],
): { diff: string; coverage: ReviewCoverage; fullDiff: string } {
  const runGit = context.runGit ?? defaultGit;
  const diffs = new Map<string, string>();
  const reviewFiles = new Set(filterReviewDiffFiles(changedFiles));
  let originalLength = 0;
  let includedDiffCount = 0;
  for (const file of changedFiles) {
    // PR-controlled filenames may contain Git pathspec magic even after `--`;
    // force literal interpretation so a name cannot exclude itself from review.
    const fileDiff = runGit([
      '--literal-pathspecs',
      'diff',
      '--no-ext-diff',
      '--no-color',
      '--unified=3',
      compareBaseSha,
      headSha,
      '--',
      file,
    ]);
    if (reviewFiles.has(file) && !fileDiff) {
      throw new Error('Git returned no diff for a changed review file.');
    }
    diffs.set(file, fileDiff);
    if (reviewFiles.has(file)) {
      originalLength += fileDiff.length + (includedDiffCount > 0 ? 1 : 0);
      includedDiffCount += 1;
    }
  }

  const sortedFiles = prioritizeFiles(changedFiles.filter((file) => reviewFiles.has(file)));
  const visible: string[] = [];
  const omittedFiles: string[] = [];
  const truncatedFiles: string[] = [];
  let visibleLength = 0;
  for (const file of sortedFiles) {
    const fileDiff = diffs.get(file) ?? '';
    if (!fileDiff) continue;
    const separatorLength = visible.length > 0 ? 1 : 0;
    const remaining = Math.max(0, MAX_DIFF_LENGTH - visibleLength - separatorLength);
    if (fileDiff.length <= remaining) {
      visible.push(fileDiff);
      visibleLength += separatorLength + fileDiff.length;
      continue;
    }
    if (remaining > 0) {
      const truncated = truncateDiff(fileDiff, remaining);
      if (truncated.diff.length > remaining) throw new Error('assembled review diff exceeded budget');
      if (truncated.diff.includes('PocketGuard diff truncated')) {
        visible.push(truncated.diff);
        visibleLength += separatorLength + truncated.diff.length;
        truncatedFiles.push(file);
      } else {
        omittedFiles.push(file);
      }
    } else {
      omittedFiles.push(file);
    }
  }

  const fullDiff = Array.from(diffs.values()).join('\n');
  const diff = visible.join('\n');
  if (diff.length > MAX_DIFF_LENGTH) throw new Error('assembled review diff exceeded budget');
  if (truncatedFiles.length > 0 && !diff.includes('PocketGuard diff truncated')) {
    throw new Error('truncated review diff is missing its notice');
  }
  return {
    diff,
    fullDiff,
    coverage: {
      complete: omittedFiles.length === 0 && truncatedFiles.length === 0,
      omittedFiles,
      truncatedFiles,
      originalLength,
    },
  };
}

// redactForModel is shared via ./redact (single source of truth with
// orchestrator triage). PR order stays: scan(fullDiff) first, then
// redact(diff) for the model (see runReviewMode).

function genericReviewOutput(): RunnerReviewOutput {
  return {
    verdict: 'INCONCLUSIVE',
    roles: ROLE_NAMES.map((role) => ({ role, modelUsed: 'not-run', verdict: 'INCONCLUSIVE', findings: [] })),
    coverage: { complete: false, omittedFiles: [], truncatedFiles: [], originalLength: 0 },
    deterministicViolations: [],
    areaLabels: [],
    changedFiles: [],
    changedFilesComplete: false,
    suggestedLabels: [],
  };
}

function saveReviewOutput(output: RunnerReviewOutput, context: RunnerContext): ReviewReportWriteResult {
  const env = context.env ?? process.env;
  const serialized = `${JSON.stringify(output, null, 2)}\n`;
  const outputPath = env.POCKETGUARD_OUTPUT;
  if (!outputPath) {
    stdout(context, serialized);
    return { ok: true };
  }
  const resolved = path.resolve(outputPath);
  fs.mkdirSync(path.dirname(resolved), { recursive: true });
  fs.writeFileSync(resolved, serialized, { encoding: 'utf8', mode: 0o600 });
  const result = writeReviewReports(output, context);
  // Phase D (P1 #4): a report-write failure must never leave an APPROVE on
  // disk. Mutate the caller's object (review modes return the same reference)
  // and rewrite the artifact as INCONCLUSIVE with incomplete coverage so the
  // recalculated verdict stays INCONCLUSIVE (validateReviewOutput derives
  // APPROVE only from unanimous APPROVE plus complete coverage).
  if (!result.ok && output.verdict === 'APPROVE') {
    output.verdict = 'INCONCLUSIVE';
    try {
      output.coverage = { ...output.coverage, complete: false };
    } catch {
      // Keep the verdict downgrade even if coverage is malformed.
    }
    output.changedFilesComplete = false;
    try {
      fs.writeFileSync(resolved, `${JSON.stringify(output, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 });
    } catch {
      // The verdict is already downgraded in memory; a rewrite failure only
      // leaves the on-disk copy stale, which publish treats as unavailable.
    }
  }
  return result;
}

function parseAllowedOrigins(env: NodeJS.ProcessEnv): string[] {
  return (env.POCKETGUARD_OPENAI_ORIGIN ?? '')
    .split(/[\s,]+/)
    .map((value) => value.trim())
    .filter((value) => {
      if (!value) return false;
      try {
        const parsed = new URL(value);
        return parsed.protocol === 'https:' && parsed.origin === value && !parsed.username && !parsed.password;
      } catch {
        return false;
      }
    });
}

function installOpenAIStub(env: NodeJS.ProcessEnv): () => void {
  if (env.POCKETGUARD_OPENAI_STUB !== '1') return () => undefined;
  const previousFetch = globalThis.fetch;
  const responseText = JSON.stringify({ verdict: 'APPROVE', summary: '測試審查完成。', findings: [] });
  globalThis.fetch = (async () => new Response(JSON.stringify({
    output: [{ content: [{ type: 'output_text', text: responseText }] }],
  }), { status: 200, headers: { 'Content-Type': 'application/json' } })) as typeof fetch;
  return () => { globalThis.fetch = previousFetch; };
}

export interface RunnerIssueOutput {
  verdict: RunnerVerdict;
  issueNumber: number;
  title: string;
  tags: string[];
  summary: string;
  // S5: raw AI label suggestions (strict string array, allowlisted at
  // publish) plus a revision fingerprint v2 over the FULL RAW issue content
  // (rawTitle/rawBody/rawComments before any redaction or budget cut; control
  // chars normalized, no masking) so publish can verify freshness and update
  // the same sticky on mismatch. Tail edits past any truncation cap still
  // change the hash (no tail collision); two secrets sharing one redacted
  // mask hash differently (no mask collision). v1 redacted hashes naturally
  // mismatch v2 raw hashes for secret-bearing content, so publish falls back
  // to INCONCLUSIVE (same sticky, never a wrong APPROVE) with no version
  // field (the mismatch is the version signal). Only the hex is persisted;
  // raw text never leaves the hash/scan path (see buildIssueContext).
  // P1 #2: comments completeness over the live listComments read. Review
  // never APPROVEs when false; publish requires true on both the artifact
  // and the fresh re-read plus fingerprint equality, otherwise it falls back
  // to INCONCLUSIVE (same sticky, never a wrong APPROVE).
  suggestedLabels: string[];
  fingerprint: string;
  commentsComplete: boolean;
  // Phase 2 (H) chunking (Phase A generalized): present only on the chunked
  // path (transport-complete but truncated: body 8001+, single comment 2001+,
  // multi-comment budget past 20000, or 0-body plus long comments, with fresh
  // issues.get verification and title within cap). Each entry is a redacted
  // minimal proof {index/total/start/end/complete/coveredLength/verdict/
  // summary(truncated)/labels} — never full chunk body/comments text.
  // chunkCoverageComplete is true only when every character is covered and
  // every per-chunk triage succeeded; publish requires it plus fingerprint
  // equality for APPROVE, otherwise INCONCLUSIVE (deterministic BLOCK may
  // still NEEDS_CHANGES). Legacy single-turn artifacts omit these fields.
  chunks?: IssueChunkReport[];
  chunkCoverageComplete?: boolean;
  chunkCount?: number;
}

function issueRulesTags(title: string): string[] {
  return sanitizeLabels(resolveLabelsFromTitle(title));
}

// S5 content fingerprint v2 for issues (P1 #2 Phase B): SHA-256 over the RAW
// (unredacted) full issue content. Control chars are normalized to space
// (shared with cleanForModel) but credential masking is NEVER applied, so two
// secrets sharing one redacted mask (e.g. `password: first` vs
// `password: second` both → `password=[REDACTED CREDENTIAL]`) hash differently.
// Callers must pass the FULL unsliced raw content (see buildIssueContext
// rawTitle/rawBody/rawComments); hashing truncated copies would collide on
// tail edits past any cap, so review and publish compare full values. The
// optional meta binds comment/issue identity (id/updated_at) when available;
// absent fields are omitted so legacy 3-arg calls over pure x/y strings hash
// identically (backward compatible). v1 redacted hashes naturally mismatch v2
// raw hashes for secret-bearing content, so publish falls back to
// INCONCLUSIVE on the same sticky (no version field; the mismatch is the
// version signal). PR freshness reuses the existing head/base SHA identity
// (sameReviewIdentity); issues hash their mutable text instead. Only the hex
// is persisted; raw text never leaves the hash/scan path.
// Order contract (see redact.ts): deterministic scanning runs on the same RAW
// input first; only the AI-bound copy is redacted.
export interface IssueContentFingerprintMeta {
  commentIds?: Array<number | null | undefined>;
  commentUpdatedAts?: Array<string | null | undefined>;
  issueUpdatedAt?: string | null | undefined;
  issueId?: number | null | undefined;
}

// Control-char normalization shared with cleanForModel, without masking.
// Used for the raw fingerprint so control-only differences are stable while
// credential differences are preserved.
export function normalizeRawForFingerprint(value: unknown): string {
  if (typeof value !== 'string') return '';
  return value.replace(/[\u0000-\u001f\u007f]/g, ' ');
}

export function issueContentFingerprint(title: string, body: string, comments: string[], meta?: IssueContentFingerprintMeta): string {
  const normTitle = normalizeRawForFingerprint(title);
  const normBody = normalizeRawForFingerprint(body);
  const normComments = Array.isArray(comments) ? comments.map((c) => normalizeRawForFingerprint(c)) : [];
  const payload: Record<string, unknown> = { title: normTitle, body: normBody, comments: normComments };
  if (meta) {
    if (meta.commentIds !== undefined) {
      payload.commentIds = meta.commentIds.map((id) => (typeof id === 'number' && Number.isSafeInteger(id) ? id : null));
    }
    if (meta.commentUpdatedAts !== undefined) {
      payload.commentUpdatedAts = meta.commentUpdatedAts.map((v) => (typeof v === 'string' ? v : null));
    }
    if (meta.issueUpdatedAt !== undefined) {
      payload.issueUpdatedAt = typeof meta.issueUpdatedAt === 'string' ? meta.issueUpdatedAt : null;
    }
    if (meta.issueId !== undefined) {
      payload.issueId = typeof meta.issueId === 'number' && Number.isSafeInteger(meta.issueId) ? meta.issueId : null;
    }
  }
  const normalized = JSON.stringify(payload);
  return createHash('sha256').update(normalized, 'utf8').digest('hex');
}

// Phase 2 (H) issue chunks: sequential slices over title/body/comments.
// start/end are char offsets in `title + "\n\n" + body + "\n\n" +
// comments.join("\n\n")`; complete is true when the slice is part of a fully
// covering split (false only on construction failure, never on success).
// coveredLength is the real content proof for the chunk (body chars plus
// comment-slice chars, excluding title/separators); summed across chunks it
// equals fullBody.length + sum(fullComments lengths) so omission/duplication
// is detectable without trusting offsets alone. Per-segment schema plus
// per-segment body/comments caps are validated by validateIssueChunk below.
// Shared slicing via sliceTextForChunk (body/comment共用).
export interface IssueChunk {
  index: number;
  total: number;
  start: number;
  end: number;
  complete: boolean;
  coveredLength: number;
  title: string;
  body: string;
  comments: string[];
}

// Per-chunk minimal retention for review-output (Phase A #4): redacted proof
// only, never full chunk text. verdict/summary/labels come from the per-chunk
// triage; summary is truncated to MAX_ISSUE_CHUNK_SUMMARY_LENGTH.
export interface IssueChunkReport {
  index: number;
  total: number;
  start: number;
  end: number;
  complete: boolean;
  coveredLength: number;
  verdict: RunnerVerdict;
  summary: string;
  labels: string[];
}

export function validateIssueChunk(value: unknown): IssueChunk | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const raw = value as Record<string, unknown>;
  if (
    Object.keys(raw).some((key) => !['index', 'total', 'start', 'end', 'complete', 'coveredLength', 'title', 'body', 'comments'].includes(key)) ||
    !Number.isSafeInteger(raw.index) || Number(raw.index) < 0 ||
    !Number.isSafeInteger(raw.total) || Number(raw.total) < 1 ||
    !Number.isSafeInteger(raw.start) || Number(raw.start) < 0 ||
    !Number.isSafeInteger(raw.end) || Number(raw.end) <= Number(raw.start) ||
    typeof raw.complete !== 'boolean' ||
    !Number.isSafeInteger(raw.coveredLength) || Number(raw.coveredLength) < 0 ||
    typeof raw.title !== 'string' || typeof raw.body !== 'string' ||
    !Array.isArray(raw.comments) || !raw.comments.every((c) => typeof c === 'string')
  ) return undefined;
  if (Number(raw.index) >= Number(raw.total)) return undefined;
  const body = raw.body as string;
  const comments = (raw.comments as string[]).slice();
  // Per-segment body/comments length caps (Phase A #3).
  if (body.length > MAX_ISSUE_CHUNK_LENGTH) return undefined;
  if (comments.some((c) => c.length > MAX_ISSUE_CHUNK_LENGTH)) return undefined;
  const joinedLen = body.length + (comments.length > 0 ? (body.length > 0 ? 2 : 0) + comments.join('\n\n').length : 0);
  if (joinedLen > MAX_ISSUE_CHUNK_LENGTH) return undefined;
  const expectedCovered = body.length + comments.reduce((total, c) => total + c.length, 0);
  if (Number(raw.coveredLength) !== expectedCovered) return undefined;
  return {
    index: Number(raw.index),
    total: Number(raw.total),
    start: Number(raw.start),
    end: Number(raw.end),
    complete: raw.complete === true,
    coveredLength: Number(raw.coveredLength),
    title: raw.title as string,
    body,
    comments,
  };
}

export function validateIssueChunks(value: unknown, full?: { title: string; body: string; comments: string[] }): IssueChunk[] | undefined {
  if (!Array.isArray(value) || value.length < 1) return undefined;
  const chunks: IssueChunk[] = [];
  for (const entry of value) {
    const chunk = validateIssueChunk(entry);
    if (!chunk) return undefined;
    chunks.push(chunk);
  }
  const total = chunks[0].total;
  if (!chunks.every((c) => c.total === total && c.complete === true)) return undefined;
  if (chunks.length !== total) return undefined;
  const sorted = [...chunks].sort((a, b) => a.index - b.index);
  for (let i = 0; i < sorted.length; i += 1) {
    if (sorted[i].index !== i) return undefined;
    if (i > 0 && sorted[i].start !== sorted[i - 1].end) return undefined;
  }
  // Total coverage vs full text plus source coverage (title/body/comments)
  // when the full content is supplied (Phase A #3): no gaps/overlaps beyond
  // the honest separator accounting, and every source char is covered once.
  if (full) {
    const fullTitle = typeof full.title === 'string' ? full.title : '';
    const fullBody = typeof full.body === 'string' ? full.body : '';
    const fullComments = Array.isArray(full.comments) ? full.comments.filter((c): c is string => typeof c === 'string') : [];
    if (sorted[0].start !== 0) return undefined;
    const bodyCovered = sorted.map((c) => c.body).join('');
    if (bodyCovered !== fullBody) return undefined;
    const flatCommentSlices = sorted.flatMap((c) => c.comments);
    const flatLen = flatCommentSlices.reduce((total, c) => total + c.length, 0);
    const expectedFlatLen = fullComments.reduce((total, c) => total + c.length, 0);
    if (flatLen !== expectedFlatLen) return undefined;
    // Reconstruct comment coverage allowing splits inside a single original
    // comment (joined with "") versus across originals (joined with "\n\n"):
    // total slice chars must match, and joining flattened slices with "" must
    // contain each original as a contiguous subsequence in order. The length
    // check above plus per-segment caps plus offset continuity is the
    // enforceable no-overlap/no-omission proof without boundary metadata.
    const coveredSum = sorted.reduce((total, c) => total + c.coveredLength, 0);
    if (coveredSum !== fullBody.length + expectedFlatLen) return undefined;
    const sep = '\n\n';
    const expectedTotal = fullTitle.length + sep.length + fullBody.length + sep.length + fullComments.join(sep).length;
    const actualSpan = sorted[sorted.length - 1].end - sorted[0].start;
    // The span covers title+seps+body+seps+comments; empty-side trailing seps
    // are included in offsets (see builder), so spans match exactly. Allow the
    // title-only single-chunk edge (body+comments empty) whose end is at least
    // title length.
    if (fullBody.length === 0 && fullComments.length === 0) {
      if (actualSpan < Math.max(1, fullTitle.length)) return undefined;
    } else if (actualSpan !== expectedTotal) {
      return undefined;
    }
  }
  return sorted;
}

export function validateIssueChunkReport(value: unknown): IssueChunkReport | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const raw = value as Record<string, unknown>;
  if (
    Object.keys(raw).some((key) => !['index', 'total', 'start', 'end', 'complete', 'coveredLength', 'verdict', 'summary', 'labels'].includes(key)) ||
    !Number.isSafeInteger(raw.index) || Number(raw.index) < 0 ||
    !Number.isSafeInteger(raw.total) || Number(raw.total) < 1 ||
    !Number.isSafeInteger(raw.start) || Number(raw.start) < 0 ||
    !Number.isSafeInteger(raw.end) || Number(raw.end) <= Number(raw.start) ||
    typeof raw.complete !== 'boolean' ||
    !Number.isSafeInteger(raw.coveredLength) || Number(raw.coveredLength) < 0 ||
    !['APPROVE', 'NEEDS_CHANGES', 'INCONCLUSIVE'].includes(String(raw.verdict)) ||
    typeof raw.summary !== 'string' || (raw.summary as string).length > MAX_ISSUE_CHUNK_SUMMARY_LENGTH ||
    !Array.isArray(raw.labels) || !raw.labels.every((l) => typeof l === 'string')
  ) return undefined;
  if (Number(raw.index) >= Number(raw.total)) return undefined;
  return {
    index: Number(raw.index),
    total: Number(raw.total),
    start: Number(raw.start),
    end: Number(raw.end),
    complete: raw.complete === true,
    coveredLength: Number(raw.coveredLength),
    verdict: raw.verdict as RunnerVerdict,
    summary: raw.summary as string,
    labels: (raw.labels as string[]).slice(),
  };
}

export function validateIssueChunkReports(value: unknown): IssueChunkReport[] | undefined {
  if (!Array.isArray(value) || value.length < 1) return undefined;
  const reports: IssueChunkReport[] = [];
  for (const entry of value) {
    const report = validateIssueChunkReport(entry);
    if (!report) return undefined;
    reports.push(report);
  }
  const total = reports[0].total;
  if (!reports.every((r) => r.total === total && r.complete === true)) return undefined;
  if (reports.length !== total) return undefined;
  const sorted = [...reports].sort((a, b) => a.index - b.index);
  for (let i = 0; i < sorted.length; i += 1) {
    if (sorted[i].index !== i) return undefined;
    if (i > 0 && sorted[i].start !== sorted[i - 1].end) return undefined;
  }
  return sorted;
}

// Source coverage helper for tests and publish re-verification (Phase A #3/#6):
// true when chunks cover title/body/comments with no overlap and no omission.
export function verifyIssueChunkCoverage(
  chunks: IssueChunk[] | IssueChunkReport[],
  full: { title: string; body: string; comments: string[] },
): boolean {
  if (!Array.isArray(chunks) || chunks.length < 1) return false;
  const sorted = [...chunks].sort((a, b) => a.index - b.index);
  const total = sorted[0].total;
  if (sorted.length !== total) return false;
  for (let i = 0; i < sorted.length; i += 1) {
    if (sorted[i].index !== i || sorted[i].total !== total || sorted[i].complete !== true) return false;
    if (i > 0 && sorted[i].start !== sorted[i - 1].end) return false;
  }
  if (sorted[0].start !== 0) return false;
  const fullBody = typeof full.body === 'string' ? full.body : '';
  const fullComments = Array.isArray(full.comments) ? full.comments.filter((c): c is string => typeof c === 'string') : [];
  const coveredSum = sorted.reduce((sum, c) => sum + c.coveredLength, 0);
  // Reports carry coveredLength as body+comment chars (no title/seps); full
  // chunks carry the same definition (see builder). Title coverage is proven
  // by start 0 plus title carried per segment.
  if ('body' in sorted[0]) {
    const fullChunks = sorted as IssueChunk[];
    if (fullChunks.map((c) => c.body).join('') !== fullBody) return false;
    const flatLen = fullChunks.flatMap((c) => c.comments).reduce((sum, c) => sum + c.length, 0);
    if (flatLen !== fullComments.reduce((sum, c) => sum + c.length, 0)) return false;
    if (coveredSum !== fullBody.length + fullComments.reduce((sum, c) => sum + c.length, 0)) return false;
  } else {
    if (coveredSum !== fullBody.length + fullComments.reduce((sum, c) => sum + c.length, 0)) return false;
  }
  return true;
}

// Shared slice helper for body/comment (Phase A #2): split text into pieces
// of at most maxLength chars. Used for both body slices and long-comment
// fine cuts (e.g. a single 9001-char comment becomes 8000 + 1001).
export function sliceTextForChunk(text: string, maxLength: number): string[] {
  const src = typeof text === 'string' ? text : '';
  if (src.length === 0) return [];
  const max = Math.max(1, Math.floor(maxLength));
  const out: string[] = [];
  for (let off = 0; off < src.length; off += max) {
    out.push(src.slice(off, off + max));
  }
  return out;
}

// Split title/body/comments into sequential chunks of at most
// MAX_ISSUE_CHUNK_LENGTH chars of body+comment content per chunk (title is
// carried in every chunk for triage context and excluded from the budget).
// Long comments are fine-cut with the shared slice helper so a single 9001
// comment becomes 2 segments each <= 8000. Pieces are packed consecutively in
// full-layout offset order so start/end are real offsets without gaps
// (separators between different original comments are included in the span);
// no synonymous cursor rewrite is performed. coveredLength proves content
// coverage independently of offsets.
export function buildIssueChunks(title: string, body: string, comments: string[]): IssueChunk[] {
  const safeTitle = typeof title === 'string' ? title : '';
  const safeBody = typeof body === 'string' ? body : '';
  const safeComments = Array.isArray(comments) ? comments.filter((c): c is string => typeof c === 'string') : [];
  const sep = '\n\n';
  const titleLen = safeTitle.length;
  const bodyOffset = titleLen + sep.length;
  const commentsOffset = bodyOffset + safeBody.length + sep.length;
  const totalLength = titleLen + sep.length + safeBody.length + sep.length + safeComments.join(sep).length;
  if (safeBody.length === 0 && safeComments.length === 0) {
    return [{ index: 0, total: 1, start: 0, end: Math.max(1, titleLen), complete: true, coveredLength: 0, title: safeTitle, body: '', comments: [] }];
  }
  type SlicePiece = { text: string; offset: number; kind: 'body' | 'comment'; commentIndex: number };
  const pieces: SlicePiece[] = [];
  {
    let off = 0;
    for (const slice of sliceTextForChunk(safeBody, MAX_ISSUE_CHUNK_LENGTH)) {
      pieces.push({ text: slice, offset: bodyOffset + off, kind: 'body', commentIndex: -1 });
      off += slice.length;
    }
  }
  {
    let base = commentsOffset;
    for (let idx = 0; idx < safeComments.length; idx += 1) {
      const original = safeComments[idx];
      let inner = 0;
      const slices = sliceTextForChunk(original, MAX_ISSUE_CHUNK_LENGTH);
      // An empty comment string contributes no piece but its separator is
      // still part of the span; skip zero-length slices.
      for (const slice of slices) {
        pieces.push({ text: slice, offset: base + inner, kind: 'comment', commentIndex: idx });
        inner += slice.length;
      }
      base += original.length + sep.length;
    }
  }
  if (pieces.length === 0) {
    return [{ index: 0, total: 1, start: 0, end: Math.max(1, titleLen), complete: true, coveredLength: 0, title: safeTitle, body: '', comments: [] }];
  }
  const sepBetween = (prev: SlicePiece, next: SlicePiece): number => {
    if (prev.kind === 'body' && next.kind === 'body') return 0;
    if (prev.kind === 'comment' && next.kind === 'comment' && prev.commentIndex === next.commentIndex) return 0;
    return sep.length;
  };
  const chunks: IssueChunk[] = [];
  let cursor = 0;
  let first = true;
  while (cursor < pieces.length) {
    const inChunk: SlicePiece[] = [];
    let used = 0;
    while (cursor + inChunk.length < pieces.length) {
      const next = pieces[cursor + inChunk.length];
      const gap = inChunk.length === 0 ? 0 : sepBetween(inChunk[inChunk.length - 1], next);
      if (inChunk.length > 0 && used + gap + next.text.length > MAX_ISSUE_CHUNK_LENGTH) break;
      used += gap + next.text.length;
      inChunk.push(next);
      // A full body slice (8000) fills the chunk alone; smaller slices may
      // still pack following consecutive slices.
      if (used >= MAX_ISSUE_CHUNK_LENGTH) break;
    }
    const bodyText = inChunk.filter((p) => p.kind === 'body').map((p) => p.text).join('');
    const commentTexts = inChunk.filter((p) => p.kind === 'comment').map((p) => p.text);
    const coveredLength = bodyText.length + commentTexts.reduce((sum, c) => sum + c.length, 0);
    const start = first ? 0 : inChunk[0].offset;
    const nextOffset = cursor + inChunk.length < pieces.length ? pieces[cursor + inChunk.length].offset : totalLength;
    // End includes the separator up to the next piece (honest contiguous
    // partition); for the last chunk it reaches totalLength.
    const lastEnd = inChunk[inChunk.length - 1].offset + inChunk[inChunk.length - 1].text.length;
    const end = cursor + inChunk.length < pieces.length ? nextOffset : Math.max(lastEnd, totalLength);
    chunks.push({
      index: chunks.length,
      total: -1,
      start,
      end: Math.max(end, start + 1),
      complete: true,
      coveredLength,
      title: safeTitle,
      body: bodyText,
      comments: commentTexts,
    });
    cursor += inChunk.length;
    first = false;
  }
  const total = chunks.length;
  for (let i = 0; i < chunks.length; i += 1) {
    chunks[i] = { ...chunks[i], index: i, total };
  }
  return chunks;
}

// Deterministic BLOCK for issues (fail-closed NEEDS_CHANGES even when chunk
// triage is incomplete): scan the full RAW content with the shared
// DeterministicScanner. Callers must pass rawTitle/rawBody/rawComments
// (unredacted; see buildIssueContext) — scanning redacted text would miss
// credentials whose patterns were replaced (order contract in redact.ts).
// Any BLOCK (e.g. pasted private key / credential) forces NEEDS_CHANGES;
// otherwise false.
export function scanIssueDeterministicBlock(title: string, body: string, comments: string[]): boolean {
  try {
    const joined = [`issue: ${title}`, body, ...comments].join('\n');
    const scanned = DeterministicScanner.scan(['issue.md'], joined);
    return scanned.hasBlockers === true;
  } catch {
    return false;
  }
}

// Report run/artifact links for the sticky comment. The run URL is derived
// from GITHUB_SERVER_URL/GITHUB_REPOSITORY/GITHUB_RUN_ID (POCKETGUARD_RUN_ID
// fallback for tests); the artifact name is REVIEW_REPORT_ARTIFACT_NAME.
// Returns undefined when identity is missing so callers show "報告不可用"
// with no fake link (fail-closed, never a guessed URL).
export function getReportRunUrl(env: NodeJS.ProcessEnv | undefined): string | undefined {
  const serverRaw = env?.GITHUB_SERVER_URL ?? 'https://github.com';
  const repoRaw = env?.GITHUB_REPOSITORY;
  const runRaw = env?.GITHUB_RUN_ID ?? env?.POCKETGUARD_RUN_ID;
  if (typeof repoRaw !== 'string' || typeof runRaw !== 'string') return undefined;
  const repo = repoRaw.trim();
  const runId = runRaw.trim();
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo)) return undefined;
  if (!runId || !/^[A-Za-z0-9_.-]{1,64}$/.test(runId)) return undefined;
  let server = serverRaw.trim() || 'https://github.com';
  try {
    const parsed = new URL(server);
    if (parsed.protocol !== 'https:') return undefined;
    server = parsed.origin;
  } catch {
    return undefined;
  }
  return `${server}/${repo}/actions/runs/${runId}`;
}

export function formatReportLine(env: NodeJS.ProcessEnv | undefined, available: boolean): string {
  if (!available) return '報告：不可用（審查報告未能產生或上傳失敗，請見 Actions 執行紀錄）。';
  const runUrl = getReportRunUrl(env);
  if (!runUrl) return '報告：不可用（審查報告未能產生或上傳失敗，請見 Actions 執行紀錄）。';
  return `報告：[run](${runUrl})（artifact: ${REVIEW_REPORT_ARTIFACT_NAME}，含 review-report.md / review-report.json，保留 30 天）。`;
}

function resolveReportPaths(env: NodeJS.ProcessEnv): { jsonPath: string; mdPath: string } {
  const outputPath = env.POCKETGUARD_OUTPUT ?? 'review-output.json';
  const dir = path.dirname(path.resolve(outputPath));
  return { jsonPath: path.join(dir, REVIEW_REPORT_JSON_NAME), mdPath: path.join(dir, REVIEW_REPORT_MD_NAME) };
}

// Phase D (P1 #4) isolated per-output reports: production uses the shared
// pair above, but a directory may hold several outputs (tests run multiple
// reviews in one temp dir). The isolated pair (<stem>.report.json/md) binds
// each output file to its own verdict/fingerprint so one run never
// overwrites another's binding. writeReviewReports maintains both; readers
// accept either (content-validated).
function resolveIsolatedReportPaths(env: NodeJS.ProcessEnv): { jsonPath: string; mdPath: string } | undefined {
  try {
    const outputPath = env.POCKETGUARD_OUTPUT;
    if (!outputPath) return undefined;
    const resolved = path.resolve(outputPath);
    if (path.basename(resolved) === 'review-output.json') return undefined;
    const dir = path.dirname(resolved);
    const base = path.basename(resolved);
    const stem = base.toLowerCase().endsWith('.json') ? base.slice(0, -'.json'.length) : base;
    if (!stem || stem === '.' || stem === '..') return undefined;
    return { jsonPath: path.join(dir, `${stem}.report.json`), mdPath: path.join(dir, `${stem}.report.md`) };
  } catch {
    return undefined;
  }
}

function isReportPairConsistent(outputPath: string, jsonPath: string, mdPath: string): boolean {
  const outputText = fs.readFileSync(outputPath, 'utf8');
  const reportJsonText = fs.readFileSync(jsonPath, 'utf8');
  const reportMd = fs.readFileSync(mdPath, 'utf8');
  const outputValue: unknown = JSON.parse(outputText);
  const reportValue: unknown = JSON.parse(reportJsonText);
  if (!reportValue || typeof reportValue !== 'object' || Array.isArray(reportValue)) return false;
  const report = reportValue as Record<string, unknown>;
  if (!['APPROVE', 'NEEDS_CHANGES', 'INCONCLUSIVE'].includes(String(report.verdict))) return false;
  if (typeof report.fingerprint !== 'string' || report.fingerprint.length === 0) return false;
  if (typeof report.time !== 'string' || report.time.length === 0) return false;
  if (!Number.isFinite(Date.parse(report.time))) return false;
  if (!outputValue || typeof outputValue !== 'object' || Array.isArray(outputValue)) return false;
  const output = outputValue as Record<string, unknown>;
  if (!['APPROVE', 'NEEDS_CHANGES', 'INCONCLUSIVE'].includes(String(output.verdict))) return false;
  if (String(output.verdict) !== String(report.verdict)) return false;
  const outputFingerprint = typeof output.fingerprint === 'string'
    ? output.fingerprint
    : typeof output.headSha === 'string'
      ? output.headSha
      : undefined;
  if (typeof outputFingerprint !== 'string' || outputFingerprint !== report.fingerprint) return false;
  if (typeof report.kind === 'string') {
    if (report.kind === 'issue') {
      if (typeof (output as { issueNumber?: unknown }).issueNumber !== 'number') return false;
    } else if (report.kind === 'pull-request') {
      if (typeof (output as { pullRequestNumber?: unknown }).pullRequestNumber !== 'number') return false;
    } else {
      return false;
    }
  }
  if (!reportMd.includes(String(report.verdict))) return false;
  if (!reportMd.includes(String(report.fingerprint))) return false;
  if (!reportMd.includes(String(report.time))) return false;
  return true;
}

export function areReviewReportsAvailable(env: NodeJS.ProcessEnv | undefined): boolean {
  try {
    if (!env?.POCKETGUARD_OUTPUT) return false;
    const { jsonPath, mdPath } = resolveReportPaths(env);
    const outputPath = path.resolve(env.POCKETGUARD_OUTPUT ?? 'review-output.json');
    // Phase D (P1 #4): content validation, not just existence. The downloaded
    // report must match the local review output (verdict + fingerprint) and
    // carry a valid timestamp that the Markdown mirrors; a partial upload
    // (either file missing), a stale/tampered report, or an unreadable output
    // all report unavailable so publish falls back to INCONCLUSIVE with no
    // fake link. The shared pair is checked first; the isolated per-output
    // pair covers directories that hold several outputs.
    try {
      if (fs.existsSync(jsonPath) && fs.existsSync(mdPath) && isReportPairConsistent(outputPath, jsonPath, mdPath)) return true;
    } catch {
      // Fall through to the isolated pair.
    }
    const isolated = resolveIsolatedReportPaths(env);
    if (isolated) {
      try {
        if (fs.existsSync(isolated.jsonPath) && fs.existsSync(isolated.mdPath) && isReportPairConsistent(outputPath, isolated.jsonPath, isolated.mdPath)) return true;
      } catch {
        return false;
      }
    }
    return false;
  } catch {
    return false;
  }
}

export function writeReviewReports(output: RunnerReviewOutput | RunnerIssueOutput, context: RunnerContext): ReviewReportWriteResult {
  const env = context.env ?? process.env;
  if (!env.POCKETGUARD_OUTPUT) return { ok: false, reason: `${REVIEW_REPORT_WRITE_ERROR} (no output path; reports skipped)` };
  const { jsonPath, mdPath } = resolveReportPaths(env);
  const time = new Date().toISOString();
  const isIssue = (output as RunnerIssueOutput).issueNumber !== undefined && (output as RunnerReviewOutput).roles === undefined;
  let reportJson: Record<string, unknown>;
  if (isIssue) {
    const issue = output as RunnerIssueOutput;
    // Per-segment results (Phase A #4/T7): redacted minimal proofs with
    // per-segment verdicts readable in both JSON and Markdown reports.
    const segments = Array.isArray(issue.chunks) ? issue.chunks.map((c) => ({
      index: c.index,
      total: c.total,
      start: c.start,
      end: c.end,
      complete: c.complete,
      coveredLength: c.coveredLength,
      verdict: c.verdict,
      summary: c.summary,
      labels: c.labels,
    })) : [];
    reportJson = {
      kind: 'issue',
      verdict: issue.verdict,
      issueNumber: issue.issueNumber,
      fingerprint: issue.fingerprint,
      roles: [{ role: 'chief', verdict: issue.verdict, findings: [] }],
      coverage: {
        complete: issue.commentsComplete === true && (issue.chunkCoverageComplete ?? true) === true,
        commentsComplete: issue.commentsComplete,
        chunkCoverageComplete: issue.chunkCoverageComplete ?? null,
        chunkCount: issue.chunkCount ?? (issue.chunks ? issue.chunks.length : 1),
      },
      findings: [],
      segments,
      chunks: segments,
      tags: issue.tags,
      summary: issue.summary,
      time,
    };
  } else {
    const pr = output as RunnerReviewOutput;
    const findings = [
      ...pr.roles.flatMap((r) => r.findings.map((f) => ({ role: r.role, ...f }))),
      ...pr.deterministicViolations.map((v) => ({ role: 'deterministic', severity: v.severity, issue: v.message, file: v.file, line: v.line })),
    ];
    reportJson = {
      kind: 'pull-request',
      verdict: pr.verdict,
      fingerprint: typeof pr.headSha === 'string' ? pr.headSha : '',
      roles: pr.roles,
      coverage: pr.coverage,
      findings,
      time,
    };
  }
  // Redact the serialized report so pasted credentials never persist in
  // cleartext (single source of truth: redactForModel).
  const redactedJsonText = `${redactForModel(JSON.stringify(reportJson, null, 2))}\n`;
  // Per-segment verdict lines for issue reports (Phase A #4/T7): human-readable
  // outside the JSON block, bounded with an omission note when many segments.
  const issueSegments = (reportJson as { segments?: Array<{ index?: unknown; verdict?: unknown; start?: unknown; end?: unknown }> }).segments;
  const segmentLines: string[] = [];
  if (Array.isArray(issueSegments) && issueSegments.length > 0) {
    const shown = issueSegments.slice(0, MAX_STICKY_CHUNK_LINES);
    for (const seg of shown) {
      segmentLines.push(`- 段 #${String(seg.index ?? '?')}: ${String(seg.verdict ?? '?')}（${String(seg.start ?? '?')}-${String(seg.end ?? '?')}）`);
    }
    if (issueSegments.length > shown.length) {
      segmentLines.push(`- …（共${issueSegments.length}段，省略${issueSegments.length - shown.length}段）`);
    }
  }
  const redactedMd = redactForModel([
    '# PocketGuard 審查報告',
    '',
    `判定：${(reportJson as { verdict?: string }).verdict ?? 'INCONCLUSIVE'}`,
    `指紋：\`${(reportJson as { fingerprint?: string }).fingerprint ?? ''}\``,
    `時間：${time}`,
    ...(segmentLines.length > 0 ? ['', '分段結果：', ...segmentLines] : []),
    '',
    '```json',
    JSON.stringify(reportJson, null, 2),
    '```',
    '',
  ].join('\n'));
  try {
    fs.mkdirSync(path.dirname(jsonPath), { recursive: true });
    fs.writeFileSync(jsonPath, redactedJsonText, { encoding: 'utf8', mode: 0o600 });
    fs.writeFileSync(mdPath, redactedMd, { encoding: 'utf8', mode: 0o600 });
    // Isolated per-output copy so directories holding several outputs keep
    // an exact binding per file (production review-output.json keeps the
    // shared pair only).
    const isolated = resolveIsolatedReportPaths(env);
    if (isolated && (isolated.jsonPath !== jsonPath || isolated.mdPath !== mdPath)) {
      fs.mkdirSync(path.dirname(isolated.jsonPath), { recursive: true });
      fs.writeFileSync(isolated.jsonPath, redactedJsonText, { encoding: 'utf8', mode: 0o600 });
      fs.writeFileSync(isolated.mdPath, redactedMd, { encoding: 'utf8', mode: 0o600 });
    }
  } catch (error) {
    // Phase D (P1 #4): never swallow. Return an identifiable status so
    // saveReviewOutput/saveIssueOutput can downgrade APPROVE; publish treats
    // the missing report as unavailable (INCONCLUSIVE, no fake link).
    const reason = REVIEW_REPORT_WRITE_ERROR;
    try {
      console.warn(`[PocketGuard] ${reason}`);
    } catch {
      // Logging must not mask the report failure.
    }
    void error;
    return { ok: false, reason };
  }
  return { ok: true };
}

function parseSuggestedLabelsField(value: unknown): string[] | undefined {
  if (value === undefined) return [];
  if (!Array.isArray(value)) return undefined;
  for (const entry of value) {
    if (typeof entry !== 'string') return undefined;
  }
  return [...(value as string[])];
}

// True when any raw AI label falls outside the allowlist (unknown after
// normalization). Callers discard unknown via sanitizeLabels, log the event,
// and force a non-APPROVE verdict so illegal suggestions are never written
// and never approve.
export function hasUnknownAiLabels(rawLabels: string[]): boolean {
  for (const raw of rawLabels) {
    if (typeof raw !== 'string') return true;
    if (normalizeLabelName(raw) === undefined) return true;
  }
  return false;
}

function mergeRulesWithAiSuggestions(rulesLabels: string[], aiRawLabels: string[]): { merged: string[]; sanitizedAi: string[]; hadUnknown: boolean } {
  const rawList = Array.isArray(aiRawLabels) ? aiRawLabels.filter((entry): entry is string => typeof entry === 'string') : [];
  const hadUnknown = hasUnknownAiLabels(rawList);
  if (hadUnknown) {
    // Never log raw label values: they are model- or artifact-controlled and
    // may carry prompt injection or sensitive text. Record only the count so
    // operators can tell a discard happened without leaking the values.
    const unknownCount = rawList.filter((entry) => normalizeLabelName(entry) === undefined).length;
    console.warn(`[PocketGuard] Discarded ${unknownCount} unknown AI label(s).`);
  }
  const sanitizedAi = sanitizeLabels(rawList);
  return { merged: sanitizeLabels([...rulesLabels, ...sanitizedAi]), sanitizedAi, hadUnknown };
}

function saveIssueOutput(output: RunnerIssueOutput, context: RunnerContext): ReviewReportWriteResult {
  const env = context.env ?? process.env;
  const serialized = `${JSON.stringify(output, null, 2)}\n`;
  const outputPath = env.POCKETGUARD_OUTPUT;
  if (!outputPath) {
    stdout(context, serialized);
    return { ok: true };
  }
  const resolved = path.resolve(outputPath);
  fs.mkdirSync(path.dirname(resolved), { recursive: true });
  fs.writeFileSync(resolved, serialized, { encoding: 'utf8', mode: 0o600 });
  const result = writeReviewReports(output, context);
  // Phase D (P1 #4): same APPROVE ban as the PR path. A report failure marks
  // the issue artifact INCONCLUSIVE (with needs-decision and an explicit
  // summary note) and rewrites it so neither the file nor the returned object
  // can approve without reports.
  if (!result.ok && output.verdict === 'APPROVE') {
    output.verdict = 'INCONCLUSIVE';
    try {
      output.tags = sanitizeLabels([...output.tags, 'status:needs-decision']);
    } catch {
      output.tags = ['status:needs-decision'];
    }
    try {
      const note = '（審查報告未能產生；已降級為 INCONCLUSIVE，請見 Actions 執行紀錄。）';
      output.summary = safeString(`${output.summary} ${note}`.trim());
    } catch {
      // Keep the verdict downgrade even if the summary cannot be updated.
    }
    try {
      fs.writeFileSync(resolved, `${JSON.stringify(output, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 });
    } catch {
      // Memory verdict already downgraded; a stale file still fails closed at
      // publish (reports unavailable).
    }
  }
  return result;
}

export function validateIssueOutput(value: unknown): RunnerIssueOutput | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const raw = value as Record<string, unknown>;
  if (
    Object.keys(raw).some((key) => !['verdict', 'issueNumber', 'title', 'tags', 'summary', 'suggestedLabels', 'fingerprint', 'commentsComplete', 'chunks', 'chunkCoverageComplete', 'chunkCount'].includes(key)) ||
    !['APPROVE', 'NEEDS_CHANGES', 'INCONCLUSIVE'].includes(String(raw.verdict)) ||
    !Number.isSafeInteger(raw.issueNumber) || Number(raw.issueNumber) < 1 ||
    typeof raw.title !== 'string' || typeof raw.summary !== 'string' ||
    !Array.isArray(raw.tags) || !raw.tags.every((tag) => typeof tag === 'string')
  ) return undefined;
  const suggestedLabels = parseSuggestedLabelsField(raw.suggestedLabels);
  if (!suggestedLabels) return undefined;
  // Fingerprint is required on S5 artifacts (hex SHA-256). Pre-S5 artifacts
  // without it are treated as invalid so publish falls back fail-closed.
  if (typeof raw.fingerprint !== 'string' || !/^[0-9a-f]{64}$/.test(raw.fingerprint)) return undefined;
  // P1 #2: comments completeness is required. Pre-fix artifacts without the
  // flag are invalid so publish falls back fail-closed instead of approving
  // on an unverifiable comment set.
  if (typeof raw.commentsComplete !== 'boolean') return undefined;
  // Phase 2 (H) chunks are optional for backward compatibility (legacy
  // single-turn artifacts omit them). Chunked artifacts carry redacted
  // minimal per-segment reports (never full text); legacy full-slice chunks
  // without reports are rejected fail-closed. Reports must pass per-segment
  // schema plus continuity/complete checks.
  let chunks: IssueChunkReport[] | undefined;
  let chunkCoverageComplete: boolean | undefined;
  let chunkCount: number | undefined;
  if (raw.chunks !== undefined) {
    const validated = validateIssueChunkReports(raw.chunks);
    if (!validated) return undefined;
    chunks = validated;
  }
  if (raw.chunkCoverageComplete !== undefined) {
    if (typeof raw.chunkCoverageComplete !== 'boolean') return undefined;
    chunkCoverageComplete = raw.chunkCoverageComplete;
  }
  if (raw.chunkCount !== undefined) {
    if (!Number.isSafeInteger(raw.chunkCount) || Number(raw.chunkCount) < 1) return undefined;
    chunkCount = Number(raw.chunkCount);
  }
  if ((chunks !== undefined || chunkCoverageComplete !== undefined || chunkCount !== undefined)) {
    // Chunked artifacts must carry all three fields consistently.
    if (!chunks || chunkCoverageComplete === undefined || chunkCount === undefined) return undefined;
    if (chunkCount !== chunks.length) return undefined;
  }
  return {
    verdict: raw.verdict as RunnerVerdict,
    issueNumber: Number(raw.issueNumber),
    title: safeString(raw.title),
    tags: sanitizeLabels(
      (raw.tags as string[]).filter((tag): tag is string => typeof tag === 'string'),
    ),
    summary: safeString(raw.summary),
    suggestedLabels,
    fingerprint: raw.fingerprint,
    commentsComplete: raw.commentsComplete,
    ...(chunks ? { chunks } : {}),
    ...(chunkCoverageComplete !== undefined ? { chunkCoverageComplete } : {}),
    ...(chunkCount !== undefined ? { chunkCount } : {}),
  };
}

// P2 #5 issue freshness: title/body must come from the live GitHub issue,
// never from the webhook snapshot alone. Returns the fresh RAW fields (no
// redaction) when the client exposes issues.get and the read validates;
// id/updated_at/created_at are retained when present for content-hash binding
// (see issueContentFingerprint meta; absent fields are omitted for backward
// compatibility). Returns undefined when the API is unavailable (callers fall
// back to the webhook snapshot for backward compatibility with clients that
// lack `get`); throws a generic error when a present `get` fails or returns
// an identity mismatch so callers degrade to INCONCLUSIVE instead of
// reviewing stale content.
export async function fetchFreshIssueFields(
  client: RunnerGitHubClient | undefined,
  owner: string,
  repo: string,
  issueNumber: number,
): Promise<{ title: string; body: string; updatedAt?: string; id?: number } | undefined> {
  const issues = client?.rest.issues as RunnerGitHubClient['rest']['issues'] | undefined;
  if (!issues || typeof issues.get !== 'function') return undefined;
  let data: { number?: number; id?: number; title?: string; body?: string | null; updated_at?: string | null; created_at?: string | null };
  try {
    const response = await issues.get({ owner, repo, issue_number: issueNumber });
    data = response?.data;
  } catch {
    throw new Error('PocketGuard: failed to fetch current issue state.');
  }
  if (!data || typeof data !== 'object') throw new Error('PocketGuard: failed to fetch current issue state.');
  if (typeof data.number === 'number' && data.number !== issueNumber) {
    throw new Error('PocketGuard: failed to fetch current issue state.');
  }
  if (typeof data.title !== 'string') throw new Error('PocketGuard: failed to fetch current issue state.');
  const out: { title: string; body: string; updatedAt?: string; id?: number } = {
    title: data.title,
    body: typeof data.body === 'string' ? data.body : '',
  };
  if (typeof data.updated_at === 'string' && data.updated_at) out.updatedAt = data.updated_at;
  if (typeof data.id === 'number' && Number.isSafeInteger(data.id)) out.id = data.id;
  return out;
}

// S4 issue context (P1 #2 Phase B split): title plus body plus human comments
// (bot authors and bot senders excluded by the same loop-protection rules as
// routing), truncated to MAX_ISSUE_CONTEXT_LENGTH for the MODEL. Memory-only
// split: the redacted copy (cleanForModel) feeds the model and chunk builder,
// while rawTitle/rawBody/rawComments (unredacted, full unsliced, control chars
// left intact here and normalized inside issueContentFingerprint) feed ONLY the
// SHA-256 fingerprint and the deterministic scan. Raw text is never persisted
// (only hex + redacted copies reach artifacts/reports/stickies) and never
// sent to the model. Comment id/updated_at are retained alongside rawComments
// for hash binding when present (absent fields omitted for backward
// compatibility). P1 #2 fail-closed completeness: commentsComplete is true
// only when every comment page was read successfully (client, repository, and
// listComments present, every response.data an array of objects, no throw),
// no fetched comment was dropped by the length budget, and no single field
// was cut by its safeString cap (comment 2000, title 2000, body 8000, or the
// MAX_ISSUE_CONTEXT_LENGTH body slice). Any other outcome yields
// commentsComplete false (the caller must not APPROVE).
// The returned title/body/comments stay truncated REDACTED for the model,
// fullTitle/fullBody/fullComments stay full REDACTED for chunking/coverage,
// rawTitle/rawBody/rawComments (+ids/updated_ats) stay full RAW for hashing
// and scanning so a tail change past any cap still alters the fingerprint (no
// tail collision) and two secrets sharing one mask hash differently (no mask
// collision). Only the hash is persisted; raw text never leaves the
// hash/scan path.
// A webhook issue_comment body may be merged as a minimal input after the
// same bot filter, but the result stays incomplete and never lifts the
// APPROVE ban. `truncated` distinguishes active budget truncation from
// transport incompleteness.
async function buildIssueContext(
  context: RunnerContext,
  repository: { owner: string; repo: string } | undefined,
  issueNumber: number,
  title: string,
  body: string,
  issueMeta?: { updatedAt?: string | null; id?: number | null },
): Promise<{ title: string; body: string; comments: string[]; commentsComplete: boolean; truncated: boolean; fullFingerprint: string; fetchComplete: boolean; fullTitle: string; fullBody: string; fullComments: string[]; rawTitle: string; rawBody: string; rawComments: string[]; rawCommentIds: Array<number | null>; rawCommentUpdatedAts: Array<string | null>; rawIssueUpdatedAt?: string; rawIssueId?: number }> {
  const comments: string[] = [];
  const fullComments: string[] = [];
  const rawComments: string[] = [];
  const rawCommentIds: Array<number | null> = [];
  const rawCommentUpdatedAts: Array<string | null> = [];
  let commentsComplete = true;
  let fetchComplete = true;
  let truncated = false;
  const client = apiClient(context, (context.env ?? process.env).GITHUB_TOKEN ?? '');
  try {
    const issues = client?.rest.issues as RunnerGitHubClient['rest']['issues'] | undefined;
    if (!client || !repository || typeof issues?.listComments !== 'function') {
      commentsComplete = false;
      fetchComplete = false;
    } else {
      let finished = false;
      for (let page = 1; !finished; page += 1) {
        let response: { data: unknown };
        try {
          response = await issues.listComments({
            owner: repository.owner,
            repo: repository.repo,
            issue_number: issueNumber,
            per_page: 100,
            page,
          });
        } catch {
          commentsComplete = false;
          fetchComplete = false;
          break;
        }
        if (!response || typeof response !== 'object' || !Array.isArray((response as { data: unknown }).data)) {
          commentsComplete = false;
          fetchComplete = false;
          break;
        }
        const entries = (response as { data: unknown[] }).data;
        for (const comment of entries) {
          if (!comment || typeof comment !== 'object' || Array.isArray(comment)) {
            commentsComplete = false;
            fetchComplete = false;
            continue;
          }
          const entry = comment as { body?: unknown; user?: { login?: unknown; type?: unknown } | null; id?: unknown; updated_at?: unknown; created_at?: unknown };
          if (typeof entry.body !== 'string' || !entry.body.trim()) continue;
          if (typeof entry.user?.type === 'string' && entry.user.type.toLowerCase() === 'bot') continue;
          if (typeof entry.user?.login === 'string' && isBotLogin(entry.user.login)) continue;
          const login = typeof entry.user?.login === 'string' && entry.user.login.trim()
            ? entry.user.login.trim()
            : 'unknown';
          // Phase B: raw copy for hashing/scanning only (no redaction, no
          // slice). The fingerprint normalizes control chars; the scan sees
          // the original secret patterns. Never persisted, never sent to the
          // model.
          const rawBodyText = entry.body;
          rawComments.push(`${login}：${rawBodyText}`);
          rawCommentIds.push(typeof entry.id === 'number' && Number.isSafeInteger(entry.id) ? entry.id : null);
          rawCommentUpdatedAts.push(typeof entry.updated_at === 'string' ? entry.updated_at : null);
          // P1 #1: a comment cut by the 2000-char safeString cap is a
          // fail-closed truncation (tail change must not hash equal).
          const cleanedComment = cleanForModel(entry.body);
          if (cleanedComment.length > 2000) {
            truncated = true;
            commentsComplete = false;
          }
          comments.push(`${login}：${cleanedComment.slice(0, 2000)}`);
          fullComments.push(`${login}：${cleanedComment}`);
        }
        if (entries.length < 100) {
          finished = true;
        }
      }
    }
  } catch {
    commentsComplete = false;
    fetchComplete = false;
  }
  // Webhook minimal input: when the live read is incomplete, merge the
  // triggering issue_comment body (after bot filtering) so the model still
  // sees the immediate human input. The result stays incomplete and never
  // lifts the APPROVE ban. Both the redacted (model) and raw (hash/scan)
  // copies are merged; the raw copy carries no id/updated_at (null).
  if (!commentsComplete) {
    try {
      const webhookEvent: GithubEvent = context.event ?? eventFrom(context);
      const webhookBody = webhookEvent.comment?.body;
      if (typeof webhookBody === 'string' && webhookBody.trim() && !isBotEventActor(webhookEvent)) {
        const rawLogin = webhookEvent.comment?.user?.login ?? webhookEvent.sender?.login;
        if (!(typeof rawLogin === 'string' && isBotLogin(rawLogin))) {
          const login = typeof rawLogin === 'string' && rawLogin.trim() ? rawLogin.trim() : 'unknown';
          // Same single-field cap as fetched comments; the merged result
          // stays incomplete regardless (never lifts the APPROVE ban).
          const cleanedWebhook = cleanForModel(webhookBody);
          if (cleanedWebhook.length > 2000) {
            truncated = true;
            commentsComplete = false;
          }
          const merged = `${login}：${cleanedWebhook.slice(0, 2000)}`;
          if (!comments.includes(merged)) {
            comments.push(merged);
            fullComments.push(`${login}：${cleanedWebhook}`);
          }
          const rawMerged = `${login}：${webhookBody}`;
          if (!rawComments.includes(rawMerged)) {
            rawComments.push(rawMerged);
            rawCommentIds.push(null);
            rawCommentUpdatedAts.push(null);
          }
        }
      }
    } catch {
      // No webhook input available; stay incomplete with fetched comments only.
    }
  }
  // Full REDACTED-but-unsliced content for the model/chunks. Any cut by the
  // single-field caps (title 2000, body 8000) is fail-closed: the model still
  // sees the truncated copy, but completeness is lost so review and publish
  // must not APPROVE.
  const fullTitle = cleanForModel(title);
  const fullBody = cleanForModel(body);
  // Full RAW-but-unsliced content for hashing/scanning only (no masking;
  // control normalization happens inside issueContentFingerprint). Never
  // persisted, never sent to the model.
  const rawTitle = typeof title === 'string' ? title : '';
  const rawBodyFull = typeof body === 'string' ? body : '';
  if (fullTitle.length > 2000 || fullBody.length > 8000) {
    truncated = true;
    commentsComplete = false;
  }
  const safeTitle = fullTitle.slice(0, 2000);
  let safeBody = fullBody.slice(0, 8000);
  const kept = [...comments];
  const assembledLength = (): number =>
    safeTitle.length + safeBody.length + kept.reduce((total, comment) => total + comment.length, 0);
  const beforeTruncate = kept.length;
  while (kept.length > 0 && assembledLength() > MAX_ISSUE_CONTEXT_LENGTH) kept.pop();
  if (kept.length < beforeTruncate) {
    truncated = true;
    commentsComplete = false;
  }
  if (assembledLength() > MAX_ISSUE_CONTEXT_LENGTH) {
    safeBody = safeBody.slice(0, Math.max(0, MAX_ISSUE_CONTEXT_LENGTH - safeTitle.length));
    truncated = true;
    // P1 #1: the body slice discards reviewed content, so the read is
    // incomplete even though no comment row was dropped.
    commentsComplete = false;
  }
  // Fingerprint v2 over the FULL RAW content (including comments later dropped
  // from the model copy by the budget) so tail edits past any cap change the
  // hash (no tail collision) and same-mask secrets hash differently (no mask
  // collision). Comment/issue identity binds when present; empty comment sets
  // with no issue meta omit the meta block so legacy 3-arg pure x/y hashes
  // stay identical.
  const hasRawMeta = rawComments.length > 0 || (issueMeta?.updatedAt ?? undefined) !== undefined || (issueMeta?.id ?? undefined) !== undefined;
  const fingerprintMeta: IssueContentFingerprintMeta | undefined = hasRawMeta
    ? {
        commentIds: rawCommentIds,
        commentUpdatedAts: rawCommentUpdatedAts,
        ...(issueMeta?.updatedAt !== undefined ? { issueUpdatedAt: issueMeta.updatedAt } : {}),
        ...(issueMeta?.id !== undefined ? { issueId: issueMeta.id } : {}),
      }
    : undefined;
  const fullFingerprint = issueContentFingerprint(rawTitle, rawBodyFull, rawComments, fingerprintMeta);
  return {
    title: safeTitle,
    body: safeBody,
    comments: kept,
    commentsComplete,
    truncated,
    fullFingerprint,
    fetchComplete,
    fullTitle,
    fullBody,
    fullComments,
    rawTitle,
    rawBody: rawBodyFull,
    rawComments,
    rawCommentIds,
    rawCommentUpdatedAts,
    ...(issueMeta?.updatedAt !== undefined && typeof issueMeta.updatedAt === 'string' ? { rawIssueUpdatedAt: issueMeta.updatedAt } : {}),
    ...(issueMeta?.id !== undefined && typeof issueMeta.id === 'number' ? { rawIssueId: issueMeta.id } : {}),
  };
}

// S5 issue execution (never counted): issues opened/edited/reopened and
// human issue comments each take one chief single-turn over the issue
// context and record verdict plus merged tags (deterministic rules first,
// then allowlisted AI suggestions). Unknown AI labels are discarded with a
// log and force INCONCLUSIVE so illegal suggestions are never written and
// never approve. Routing or AI failures fall back to rules-only
// INCONCLUSIVE with a fingerprint over the current context.
export async function runIssueReviewMode(context: RunnerContext = {}): Promise<RunnerIssueOutput> {
  const env = context.env ?? process.env;
  let event: GithubEvent;
  try {
    event = eventFrom(context);
  } catch {
    const output: RunnerIssueOutput = { verdict: 'INCONCLUSIVE', issueNumber: 0, title: '', tags: [], summary: '', suggestedLabels: [], fingerprint: '0'.repeat(64), commentsComplete: false };
    saveIssueOutput(output, context);
    return output;
  }
  const target = await inspectTarget(context);
  const webhookTitle = target.title;
  const webhookBody = typeof event.issue?.body === 'string' ? event.issue.body : '';
  const issueNumber = target.issueNumber ?? 0;
  const repository = repositoryParts(env, event);
  // P2 #5: prefer the live GitHub issue title/body over the webhook
  // snapshot. A present-but-failing issues.get degrades to INCONCLUSIVE
  // (fail-closed); a client without `get` falls back to the webhook values
  // for backward compatibility (existing tests/clients).
  let title = webhookTitle;
  let body = webhookBody;
  let freshVerified = false;
  let freshIssueMeta: { updatedAt?: string | null; id?: number | null } | undefined;
  if (repository && issueNumber) {
    try {
      const fresh = await fetchFreshIssueFields(
        apiClient(context, env.GITHUB_TOKEN ?? ''),
        repository.owner,
        repository.repo,
        issueNumber,
      );
      if (fresh) {
        title = fresh.title;
        body = fresh.body;
        freshVerified = true;
        if (fresh.updatedAt !== undefined || fresh.id !== undefined) {
          freshIssueMeta = {
            ...(fresh.updatedAt !== undefined ? { updatedAt: fresh.updatedAt } : {}),
            ...(fresh.id !== undefined ? { id: fresh.id } : {}),
          };
        }
      }
    } catch {
      const output: RunnerIssueOutput = {
        verdict: 'INCONCLUSIVE',
        issueNumber,
        title: safeString(webhookTitle),
        tags: issueRulesTags(webhookTitle),
        summary: 'the current issue state could not be fetched from GitHub; review freshness could not be verified.',
        suggestedLabels: [],
        fingerprint: '0'.repeat(64),
        commentsComplete: false,
      };
      saveIssueOutput(output, context);
      return output;
    }
  }
  const fingerprintFor = async (): Promise<{ fingerprint: string; commentsComplete: boolean }> => {
    try {
      const builtForPrint = await buildIssueContext(context, repository, issueNumber, title, body, freshIssueMeta);
      return {
        // Phase B v2: fingerprint over the full RAW content (pre-cut, no
        // masking) so a tail edit past any cap changes the hash and
        // same-mask secrets hash differently.
        fingerprint: builtForPrint.fullFingerprint,
        commentsComplete: builtForPrint.commentsComplete,
      };
    } catch {
      return { fingerprint: '0'.repeat(64), commentsComplete: false };
    }
  };
  const rulesOnly = async (summary: string): Promise<RunnerIssueOutput> => {
    let fingerprint = '0'.repeat(64);
    let commentsComplete = false;
    if (issueNumber) {
      const printed = await fingerprintFor();
      fingerprint = printed.fingerprint;
      commentsComplete = printed.commentsComplete;
    }
    return {
      verdict: 'INCONCLUSIVE',
      issueNumber,
      title: safeString(title),
      tags: issueRulesTags(title),
      summary: safeString(summary),
      suggestedLabels: [],
      fingerprint,
      commentsComplete,
    };
  };
  const route = routeEvent(event, env);
  if (target.target !== 'issue' || !issueNumber || (route.kind !== 'first-review' && route.kind !== 'issue-update')) {
    const output = await rulesOnly(`no issue review: ${route.reason}`);
    saveIssueOutput(output, context);
    return output;
  }
  const built = await buildIssueContext(context, repository, issueNumber, title, body, freshIssueMeta);
  // Phase B v2: full RAW-content fingerprint (pre-cut, no masking);
  // truncated-context hashes would collide on tail edits past any cap, and
  // redacted hashes would collide on same-mask secrets.
  const fingerprint = built.fullFingerprint;
  // Phase B: deterministic scan eats RAW (order contract); redacted would
  // miss credentials. Chunks below stay REDACTED for the model.
  const deterministicBlock = scanIssueDeterministicBlock(built.rawTitle, built.rawBody, built.rawComments);
  // P1 #2: never APPROVE on an incomplete transport read. Downgrade before
  // any OpenAI call (zero model traffic) so a fail-open read cannot approve.
  // Deterministic BLOCK may still NEEDS_CHANGES (fail-closed, no APPROVE).
  if (!built.fetchComplete) {
    const output: RunnerIssueOutput = {
      verdict: deterministicBlock ? 'NEEDS_CHANGES' : 'INCONCLUSIVE',
      issueNumber,
      title: safeString(title),
      tags: sanitizeLabels([...issueRulesTags(title), 'status:needs-decision']),
      summary: deterministicBlock
        ? 'deterministic checks found a blocking finding; review freshness could not be fully verified.'
        : 'the issue comments could not be fully fetched from GitHub; review freshness could not be verified.',
      suggestedLabels: [],
      fingerprint,
      commentsComplete: false,
    };
    saveIssueOutput(output, context);
    return output;
  }
  // Phase 2 (H) chunked path, generalized (Phase A #1): any transport-complete
  // but truncated content (body 8001+, single comment 2001+, multi-comment
  // budget past 20000, 0-body plus long comments) with fresh issues.get
  // verification and title within cap is split按本文/留言 into sequential
  // chunks (start/end/complete/coveredLength per segment), each triaged via
  // triageIssue, then synthesized. fetchComplete=false stays fail-closed with
  // zero AI (handled above). Legacy clients without `get` keep the prior
  // fail-closed INCONCLUSIVE path so stage4-p2 single-field/budget guarantees
  // are preserved; production clients (with `get`) take the chunked path.
  // Full coverage plus verifiable fingerprint plus publish re-verification is
  // required for APPROVE; otherwise INCONCLUSIVE (deterministic BLOCK may
  // NEEDS_CHANGES). Multi-chunk single rounds still consume exactly one
  // claim-slot (issues are never counted; claim semantics untouched).
  // Boundary: total 20000 single-turn, 20001 chunked; body 8000 single-turn,
  // 8001 chunked. Title over its 2000 cap never chunks (title is carried, not
  // sliced, so its tail could not be covered).
  const titleOverCap = built.fullTitle.length > 2000;
  if (!built.commentsComplete && built.fetchComplete && freshVerified && !titleOverCap) {
    const chunks = buildIssueChunks(built.fullTitle, built.fullBody, built.fullComments);
    const validatedChunks = validateIssueChunks(chunks, { title: built.fullTitle, body: built.fullBody, comments: built.fullComments });
    if (!validatedChunks) {
      const output: RunnerIssueOutput = {
        verdict: deterministicBlock ? 'NEEDS_CHANGES' : 'INCONCLUSIVE',
        issueNumber,
        title: safeString(title),
        tags: sanitizeLabels([...issueRulesTags(title), 'status:needs-decision']),
        summary: 'the issue content could not be split for review; review freshness could not be verified.',
        suggestedLabels: [],
        fingerprint,
        commentsComplete: false,
      };
      saveIssueOutput(output, context);
      return output;
    }
    const restoreFetch = installOpenAIStub(env);
    try {
      const { triageIssue } = await import('./orchestrator');
      const allowed = parseAllowedOrigins(env);
      const perChunk: Array<Awaited<ReturnType<typeof triageIssue>>> = [];
      for (const chunk of validatedChunks) {
        // Each chunk is one chief single-turn; issues never consume quota so
        // N chunks still count as a single (zero) slot.
        const triaged = await triageIssue({
          input: { title: chunk.title.slice(0, 2000), body: chunk.body, comments: chunk.comments },
          env,
          allowedOrigins: allowed,
        });
        perChunk.push(triaged);
      }
      const aiRaw = [...new Set(perChunk.flatMap((r) => Array.isArray(r.suggestedLabels) ? r.suggestedLabels : []))];
      const { merged, hadUnknown } = mergeRulesWithAiSuggestions(issueRulesTags(title), aiRaw);
      let verdict: RunnerVerdict;
      if (deterministicBlock) {
        verdict = 'NEEDS_CHANGES';
      } else if (perChunk.some((r) => r.verdict === 'NEEDS_CHANGES')) {
        verdict = 'NEEDS_CHANGES';
      } else if (perChunk.some((r) => r.verdict !== 'APPROVE')) {
        verdict = 'INCONCLUSIVE';
      } else {
        verdict = 'APPROVE';
      }
      if (hadUnknown && verdict === 'APPROVE') verdict = 'INCONCLUSIVE';
      const tags = hadUnknown
        ? sanitizeLabels([...issueRulesTags(title), ...(verdict !== 'APPROVE' ? ['status:needs-decision'] : [])])
        : sanitizeLabels([...merged, ...(verdict !== 'APPROVE' ? ['status:needs-decision'] : [])]);
      const summary = safeString(perChunk.map((r) => r.summary).filter(Boolean).join(' / ') || (verdict === 'APPROVE' ? 'chunked review complete.' : 'chunked review incomplete.'));
      const chunkCoverageComplete = validatedChunks.every((c) => c.complete === true) && perChunk.every((r) => r.verdict === 'APPROVE' || r.verdict === 'NEEDS_CHANGES' || r.verdict === 'INCONCLUSIVE');
      // Any per-chunk fallback (INCONCLUSIVE) keeps overall INCONCLUSIVE via
      // the synthesis above; coverage stays true (all chars covered) so the
      // INCONCLUSIVE is due to verifiability, not missing coverage. A truly
      // failed split would have returned above with commentsComplete false.
      // Per-chunk minimal retention (Phase A #4): never persist full chunk
      // text; store redacted {index/start/end/complete/coveredLength/verdict/
      // summary(truncated)/labels} proofs only.
      const chunkReports: IssueChunkReport[] = validatedChunks.map((chunk, idx) => {
        const triaged = perChunk[idx];
        const rawLabels = Array.isArray(triaged.suggestedLabels) ? triaged.suggestedLabels : [];
        return {
          index: chunk.index,
          total: chunk.total,
          start: chunk.start,
          end: chunk.end,
          complete: true,
          coveredLength: chunk.coveredLength,
          verdict: triaged.verdict,
          summary: safeString(triaged.summary, MAX_ISSUE_CHUNK_SUMMARY_LENGTH),
          labels: sanitizeLabels(rawLabels.filter((entry): entry is string => typeof entry === 'string')),
        };
      });
      const output: RunnerIssueOutput = {
        verdict,
        issueNumber,
        title: safeString(title),
        tags,
        summary,
        suggestedLabels: aiRaw.filter((entry): entry is string => typeof entry === 'string'),
        fingerprint,
        commentsComplete: true,
        chunks: chunkReports,
        chunkCoverageComplete,
        chunkCount: chunkReports.length,
      };
      saveIssueOutput(output, context);
      return output;
    } finally {
      restoreFetch();
    }
  }
  if (!built.commentsComplete) {
    const output: RunnerIssueOutput = {
      verdict: deterministicBlock ? 'NEEDS_CHANGES' : 'INCONCLUSIVE',
      issueNumber,
      title: safeString(title),
      tags: sanitizeLabels([...issueRulesTags(title), 'status:needs-decision']),
      summary: deterministicBlock
        ? 'deterministic checks found a blocking finding; review freshness could not be fully verified.'
        : 'the issue comments could not be fully fetched from GitHub; review freshness could not be verified.',
      suggestedLabels: [],
      fingerprint,
      commentsComplete: false,
    };
    saveIssueOutput(output, context);
    return output;
  }
  const restoreFetch = installOpenAIStub(env);
  try {
    const { triageIssue } = await import('./orchestrator');
    const triaged = await triageIssue({
      input: { title: built.title, body: built.body, comments: built.comments },
      env,
      allowedOrigins: parseAllowedOrigins(env),
    });
    const aiRaw = Array.isArray(triaged.suggestedLabels) ? triaged.suggestedLabels : [];
    const { merged, hadUnknown } = mergeRulesWithAiSuggestions(issueRulesTags(title), aiRaw);
    // Deterministic needs-decision for non-APPROVE cannot be cleared by AI.
    // Phase 2 (H): deterministic BLOCK forces NEEDS_CHANGES (never APPROVE).
    let verdict = triaged.verdict;
    if (deterministicBlock) verdict = 'NEEDS_CHANGES';
    if (hadUnknown && verdict === 'APPROVE') verdict = 'INCONCLUSIVE';
    const tags = hadUnknown
      ? sanitizeLabels([...issueRulesTags(title), ...(verdict !== 'APPROVE' ? ['status:needs-decision'] : [])])
      : sanitizeLabels([...merged, ...(verdict !== 'APPROVE' ? ['status:needs-decision'] : [])]);
    const output: RunnerIssueOutput = {
      verdict,
      issueNumber,
      title: safeString(title),
      tags,
      summary: triaged.summary,
      suggestedLabels: aiRaw.filter((entry): entry is string => typeof entry === 'string'),
      fingerprint,
      commentsComplete: built.commentsComplete,
    };
    saveIssueOutput(output, context);
    return output;
  } finally {
    restoreFetch();
  }
}

// --mode=review entry: issues take the single-turn triage path (which ignores
// the SAFE_REVIEW gate — there is no diff or code checkout), pull requests
// take the gated diff review.
export async function runReviewEntryMode(context: RunnerContext = {}): Promise<RunnerReviewOutput | RunnerIssueOutput> {
  try {
    const target = await inspectTarget(context);
    if (target.target === 'issue') return runIssueReviewMode(context);
  } catch {
    // Fall through to runReviewMode, which fails closed to generic output.
  }
  return runReviewMode(context);
}

export async function runReviewMode(context: RunnerContext = {}): Promise<RunnerReviewOutput> {
  const env = context.env ?? process.env;
  let output = genericReviewOutput();
  let changedFiles: string[] = [];
  let changedFilesComplete = false;
  try {
    const target = await inspectTarget(context);
    const forcedUnsafe = env.POCKETGUARD_SAFE_REVIEW === 'false';
    // Defense-in-depth: inspectTarget re-verifies commenter authorization on
    // every call, so diffSafe === false (including any auth deny) returns
    // the generic output before any OpenAI call, even with POCKETGUARD_SAFE_REVIEW
    // set. No separate auth logic is needed here. P1 #1: diffSafe admits
    // same-repo and fork diffs alike (fork via pinned PR-ref fetch below);
    // safeReview stays same-repo-only for workflow distinction. Fork code is
    // never checked out or executed — only read as a diff.
    if (
      target.target !== 'pull-request' || !target.diffSafe || forcedUnsafe || !target.pullRequest ||
      !target.issueNumber || !isCommentCommandAllowed(target.command, 'pull-request')
    ) {
      saveReviewOutput(output, context);
      return output;
    }

    // Stage 5 execution matrix (P1 #1, scheme A): routed-ignore events
    // (non-owner synchronize, ordinary chatter, unsubscribed actions, bot
    // events) never reach AI, and review mode re-reads the sticky ledger
    // immediately before any git or OpenAI work as defense in depth against
    // concurrent runs. Quota is consumed by the claim-slot job before review:
    // when a run id is available the review proceeds only when its own
    // (sha,run_id,attempt) claim marker is present (an approved claim owns
    // its slot even at the limit); without its own claim — claim skipped,
    // failed, or exhausted — it returns the generic output with zero OpenAI
    // calls. Runs without a run id (local) fall back to the count check.
    // Phase 3 reopened (no-key local path): reopened with used>=1 returns
    // generic with zero OpenAI and zero git (only used==0 may review).
    // Like every routing or authorization denial, a blocked review is never
    // counted (counting happens only in the claim job).
    const reviewEvent = eventFrom(context);
    const reviewRoute = routeEvent(reviewEvent, env);
    if (reviewRoute.kind === 'ignore') {
      saveReviewOutput(output, context);
      return output;
    }
    const reviewRepository = repositoryParts(env, reviewEvent);
    if (!reviewRepository) {
      saveReviewOutput(output, context);
      return output;
    }
    {
      const quotaClient = apiClient(context, env.GITHUB_TOKEN ?? '');
      const reviewClaimKey = resolveCountClaimKey(env);
      if (reviewClaimKey) {
        const ledger = await readStickyLedger(
          quotaClient,
          reviewRepository.owner,
          reviewRepository.repo,
          target.issueNumber,
        );
        if (!ledger.ok) {
          saveReviewOutput(output, context);
          return output;
        }
        const reviewSha = target.pullRequest.head.sha.toLowerCase();
        if (!ledger.claims.has(`${reviewSha}:${reviewClaimKey}`)) {
          saveReviewOutput(output, context);
          return output;
        }
      } else {
        const quota = await readStickyReviewCount(
          quotaClient,
          reviewRepository.owner,
          reviewRepository.repo,
          target.issueNumber,
          target.pullRequest.head.sha,
        );
        if (!quota.ok || quota.used >= MAX_REVIEWS_PER_SHA) {
          saveReviewOutput(output, context);
          return output;
        }
        // Phase 3 reopened (no-key local path): reopened only supplements
        // an incomplete first review, with zero git/OpenAI when completed.
        {
          const reviewAction = typeof reviewEvent.action === 'string' ? reviewEvent.action : '';
          if (
            inferRouteEventName(reviewEvent, env) === 'pull_request_target' &&
            reviewAction === 'reopened' &&
            quota.used >= 1
          ) {
            saveReviewOutput(output, context);
            return output;
          }
        }
      }
    }

    const baseSha = target.pullRequest.base.sha;
    const headSha = target.pullRequest.head.sha;
    const runGit = context.runGit ?? defaultGit;
    try {
      fetchReviewCommitsForPR(context, target.pullRequest);
      const mergeBase = resolveMergeBase(runGit, baseSha, headSha);
      changedFiles = getChangedPaths(context, mergeBase, headSha);
      if (changedFiles.length > MAX_CHANGED_FILES) {
        throw new Error('PocketGuard: changed file list exceeds limit.');
      }
      changedFilesComplete = true;
      const reviewDiff = buildReviewDiff(context, mergeBase, headSha, changedFiles);
      const scan = DeterministicScanner.scan(changedFiles, reviewDiff.fullDiff);
      if (scan.hasBlockers) {
        output = {
          ...genericReviewOutput(),
          verdict: 'NEEDS_CHANGES',
          pullRequestNumber: target.pullRequest.number,
          baseSha,
          headSha,
          headRepository: target.pullRequest.head.repo?.full_name ?? '',
          coverage: reviewDiff.coverage,
          deterministicViolations: scan.violations,
          areaLabels: resolveAreaLabelsFromPaths(changedFiles),
          changedFiles,
          changedFilesComplete,
          suggestedLabels: [],
        };
      } else {
        const { orchestrateReview } = await import('./orchestrator');
        const restoreFetch = installOpenAIStub(env);
        try {
          const orchestrated = await orchestrateReview({
            changedFiles,
            diff: redactForModel(reviewDiff.diff),
            coverage: reviewDiff.coverage,
            deterministicViolations: scan.violations,
            env,
            allowedOrigins: parseAllowedOrigins(env),
          });
          const mergedSuggested = [...new Set(orchestrated.roles.flatMap((role) => role.suggestedLabels))];
          const runnerRoles: RunnerReviewOutput['roles'] = orchestrated.roles.map((role) => ({
            role: role.role as ReviewRoleName,
            modelUsed: role.modelUsed,
            verdict: role.verdict as RunnerVerdict,
            findings: role.findings.map((finding) => ({ ...finding })),
          }));
          let reviewVerdict = orchestrated.verdict as RunnerVerdict;
          // S5: illegal AI labels can never approve. Any unknown suggestion
          // downgrades an APPROVE to INCONCLUSIVE (rules-only path at
          // publish); NEEDS_CHANGES/INCONCLUSIVE stay as computed by the
          // existing deterministic-BLOCK-first semantics.
          if (hasUnknownAiLabels(mergedSuggested) && reviewVerdict === 'APPROVE') {
            console.warn('[PocketGuard] Discarded unknown AI labels; downgrading review verdict to INCONCLUSIVE.');
            reviewVerdict = 'INCONCLUSIVE';
          }
          output = {
            verdict: reviewVerdict,
            roles: runnerRoles,
            coverage: orchestrated.coverage,
            deterministicViolations: orchestrated.deterministicViolations,
            pullRequestNumber: target.pullRequest.number,
            baseSha,
            headSha,
            headRepository: target.pullRequest.head.repo?.full_name ?? '',
            areaLabels: resolveAreaLabelsFromPaths(changedFiles),
            changedFiles,
            changedFilesComplete,
            suggestedLabels: mergedSuggested,
          };
        } finally {
          restoreFetch();
        }
      }
    } catch {
      output = genericReviewOutput();
    }
  } catch {
    output = genericReviewOutput();
  }
  output = {
    ...output,
    areaLabels: resolveAreaLabelsFromPaths(changedFiles, changedFilesComplete),
    changedFiles,
    changedFilesComplete,
  };
  saveReviewOutput(output, context);
  return output;
}

function cleanForModel(value: unknown): string {
  if (typeof value !== 'string') return '';
  return redactForModel(value)
    .replace(/[\u0000-\u001f\u007f]/g, ' ');
}

function safeString(value: unknown, maxLength = 2000): string {
  return cleanForModel(value).slice(0, maxLength);
}

function validateReviewOutput(value: unknown): RunnerReviewOutput | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const raw = value as Record<string, unknown>;
  const verdicts = new Set(['APPROVE', 'NEEDS_CHANGES', 'INCONCLUSIVE']);
  if (
    Object.keys(raw).some((key) => ![
      'verdict', 'pullRequestNumber', 'baseSha', 'headSha', 'headRepository', 'roles', 'coverage',
      'deterministicViolations', 'areaLabels', 'changedFiles', 'changedFilesComplete', 'suggestedLabels',
    ].includes(key)) ||
    !Number.isSafeInteger(raw.pullRequestNumber) || Number(raw.pullRequestNumber) < 1 ||
    !safeSha(raw.baseSha) || !safeSha(raw.headSha) || !safeRepositoryName(raw.headRepository) ||
    !verdicts.has(String(raw.verdict)) || !Array.isArray(raw.roles) || raw.roles.length !== ROLE_NAMES.length ||
    !Array.isArray(raw.deterministicViolations) ||
    !Array.isArray(raw.areaLabels) || !raw.areaLabels.every((label) => typeof label === 'string') ||
    !Array.isArray(raw.changedFiles) || !raw.changedFiles.every((file) => typeof file === 'string') ||
    typeof raw.changedFilesComplete !== 'boolean'
  ) return undefined;
  const suggestedLabels = parseSuggestedLabelsField(raw.suggestedLabels);
  if (!suggestedLabels) return undefined;
  const rawCoverage = raw.coverage;
  if (!rawCoverage || typeof rawCoverage !== 'object' || Array.isArray(rawCoverage)) return undefined;
  const coverage = rawCoverage as Record<string, unknown>;
  if (
    Object.keys(coverage).some((key) => !['complete', 'omittedFiles', 'truncatedFiles', 'originalLength'].includes(key)) ||
    typeof coverage.complete !== 'boolean' || !Array.isArray(coverage.omittedFiles) ||
    !coverage.omittedFiles.every((file) => typeof file === 'string') || !Array.isArray(coverage.truncatedFiles) ||
    !coverage.truncatedFiles.every((file) => typeof file === 'string') ||
    !Number.isSafeInteger(coverage.originalLength) || Number(coverage.originalLength) < 0
  ) return undefined;
  if (
    coverage.complete === true &&
    ((coverage.omittedFiles as string[]).length > 0 || (coverage.truncatedFiles as string[]).length > 0)
  ) return undefined;

  const roles: RunnerReviewOutput['roles'] = [];
  for (const rawRole of raw.roles) {
    if (!rawRole || typeof rawRole !== 'object' || Array.isArray(rawRole)) return undefined;
    const role = rawRole as Record<string, unknown>;
    if (
      Object.keys(role).some((key) => !['role', 'modelUsed', 'verdict', 'findings'].includes(key)) ||
      !ROLE_NAMES.includes(role.role as ReviewRoleName) || typeof role.modelUsed !== 'string' || !role.modelUsed.trim() ||
      !verdicts.has(String(role.verdict)) || !Array.isArray(role.findings)
    ) return undefined;
    const findings: RunnerReviewOutput['roles'][number]['findings'] = [];
    for (const rawFinding of role.findings) {
      if (!rawFinding || typeof rawFinding !== 'object' || Array.isArray(rawFinding)) return undefined;
      const finding = rawFinding as Record<string, unknown>;
      if (Object.keys(finding).some((key) => !['severity', 'file', 'line', 'issue', 'suggestion'].includes(key))) return undefined;
      if (
        !['BLOCK', 'WARN', 'SUGGESTION'].includes(String(finding.severity)) ||
        typeof finding.issue !== 'string' || !finding.issue.trim() ||
        (finding.file !== undefined && typeof finding.file !== 'string') ||
        (finding.line !== undefined && (!Number.isInteger(finding.line) || Number(finding.line) < 1)) ||
        (finding.suggestion !== undefined && typeof finding.suggestion !== 'string')
      ) return undefined;
      findings.push({
        severity: finding.severity as 'BLOCK' | 'WARN' | 'SUGGESTION',
        ...(typeof finding.file === 'string' ? { file: safeString(finding.file) } : {}),
        ...(typeof finding.line === 'number' ? { line: finding.line } : {}),
        issue: safeString(finding.issue),
        ...(typeof finding.suggestion === 'string' ? { suggestion: safeString(finding.suggestion) } : {}),
      });
    }
    roles.push({
      role: role.role as ReviewRoleName,
      modelUsed: safeString(role.modelUsed, 200),
      verdict: role.verdict as RunnerVerdict,
      findings,
    });
  }

  const deterministicViolations: ScanViolation[] = [];
  for (const rawViolation of raw.deterministicViolations) {
    if (!rawViolation || typeof rawViolation !== 'object' || Array.isArray(rawViolation)) return undefined;
    const violation = rawViolation as Record<string, unknown>;
    if (
      Object.keys(violation).some((key) => !['ruleId', 'severity', 'category', 'message', 'file', 'line'].includes(key)) ||
      !['BLOCK', 'WARN'].includes(String(violation.severity)) ||
      !['security', 'reliability'].includes(String(violation.category)) ||
      typeof violation.ruleId !== 'string' || typeof violation.message !== 'string' ||
      (violation.file !== undefined && typeof violation.file !== 'string') ||
      (violation.line !== undefined && (!Number.isSafeInteger(violation.line) || Number(violation.line) < 1))
    ) return undefined;
    deterministicViolations.push({
      ruleId: safeString(violation.ruleId, 200),
      severity: violation.severity as 'BLOCK' | 'WARN',
      category: violation.category as 'security' | 'reliability',
      message: safeString(violation.message),
      ...(typeof violation.file === 'string' ? { file: safeString(violation.file) } : {}),
      ...(typeof violation.line === 'number' ? { line: violation.line } : {}),
    });
  }

  if (new Set(roles.map((role) => role.role)).size !== ROLE_NAMES.length) return undefined;
  const hasBlockingFinding = roles.some((role) =>
    role.verdict === 'NEEDS_CHANGES' || role.findings.some((finding) => finding.severity === 'BLOCK'));
  const hasDeterministicBlock = deterministicViolations.some((violation) => violation.severity === 'BLOCK');
  const hasCriticalOmission = (coverage.omittedFiles as string[]).some((file) => {
    const normalized = file.replace(/\\/g, '/').replace(/^\.\//, '').toLowerCase();
    if (normalized.endsWith('.md') || normalized.startsWith('docs/') || normalized.includes('/docs/')) return false;
    if (normalized.endsWith('androidmanifest.xml') || /permission/.test(normalized)) return true;
    if (/(auth|crypto|key|token|credential|biometric)/.test(normalized)) return true;
    if (
      normalized.endsWith('.gradle.kts') || normalized.endsWith('settings.gradle.kts') ||
      normalized.endsWith('libs.versions.toml') || normalized.includes('proguard') ||
      normalized.includes('network-security')
    ) return true;
    return normalized.startsWith('.github/workflows/') || normalized.includes('/.github/workflows/');
  });
  const unanimousApproval = roles.length === ROLE_NAMES.length && roles.every((role) => role.verdict === 'APPROVE');
  const completeFileCoverage = raw.changedFilesComplete === true && coverage.complete === true &&
    (coverage.omittedFiles as string[]).length === 0 && (coverage.truncatedFiles as string[]).length === 0;
  const calculatedVerdict: RunnerVerdict = hasBlockingFinding || hasDeterministicBlock
    ? 'NEEDS_CHANGES'
    : unanimousApproval && completeFileCoverage && !hasCriticalOmission
      ? 'APPROVE'
      : 'INCONCLUSIVE';
  if (raw.verdict !== calculatedVerdict) return undefined;

  return {
    verdict: calculatedVerdict,
    pullRequestNumber: Number(raw.pullRequestNumber),
    baseSha: raw.baseSha,
    headSha: raw.headSha,
    headRepository: raw.headRepository,
    roles,
    coverage: {
      complete: coverage.complete,
      omittedFiles: coverage.omittedFiles.filter((file): file is string => typeof file === 'string').map((file) => safeString(file)),
      truncatedFiles: coverage.truncatedFiles.filter((file): file is string => typeof file === 'string').map((file) => safeString(file)),
      originalLength: Number.isSafeInteger(coverage.originalLength) ? Number(coverage.originalLength) : 0,
    },
    deterministicViolations,
    areaLabels: sanitizeLabels(Array.isArray(raw.areaLabels)
      ? raw.areaLabels.filter((label): label is string => typeof label === 'string')
      : []),
    changedFiles: Array.isArray(raw.changedFiles)
      ? raw.changedFiles.filter((file): file is string => typeof file === 'string').map((file) => safeString(file))
      : [],
    changedFilesComplete: raw.changedFilesComplete === true,
    suggestedLabels,
  };
}

function escapeMarkdown(value: string): string {
  return safeString(value).replace(/[\\`*_{}\[\]()#+\-.!|<>]/g, '\\$&');
}

// Sticky bound helper (Phase A #4/T8): truncate with an explicit omission note
// so 100+ findings/summaries/chunk lines stay bounded and auditable.
export function truncateStickyText(value: string, maxLength: number): string {
  const src = typeof value === 'string' ? value : '';
  if (src.length <= maxLength) return src;
  return `${src.slice(0, maxLength)}…（已省略${src.length - maxLength}字）`;
}

export function reviewComment(output: RunnerReviewOutput, appliedLabels: string[] = [], reportLine?: string): string {
  const updatedAt = new Date().toISOString();
  const lines = [
    REVIEW_MARKER,
    '',
    '## PocketGuard 審查',
    '',
    `**判定：${output.verdict}**`,
    `審查的 head SHA：\`${output.headSha}\``,
    `審查的 base SHA：\`${output.baseSha}\``,
    `標籤決策：${appliedLabels.length > 0 ? appliedLabels.map((label) => escapeMarkdown(label)).join('、') : '無'}`,
    `更新時間：${updatedAt}`,
    reportLine ?? formatReportLine(process.env, false),
    coverageSummary(output.coverage),
    `省略檔案：${output.coverage.omittedFiles.length > 0
      ? output.coverage.omittedFiles.map((file) => escapeMarkdown(file)).join('、')
      : '無'}`,
    `截斷檔案：${output.coverage.truncatedFiles.length > 0
      ? output.coverage.truncatedFiles.map((file) => escapeMarkdown(file)).join('、')
      : '無'}`,
    '',
  ];
  for (const role of output.roles) {
    lines.push(`### ${role.role} — ${role.verdict}`);
    if (role.findings.length === 0) {
      lines.push('- 無符合回報條件的具體發現。');
    }
    // Bounded findings per role with omission note (Phase A #4/T8).
    const shown = role.findings.slice(0, MAX_STICKY_FINDINGS_PER_ROLE);
    for (const finding of shown) {
      const location = finding.file
        ? ` (${escapeMarkdown(finding.file)}${finding.line ? `:${finding.line}` : ''})`
        : '';
      lines.push(`- **${finding.severity}**${location}: ${escapeMarkdown(finding.issue)}`);
      if (finding.suggestion) lines.push(`  - 建議：${escapeMarkdown(finding.suggestion)}`);
    }
    if (role.findings.length > shown.length) {
      lines.push(`- …（共${role.findings.length}項，省略${role.findings.length - shown.length}項）`);
    }
    lines.push('');
  }
  // Bounded deterministic violations with omission note.
  const shownViolations = output.deterministicViolations.slice(0, MAX_STICKY_FINDINGS_PER_ROLE);
  for (const violation of shownViolations) {
    lines.push(`- **${violation.severity} ${escapeMarkdown(violation.ruleId)}**: ${escapeMarkdown(violation.message)}`);
  }
  if (output.deterministicViolations.length > shownViolations.length) {
    lines.push(`- …（共${output.deterministicViolations.length}項，省略${output.deterministicViolations.length - shownViolations.length}項）`);
  }
  return `${lines.join('\n')}\n`;
}

function inconclusiveComment(reason: string, reportLine?: string): string {
  return [
    REVIEW_MARKER,
    '',
    '## PocketGuard 審查',
    '',
    '**判定：INCONCLUSIVE**',
    `**審查結果不可用：${safeString(reason)}**`,
    `更新時間：${new Date().toISOString()}`,
    reportLine ?? formatReportLine(process.env, false),
    SAFE_MESSAGE,
    '',
  ].join('\n');
}

// S5 issue sticky: one comment per issue, edited in place (never a second
// comment). Carries verdict, summary, label decision, updated time, and the
// revision fingerprint over title/body/comments so readers can tell whether
// the published result matches the current issue content. Chunk line carries
// per-segment verdicts with boundaries and omission notes; summaries are
// bounded with omission notes (Phase A #4/T7/T8).
export function issueReviewComment(output: RunnerIssueOutput, appliedLabels: string[], reportLine?: string): string {
  const updatedAt = new Date().toISOString();
  let chunkLine: string;
  const segmentDetailLines: string[] = [];
  if (output.chunks && output.chunks.length > 0) {
    const total = output.chunkCount ?? output.chunks.length;
    const shown = output.chunks.slice(0, MAX_STICKY_CHUNK_LINES);
    const shownText = shown.map((c) => `#${c.index}:${c.start}-${c.end}:${c.verdict}`).join('、');
    const omitted = total > shown.length ? `，…（共${total}段，省略${total - shown.length}段）` : '';
    chunkLine = `分段：${total} 段（${shownText}${omitted}，完整：${output.chunkCoverageComplete === true ? '是' : '否'}）`;
    for (const seg of shown) {
      segmentDetailLines.push(`- 段 #${seg.index} ${seg.verdict}（${seg.start}-${seg.end}）：${truncateStickyText(seg.summary || '', 200) || '（無摘要）'}`);
    }
    if (total > shown.length) {
      segmentDetailLines.push(`- …（共${total}段，省略${total - shown.length}段）`);
    }
  } else {
    chunkLine = '分段：單段（完整）';
  }
  const boundedSummary = truncateStickyText(output.summary || '', MAX_STICKY_SUMMARY_LENGTH);
  return [
    REVIEW_MARKER,
    '',
    '## PocketGuard 議題審查',
    '',
    `**判定：${output.verdict}**`,
    `議題：#${output.issueNumber} ${escapeMarkdown(output.title)}`,
    `摘要：${escapeMarkdown(boundedSummary) || '（無摘要）'}`,
    ...(boundedSummary.length < (output.summary || '').length ? ['（摘要已省略部分內容，詳見報告 artifact）'] : []),
    `標籤決策：${appliedLabels.length > 0 ? appliedLabels.map((label) => escapeMarkdown(label)).join('、') : '無'}`,
    `修訂指紋：\`${output.fingerprint}\``,
    chunkLine,
    ...segmentDetailLines,
    `更新時間：${updatedAt}`,
    reportLine ?? formatReportLine(process.env, false),
    '',
  ].join('\n');
}

function issueInconclusiveComment(issueNumber: number, title: string, reason: string, fingerprint: string, reportLine?: string): string {
  return [
    REVIEW_MARKER,
    '',
    '## PocketGuard 議題審查',
    '',
    '**判定：INCONCLUSIVE**',
    `議題：#${issueNumber} ${escapeMarkdown(title)}`,
    `**審查結果不可用：${safeString(reason)}**`,
    `修訂指紋：\`${safeString(fingerprint, 100)}\``,
    `更新時間：${new Date().toISOString()}`,
    reportLine ?? formatReportLine(process.env, false),
    SAFE_MESSAGE,
    '',
  ].join('\n');
}

function eventCommentAllowed(target: ReviewTarget, eventName: string): boolean {
  if (eventName === 'pull_request_target') return target.target === 'pull-request';
  return target.target === 'pull-request' && isCommentCommandAllowed(target.command, 'pull-request');
}

function configuredTagLabels(env: NodeJS.ProcessEnv): string[] {
  try {
    const parsed: unknown = JSON.parse(env.POCKETGUARD_TAG_LABELS ?? '[]');
    return Array.isArray(parsed)
      ? sanitizeLabels(parsed.filter((label): label is string => typeof label === 'string'))
      : [];
  } catch {
    return [];
  }
}

async function publishStickyComment(
  client: RunnerGitHubClient,
  repository: { owner: string; repo: string },
  issueNumber: number,
  body: string,
  bodyBeforeWrite?: () => Promise<string>,
): Promise<void> {
  let botLogin: string | undefined;
  try {
    const identity = await client.rest.users.getAuthenticated();
    if (typeof identity.data.login === 'string' && identity.data.login.trim()) {
      botLogin = identity.data.login;
    }
  } catch {
    // The marker plus GitHub's bot author metadata provides a safe fallback.
  }

  try {
    let existing: GithubComment | undefined;
    for (let page = 1; ; page += 1) {
      const comments = await client.rest.issues.listComments({
        owner: repository.owner,
        repo: repository.repo,
        issue_number: issueNumber,
        per_page: 100,
        page,
      });
      existing = comments.data.find((comment) =>
        (botLogin
          ? comment.user?.login === botLogin
          : comment.user?.login === 'github-actions[bot]' || comment.user?.type === 'Bot') &&
        typeof comment.body === 'string' && comment.body.includes(REVIEW_MARKER));
      if (existing || comments.data.length < 100) break;
    }
    // Revalidate only after locating the sticky comment and immediately before
    // the write. GitHub offers no compare-and-swap, so this narrows but cannot
    // eliminate the race with a concurrent PR update.
    const currentBody = bodyBeforeWrite ? await bodyBeforeWrite() : body;
    if (existing) {
      await client.rest.issues.updateComment({
        owner: repository.owner,
        repo: repository.repo,
        comment_id: existing.id,
        body: currentBody,
      });
    } else {
      await client.rest.issues.createComment({
        owner: repository.owner,
        repo: repository.repo,
        issue_number: issueNumber,
        body: currentBody,
      });
    }
  } catch {
    // Do not leak API details, and do not let labels advertise an unpublished result.
    throw new Error('PocketGuard: failed to publish review comment.');
  }
}

function sameReviewIdentity(output: RunnerReviewOutput, pullRequest: PullRequestData): boolean {
  return output.pullRequestNumber === pullRequest.number &&
    typeof output.baseSha === 'string' && output.baseSha.toLowerCase() === pullRequest.base.sha.toLowerCase() &&
    typeof output.headSha === 'string' && output.headSha.toLowerCase() === pullRequest.head.sha.toLowerCase() &&
    typeof output.headRepository === 'string' && sameRepository(output.headRepository, pullRequest.head.repo?.full_name ?? '');
}

async function currentReviewProblem(
  client: RunnerGitHubClient,
  repository: { owner: string; repo: string },
  issueNumber: number,
  output: RunnerReviewOutput,
): Promise<string | undefined> {
  try {
    const response = await client.rest.pulls.get({
      owner: repository.owner,
      repo: repository.repo,
      pull_number: issueNumber,
    });
    if (!validPullRequestData(response?.data) || response.data.number !== issueNumber) {
      return 'the current pull request state is unavailable; review freshness could not be verified.';
    }
    if (!sameReviewIdentity(output, response.data)) {
      return 'the pull request changed after review; the result is stale.';
    }
    return undefined;
  } catch {
    return 'the current pull request state is unavailable; review freshness could not be verified.';
  }
}

async function reconcileNeedsDecisionOnly(
  client: RunnerGitHubClient,
  repository: { owner: string; repo: string },
  issueNumber: number,
): Promise<void> {
  // P1 #3 provenance: this fallback only desires status:needs-decision (never
  // a transitionable peer), so no transition can occur; provenance is passed
  // explicitly as unknown (fail-closed) for contract uniformity.
  const reconciliation = await reconcileBotLabelsSafely({
    client,
    owner: repository.owner,
    repo: repository.repo,
    issueNumber,
    desiredLabels: ['status:needs-decision'],
    scope: { managedExactLabels: ['status:needs-decision'] } satisfies ReconcileScope,
    coverageComplete: false,
    botWrittenLabels: undefined,
  });
  if (
    !reconciliation.added.includes('status:needs-decision') &&
    !reconciliation.skipped.includes('status:needs-decision')
  ) {
    throw new Error('PocketGuard: failed to ensure maintainer decision label.');
  }
}

export async function runPublishMode(context: RunnerContext = {}): Promise<void> {
  const env = context.env ?? process.env;
  let event: GithubEvent;
  try {
    event = eventFrom(context);
  } catch {
    return;
  }
  const repository = repositoryParts(env, event);
  if (!repository) return;
  const token = env.GITHUB_TOKEN ?? '';
  const client = apiClient(context, token);
  if (!client) return;

  const target = await inspectTarget(context);
  const eventName = env.GITHUB_EVENT_NAME ?? '';
  if (!target.issueNumber) return;

  // S4 execution matrix: routed-ignore events (non-owner synchronize, ordinary
  // chatter, unsubscribed actions, bot events) publish nothing — no sticky
  // write, no label changes. S5 adds an explicit bot guard so a direct
  // publish call with a bot comment also returns without writes.
  const publishRoute = routeEvent(event, env);
  if (publishRoute.kind === 'ignore') return;
  if (isBotEventActor(event)) return;

  // S5 issue sticky: exactly one comment per issue (REVIEW_MARKER shared with
  // PRs), edited in place. Covers issues opened/edited/reopened plus human
  // issue_comment on issues (no auth gate by owner decision, bot excluded
  // above). Publish verifies the artifact fingerprint against the current
  // title/body/comments; mismatch falls back to INCONCLUSIVE on the same
  // comment without creating a second one.
  if (target.target === 'issue' && target.issueNumber) {
    const issueNumber = target.issueNumber;
    // P2 #5: re-read the live issue title/body via issues.get (plus a fresh
    // comment re-fetch inside buildIssueContext) and compare the fingerprint
    // before any sticky write. Review and publish never rely on the webhook
    // snapshot alone. A present-but-failing/unverifiable fresh read falls
    // back to INCONCLUSIVE; a client without `get` uses the webhook values
    // for backward compatibility.
    const webhookIssueTitle = target.title;
    const webhookIssueBody = typeof event.issue?.body === 'string' ? event.issue.body : '';
    let issueTitle = webhookIssueTitle;
    let issueBody = webhookIssueBody;
    let publishIssueMeta: { updatedAt?: string | null; id?: number | null } | undefined;
    let freshIssueReadFailed = false;
    if (typeof client.rest.issues.get === 'function') {
      try {
        const fresh = await fetchFreshIssueFields(client, repository.owner, repository.repo, issueNumber);
        if (fresh) {
          issueTitle = fresh.title;
          issueBody = fresh.body;
          if (fresh.updatedAt !== undefined || fresh.id !== undefined) {
            publishIssueMeta = {
              ...(fresh.updatedAt !== undefined ? { updatedAt: fresh.updatedAt } : {}),
              ...(fresh.id !== undefined ? { id: fresh.id } : {}),
            };
          }
        } else {
          freshIssueReadFailed = true;
        }
      } catch {
        freshIssueReadFailed = true;
      }
    }
    const rulesTitle = sanitizeLabels([
      ...configuredTagLabels(env),
      ...resolveLabelsFromTitle(issueTitle),
    ]);
    // P1 #2: the fresh comment re-read carries its own completeness flag.
    // A throw here is fail-closed (unverifiable) rather than a crash.
    let currentFingerprint = '0'.repeat(64);
    let currentCommentsComplete = false;
    let currentFetchComplete = false;
    let currentFullBody = '';
    let currentFullComments: string[] = [];
    let currentFullTitle = '';
    try {
      const builtCurrent = await buildIssueContext(context, repository, issueNumber, issueTitle, issueBody, publishIssueMeta);
      // Phase B v2: compare full RAW-content fingerprints (pre-cut, no
      // masking) on both sides so a tail edit past any cap -- or a
      // same-mask secret swap -- is detected as stale. Coverage below still
      // uses the REDACTED full copies (chunks are redacted).
      currentFingerprint = builtCurrent.fullFingerprint;
      currentCommentsComplete = builtCurrent.commentsComplete;
      currentFetchComplete = builtCurrent.fetchComplete;
      currentFullBody = builtCurrent.fullBody;
      currentFullComments = builtCurrent.fullComments;
      currentFullTitle = builtCurrent.fullTitle;
    } catch {
      currentFingerprint = '0'.repeat(64);
      currentCommentsComplete = false;
      currentFetchComplete = false;
    }

    let validated: RunnerIssueOutput | undefined;
    let issueFallbackReason: string | undefined;
    const reviewJobResult = env.POCKETGUARD_REVIEW_JOB_RESULT ?? 'unavailable';
    if (freshIssueReadFailed) {
      issueFallbackReason = 'the current issue state could not be fetched from GitHub; review freshness could not be verified.';
    } else if (reviewJobResult !== 'success') {
      issueFallbackReason = `the review job did not complete successfully (result: ${safeString(reviewJobResult, 100)}).`;
    } else {
      let artifactText: string;
      try {
        artifactText = fs.readFileSync(env.POCKETGUARD_OUTPUT ?? 'review-output.json', 'utf8');
      } catch {
        issueFallbackReason = 'the review output artifact is missing or unreadable.';
        artifactText = '';
      }
      if (!issueFallbackReason) {
        let artifactValue: unknown;
        try {
          artifactValue = JSON.parse(artifactText) as unknown;
        } catch {
          issueFallbackReason = 'the review output JSON is malformed.';
        }
        if (!issueFallbackReason) {
          validated = validateIssueOutput(artifactValue);
          if (!validated) issueFallbackReason = 'the review output schema or contents are invalid.';
          else if (validated.issueNumber !== issueNumber) issueFallbackReason = 'the review output is stale: its issue number no longer matches GitHub.';
          else if (validated.commentsComplete !== true) issueFallbackReason = 'the review output is unverifiable: the issue comments were not fully fetched during review; review freshness could not be verified.';
          // Phase 2 (H): chunked artifacts re-verify via transport completeness
          // (fetchComplete) plus full fingerprint, not the truncated
          // commentsComplete flag (super-long bodies are always truncated in
          // the single-turn view but fully covered via chunks).
          else if (validated.chunks !== undefined
            ? currentFetchComplete !== true
            : currentCommentsComplete !== true) issueFallbackReason = 'the current issue comments could not be fully fetched from GitHub; review freshness could not be verified.';
          else if (validated.fingerprint === '0'.repeat(64) || currentFingerprint === '0'.repeat(64)) issueFallbackReason = 'the review output is unverifiable: review freshness could not be verified.';
          else if (validated.fingerprint !== currentFingerprint) issueFallbackReason = 'the review output is stale: the issue content changed after review.';
          // Phase 2 (H): chunked APPROVE requires full chunk coverage; any
          // chunked artifact without complete coverage falls back (same
          // sticky, never a wrong APPROVE). Legacy single-turn artifacts omit
          // chunk fields and skip this gate.
          else if (validated.chunks !== undefined && validated.chunkCoverageComplete !== true) issueFallbackReason = 'the review output is unverifiable: issue chunks were not fully covered; review freshness could not be verified.';
          else if (validated.chunks !== undefined && validated.verdict === 'APPROVE' && validated.chunkCount !== validated.chunks.length) issueFallbackReason = 'the review output is unverifiable: issue chunk count mismatch.';
          // Phase A #3: source coverage re-verification for chunked artifacts:
          // summed coveredLength must equal the current full body+comments
          // content (no omission/duplication vs the live text).
          else if (validated.chunks !== undefined && !verifyIssueChunkCoverage(validated.chunks, { title: currentFullTitle, body: currentFullBody, comments: currentFullComments })) issueFallbackReason = 'the review output is unverifiable: issue chunk coverage does not match the current issue content.';
          else if (hasUnknownAiLabels(validated.suggestedLabels)) issueFallbackReason = 'the AI label suggestions contain unknown labels; discarded.';
          // Phase 2 (H): full fingerprint re-verification for chunked APPROVE
          // already covered by the equality above; an APPROVE with chunks must
          // also carry complete per-segment schema (validated above).
        }
      }
    }

    if (issueFallbackReason || !validated) {
      const fallbackFingerprint = validated?.fingerprint && /^[0-9a-f]{64}$/.test(validated.fingerprint)
        ? validated.fingerprint
        : currentFingerprint;
      // Phase 2 (H) reports: failure shows unavailable + INCONCLUSIVE with no
      // fake link (single sticky ID updated in place via buildStampedBody path
      // inside publishStickyComment).
      const fallbackReportLine = formatReportLine(env, false);
      await publishStickyComment(
        client,
        repository,
        issueNumber,
        issueInconclusiveComment(issueNumber, issueTitle, issueFallbackReason ?? 'a valid review result was unavailable.', fallbackFingerprint, fallbackReportLine),
      );
      const fallbackDesired = sanitizeLabels([...rulesTitle, 'status:needs-decision']);
      try {
        const reconciliation = await reconcileBotLabelsSafely({
          client,
          owner: repository.owner,
          repo: repository.repo,
          issueNumber,
          desiredLabels: fallbackDesired,
          scope: DEFAULT_PR_RECONCILE_SCOPE,
          coverageComplete: false,
          // P1 #3 provenance: issue fallback carries no verified bot-written
          // set (no sticky marker read on this path); unproven transitions
          // are warn-only preserved, never deleted.
          botWrittenLabels: undefined,
        });
        const reconciled = new Set([...reconciliation.added, ...reconciliation.skipped]);
        if (reconciliation.failedToList || fallbackDesired.some((label) => !reconciled.has(label)) || (reconciliation.failedRemovals?.length ?? 0) > 0) {
          throw new Error('label reconcile incomplete');
        }
      } catch {
        throw new Error('PocketGuard: failed to apply issue labels.');
      }
      return;
    }

    const { merged, hadUnknown } = mergeRulesWithAiSuggestions(rulesTitle, validated.suggestedLabels);
    if (hadUnknown) {
      await publishStickyComment(
        client,
        repository,
        issueNumber,
        issueInconclusiveComment(issueNumber, issueTitle, 'the AI label suggestions contain unknown labels; discarded.', currentFingerprint, formatReportLine(env, false)),
      );
      const fallbackDesired = sanitizeLabels([...rulesTitle, 'status:needs-decision']);
      try {
        await reconcileBotLabelsSafely({
          client,
          owner: repository.owner,
          repo: repository.repo,
          issueNumber,
          desiredLabels: fallbackDesired,
          scope: DEFAULT_PR_RECONCILE_SCOPE,
          coverageComplete: false,
          // P1 #3 provenance: unknown-AI fallback carries no verified
          // bot-written set; unproven transitions are warn-only preserved.
          botWrittenLabels: undefined,
        });
      } catch {
        throw new Error('PocketGuard: failed to apply issue labels.');
      }
      return;
    }
    // Defense in depth: review mode already downgrades APPROVE with unknown
    // labels, but a crafted artifact could still carry APPROVE with valid
    // labels yet stale tags; the merged desired below always enforces the
    // deterministic needs-decision for non-APPROVE.
    const desiredIssueLabels = sanitizeLabels([
      ...merged,
      ...(validated.verdict !== 'APPROVE' ? ['status:needs-decision'] : []),
    ]);
    const stickyOutput: RunnerIssueOutput = { ...validated, tags: desiredIssueLabels, fingerprint: validated.fingerprint };
    // Phase D (P1 #4) double-gate: a validated output still requires
    // content-validated reports (output↔report verdict/fingerprint/time).
    // Without them the success path must not publish APPROVE with an
    // unavailable line; fall back to the same sticky INCONCLUSIVE with no
    // fake link plus the maintainer-decision label.
    const successReportsAvailable = areReviewReportsAvailable(env);
    if (!successReportsAvailable) {
      const unavailableLine = formatReportLine(env, false);
      await publishStickyComment(
        client,
        repository,
        issueNumber,
        issueInconclusiveComment(issueNumber, issueTitle, 'the review reports are missing or inconsistent; review freshness could not be verified.', currentFingerprint, unavailableLine),
      );
      const reportsMissingDesired = sanitizeLabels([...rulesTitle, 'status:needs-decision']);
      try {
        const reconciliation = await reconcileBotLabelsSafely({
          client,
          owner: repository.owner,
          repo: repository.repo,
          issueNumber,
          desiredLabels: reportsMissingDesired,
          scope: DEFAULT_PR_RECONCILE_SCOPE,
          coverageComplete: false,
          botWrittenLabels: undefined,
        });
        const reconciled = new Set([...reconciliation.added, ...reconciliation.skipped]);
        if (reconciliation.failedToList || reportsMissingDesired.some((label) => !reconciled.has(label)) || (reconciliation.failedRemovals?.length ?? 0) > 0) {
          throw new Error('label reconcile incomplete');
        }
      } catch {
        throw new Error('PocketGuard: failed to apply issue labels.');
      }
      return;
    }
    // Phase 2 (H): success writes back the artifact/run link (retention 30d);
    // the gate above guarantees reports are content-valid here, so a missing
    // run identity is the only unavailable case (still no fake link). Single
    // comment ID is updated in place (publishStickyComment).
    const successReportLine = formatReportLine(env, getReportRunUrl(env) !== undefined);
    await publishStickyComment(
      client,
      repository,
      issueNumber,
      issueReviewComment(stickyOutput, desiredIssueLabels, successReportLine),
    );
    try {
      const reconciliation = await reconcileBotLabelsSafely({
        client,
        owner: repository.owner,
        repo: repository.repo,
        issueNumber,
        desiredLabels: desiredIssueLabels,
        scope: DEFAULT_PR_RECONCILE_SCOPE,
        coverageComplete: true,
        // P1 #3 provenance: issue success carries no sticky-marker read yet;
        // the timeline fallback inside reconcile (actor == botLogin) is the
        // provenance source. Unknown/failed provenance retains transitions.
        botWrittenLabels: undefined,
      });
      const reconciled = new Set([...reconciliation.added, ...reconciliation.skipped]);
      if (reconciliation.failedToList || desiredIssueLabels.some((label) => !reconciled.has(label)) || (reconciliation.failedRemovals?.length ?? 0) > 0) {
        throw new Error('label reconcile incomplete');
      }
    } catch {
      throw new Error('PocketGuard: failed to apply issue labels.');
    }
    return;
  }

  if (!eventCommentAllowed(target, eventName)) return;
  // Defense in depth for the workflow publish gate: an issue_comment without
  // an explicit authorized verdict publishes nothing — no artifact read, no
  // sticky write, no label changes — so an unauthorized /review can never
  // touch a prior approval even if the job condition is bypassed in tests.
  if (eventName === 'issue_comment' && target.authorized !== true) return;

  let fallbackReason: string | undefined;
  let freshPullRequest: PullRequestData | undefined;
  try {
    const response = await client.rest.pulls.get({
      owner: repository.owner,
      repo: repository.repo,
      pull_number: target.issueNumber,
    });
    if (!validPullRequestData(response?.data) || response.data.number !== target.issueNumber) {
      fallbackReason = 'GitHub returned an invalid current pull request identity; review freshness could not be verified.';
    } else {
      freshPullRequest = response.data;
    }
  } catch {
    fallbackReason = 'the current pull request state could not be fetched from GitHub.';
  }

  // Stage 5 quota (claim-slot counts, publish reconciles): an exhausted
  // budget — or an unreadable counter (fail-closed) — publishes nothing — the
  // sticky comment and labels keep the previous review untouched. This early
  // check covers the fresh head SHA so quota-exhausted/unknown runs never
  // touch the sticky. Counting happens only in the claim-slot job before AI
  // (including its hold for runs whose later transport/artifact/publish
  // fails, which is never refunded); every publish write below preserves the
  // ledger and claim set with no new marker. A run that owns its
  // (freshSHA,run_id/run_attempt) claim always reconciles (even at the
  // limit — it owns the slot); without its own claim an exhausted budget — or
  // an unreadable counter (fail-closed) — publishes nothing. Only claimed
  // reviews consume: routing/authorization denials return above with zero
  // writes, and quota-exhausted without a claim returns here.
  // Phase 3 reopened: a reopened run without its own claim and with
  // used>=1 publishes nothing (no fallback noise); only the incomplete
  // first review (used==0) may publish its fallback.
  if (freshPullRequest && safeSha(freshPullRequest.head.sha)) {
    const freshLower = freshPullRequest.head.sha.toLowerCase();
    const publishClaimKey = resolveCountClaimKey(env);
    const publishAction = typeof event.action === 'string' ? event.action : '';
    const isReopenedPublish = inferRouteEventName(event, env) === 'pull_request_target' && publishAction === 'reopened';
    if (publishClaimKey) {
      const ledger = await readStickyLedger(client, repository.owner, repository.repo, target.issueNumber);
      if (!ledger.ok) return;
      const used = ledger.ledger.get(freshLower) ?? 0;
      if (!ledger.claims.has(`${freshLower}:${publishClaimKey}`) && used >= MAX_REVIEWS_PER_SHA) return;
      if (!ledger.claims.has(`${freshLower}:${publishClaimKey}`) && isReopenedPublish && used >= 1) return;
    } else {
      const quota = await readStickyReviewCount(
        client,
        repository.owner,
        repository.repo,
        target.issueNumber,
        freshPullRequest.head.sha,
      );
      if (!quota.ok) return;
      if (quota.used >= MAX_REVIEWS_PER_SHA) return;
      if (isReopenedPublish && quota.used >= 1) return;
    }
  }

  const outputPath = env.POCKETGUARD_OUTPUT ?? 'review-output.json';
  let output: RunnerReviewOutput | undefined;
  let staleReason: string | undefined;
  if (!fallbackReason) {
    const reviewJobResult = env.POCKETGUARD_REVIEW_JOB_RESULT ?? 'unavailable';
    if (reviewJobResult !== 'success') {
      fallbackReason = `the review job did not complete successfully (result: ${safeString(reviewJobResult, 100)}).`;
    } else {
      let artifactText: string;
      try {
        artifactText = fs.readFileSync(outputPath, 'utf8');
      } catch {
        fallbackReason = 'the review output artifact is missing or unreadable.';
        artifactText = '';
      }
      if (!fallbackReason) {
        let artifactValue: unknown;
        try {
          artifactValue = JSON.parse(artifactText) as unknown;
        } catch {
          fallbackReason = 'the review output JSON is malformed.';
        }
        if (!fallbackReason) {
          output = validateReviewOutput(artifactValue);
          if (!output) fallbackReason = 'the review output schema or contents are invalid.';
        }
      }
      if (!fallbackReason && output && freshPullRequest && !sameReviewIdentity(output, freshPullRequest)) {
        staleReason = 'the review output is stale: its PR number, base SHA, head SHA, or head repository no longer matches GitHub.';
      }
    }
  }

  // Stage 5 attribution (P1 #1, scheme A): counting happens only in the
  // claim-slot job before AI, never here. Every sticky write below preserves
  // the ledger and claim set unchanged (no new marker, no increment) and only
  // reconciles content: a stale artifact still publishes its INCONCLUSIVE
  // fallback toward the preserved ledger, and without verifiable output the
  // fallback likewise preserves. Started-but-failed runs keep the claim's
  // count (never refunded); unstarted runs (no claim) keep zero.
  const buildPreservedBody = async (freshContent: string): Promise<string> => {
    const fresh = await readStickyLedger(client, repository.owner, repository.repo, target.issueNumber!);
    if (!fresh.ok) throw new Error('PocketGuard: failed to publish review comment.');
    return buildStampedBody(freshContent, fresh.ledger, fresh.claims, undefined, undefined, undefined);
  };

  // Unverifiable output (missing artifact, job != success, malformed,
  // invalid schema, or fresh fetch failure without output): preserved
  // fallback that keeps the claim's ledger. Fail-closed on unreadable ledger.
  // Phase 2 (H): failure shows report unavailable + INCONCLUSIVE, no fake link.
  if (fallbackReason || !output || !(output && safeSha(output.headSha))) {
    const reasonText = fallbackReason ?? 'a valid review result was unavailable.';
    const baseline = await readStickyLedger(client, repository.owner, repository.repo, target.issueNumber);
    if (!baseline.ok) return;
    const unavailableLine = formatReportLine(env, false);
    await publishStickyComment(
      client,
      repository,
      target.issueNumber,
      buildStampedBody(inconclusiveComment(reasonText, unavailableLine), baseline.ledger, baseline.claims, undefined, undefined, undefined),
      async () => buildPreservedBody(inconclusiveComment(reasonText, unavailableLine)),
    );
    await reconcileNeedsDecisionOnly(client, repository, target.issueNumber);
    return;
  }

  // From here output is verified and fallbackReason is undefined. Re-read
  // the ledger after artifact validation and before any write; every write
  // below preserves the ledger and claim set (reconciliation only) so
  // retries never +1+1 and other SHAs are preserved.
  const baselineReconciled = await readStickyLedger(client, repository.owner, repository.repo, target.issueNumber);
  if (!baselineReconciled.ok) return;
  const buildReconciledBody = async (freshContent: string): Promise<string> => {
    const fresh = await readStickyLedger(client, repository.owner, repository.repo, target.issueNumber!);
    if (!fresh.ok) throw new Error('PocketGuard: failed to publish review comment.');
    return buildStampedBody(freshContent, fresh.ledger, fresh.claims, undefined, undefined, undefined);
  };
  const reconciledInitialBody = (freshContent: string): string =>
    buildStampedBody(freshContent, baselineReconciled.ledger, baselineReconciled.claims, undefined, undefined, undefined);

  // Stale but verifiable output: preserved INCONCLUSIVE fallback that keeps
  // the claim's ledger untouched (never polluting another SHA).
  if (staleReason) {
    const staleLine = formatReportLine(env, false);
    await publishStickyComment(
      client,
      repository,
      target.issueNumber,
      reconciledInitialBody(inconclusiveComment(staleReason, staleLine)),
      async () => buildReconciledBody(inconclusiveComment(staleReason, staleLine)),
    );
    await reconcileNeedsDecisionOnly(client, repository, target.issueNumber);
    return;
  }

  // S5 AI label gate + P2 #4 convergence: raw suggestions are allowlisted at
  // publish. Unknown entries are discarded with a log and force INCONCLUSIVE
  // on the same sticky (never a wrong APPROVE, never writing illegal labels).
  // PR convergence: area:*/security/performance/status:needs-decision
  // (+ type:tracking + semantic bug/enhancement/documentation) may be written
  // from PR AI; priority:*/gate:* (and verified statuses) are
  // issue-triage-only, so a PR suggestion carrying them is likewise discarded
  // and forces INCONCLUSIVE. Manual labels outside the bot scope are always
  // preserved by reconciliation (proven bot-owned P1->P2 / bug->enhancement
  // transitions excepted — P1 #3: only when provenance proves bot authorship
  // via the sticky marker or timeline actor == botLogin; otherwise warn-only
  // retain). The ledger stays as the claim left it
  // (reconciliation only).
  const aiRaw = Array.isArray(output.suggestedLabels) ? output.suggestedLabels : [];
  const { hadUnknown } = mergeRulesWithAiSuggestions([], aiRaw);
  const prFiltered = sanitizePrAiSuggestions(aiRaw);
  if (hadUnknown || prFiltered.discardedCount > 0) {
    const discardReason = hadUnknown
      ? 'the AI label suggestions contain unknown labels; discarded.'
      : 'the AI label suggestions contain issue-triage-only labels; discarded for PR publish.';
    // Never log raw label values (model-controlled); record only the count.
    console.warn(`[PocketGuard] Discarded ${prFiltered.discardedCount} PR AI label(s) outside the convergence allowlist.`);
    const discardLine = formatReportLine(env, false);
    await publishStickyComment(
      client,
      repository,
      target.issueNumber,
      reconciledInitialBody(inconclusiveComment(discardReason, discardLine)),
      async () => buildReconciledBody(inconclusiveComment(discardReason, discardLine)),
    );
    await reconcileNeedsDecisionOnly(client, repository, target.issueNumber);
    return;
  }

  const hasSecurityBlock = output.deterministicViolations.some((violation) =>
    violation.severity === 'BLOCK' && (violation.category === 'security' || violation.ruleId.startsWith('SEC-'))) ||
    output.roles.some((role) => role.role === 'android_sec' && role.findings.some((finding) => finding.severity === 'BLOCK'));
  // Deterministic rules first (priority): configured + area + review verdict
  // labels (security/needs-decision from BLOCK verdict). AI suggestions are
  // merged second and cannot override or clear deterministic BLOCK-related
  // labels because the merge is a union and reconciliation only touches the
  // bot-managed scope.
  const rulesLabels = sanitizeLabels([
    ...configuredTagLabels(env),
    ...(output.changedFilesComplete ? output.areaLabels : []),
    ...resolveReviewLabels({
      verdict: output.verdict,
      hasSecurityFinding: hasSecurityBlock,
    }),
  ]);
  const labels = sanitizeLabels([...rulesLabels, ...prFiltered.kept]);
  const coverageComplete = output.coverage.complete && output.changedFilesComplete;
  // Phase D (P1 #4) double-gate: JOB_RESULT==success plus content-validated
  // reports (output↔report verdict/fingerprint/time). A valid APPROVE with a
  // missing/tampered/partial report must not publish APPROVE with an
  // unavailable line; fall back to the same sticky INCONCLUSIVE with no fake
  // link plus reconcileNeedsDecisionOnly. Single comment ID updated in place.
  const prReportsAvailable = areReviewReportsAvailable(env);
  if (!prReportsAvailable) {
    const unavailableLine = formatReportLine(env, false);
    const reportsMissingReason = 'the review reports are missing or inconsistent; review freshness could not be verified.';
    await publishStickyComment(
      client,
      repository,
      target.issueNumber,
      reconciledInitialBody(inconclusiveComment(reportsMissingReason, unavailableLine)),
      async () => buildReconciledBody(inconclusiveComment(reportsMissingReason, unavailableLine)),
    );
    await reconcileNeedsDecisionOnly(client, repository, target.issueNumber);
    return;
  }
  // Phase 2 (H): success writes back the artifact/run link; the gate above
  // guarantees content-valid reports, so only a missing run identity degrades
  // to unavailable (still no fake link).
  const prReportLine = formatReportLine(env, getReportRunUrl(env) !== undefined);

  let publishFallbackReason: string | undefined;
  await publishStickyComment(client, repository, target.issueNumber, reconciledInitialBody(reviewComment(output, labels, prReportLine)), async () => {
    publishFallbackReason = await currentReviewProblem(client, repository, target.issueNumber!, output!);
    const unavailableFallback = formatReportLine(env, false);
    const content = publishFallbackReason ? inconclusiveComment(publishFallbackReason, unavailableFallback) : reviewComment(output!, labels, prReportLine);
    return buildReconciledBody(content);
  });
  if (publishFallbackReason) {
    await reconcileNeedsDecisionOnly(client, repository, target.issueNumber);
    return;
  }

  const labelFallbackReason = await currentReviewProblem(client, repository, target.issueNumber, output);
  if (labelFallbackReason) {
    const labelFallbackLine = formatReportLine(env, false);
    await publishStickyComment(
      client,
      repository,
      target.issueNumber,
      reconciledInitialBody(inconclusiveComment(labelFallbackReason, labelFallbackLine)),
      async () => buildReconciledBody(inconclusiveComment(labelFallbackReason, labelFallbackLine)),
    );
    await reconcileNeedsDecisionOnly(client, repository, target.issueNumber);
    return;
  }

  // P1 #3 provenance: the sticky bot-labels ledger marker (last bot-written
  // set) is read from the pre-reconcile baseline and passed as the proven
  // set; a missing marker means unknown provenance (fail-closed, transitions
  // retained). The timeline fallback inside reconcile (actor == botLogin)
  // covers GitHub-recorded writes with zero extra sticky writes.
  const prProvenBotLabels = parseBotLabelsMarker(baselineReconciled.body);
  const reconciliation = await reconcileBotLabelsSafely({
    client,
    owner: repository.owner,
    repo: repository.repo,
    issueNumber: target.issueNumber,
    desiredLabels: labels,
    scope: output.changedFilesComplete
      ? DEFAULT_PR_RECONCILE_SCOPE
      : { managedExactLabels: ['status:needs-decision'] } satisfies ReconcileScope,
    coverageComplete,
    botWrittenLabels: prProvenBotLabels,
  });
  const expectedLabels = sanitizeLabels([
    ...labels,
    ...(coverageComplete ? [] : ['status:needs-decision']),
  ]);
  const reconciledLabels = new Set([...reconciliation.added, ...reconciliation.skipped]);
  if (
    reconciliation.failedToList ||
    expectedLabels.some((label) => !reconciledLabels.has(label)) ||
    (reconciliation.failedRemovals?.length ?? 0) > 0
  ) {
    throw new Error('PocketGuard: failed to reconcile review labels.');
  }
}

function modeFromArgs(args: string[]): 'tag' | 'review' | 'publish' | 'claim' | undefined {
  const inline = args.find((arg) => arg.startsWith('--mode='))?.slice('--mode='.length);
  const index = args.indexOf('--mode');
  const mode = inline ?? (index >= 0 ? args[index + 1] : undefined);
  return mode === 'tag' || mode === 'review' || mode === 'publish' || mode === 'claim' ? mode : undefined;
}

async function main(): Promise<void> {
  const mode = modeFromArgs(process.argv.slice(2));
  if (!mode) {
    process.stderr.write('PocketGuard: specify --mode=tag, --mode=review, --mode=claim, or --mode=publish.\n');
    process.exitCode = 2;
    return;
  }
  try {
    if (mode === 'tag') await runTagMode();
    if (mode === 'review') await runReviewEntryMode();
    if (mode === 'claim') await runClaimMode();
    if (mode === 'publish') await runPublishMode();
  } catch {
    process.stderr.write('PocketGuard: operation failed.\n');
    process.exitCode = 1;
  }
}

if (require.main === module) void main();
