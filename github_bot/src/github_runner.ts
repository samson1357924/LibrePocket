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
}

interface RunnerGitHubClient {
  rest: {
    pulls: { get(params: { owner: string; repo: string; pull_number: number }): Promise<{ data: PullRequestData }> };
    issues: {
      get?(params: { owner: string; repo: string; issue_number: number }): Promise<{ data: { number?: number; title?: string; body?: string | null } }>;
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
// should_review/should_tag flags emitted by runTagMode.
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
  let reviewGate = resolveReviewGate(route.kind, flags.shouldReview);
  let shouldReview = flags.shouldReview;
  let reason = route.reason;
  let reviewsUsed = 0;
  if (target.target === 'issue') {
    reviewGate = 'none';
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
//
// Semantics: claim = (repo,PR,full head SHA) first +1 plus a per-run
// (sha,run_id,attempt) marker. review-send only runs AI when its own claim
// marker is present; publish only reconciles and never +1. Routing,
// authorization, or quota blocks (unstarted) write nothing and count zero; a
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
  // Read-then-write: an unreadable ledger fails closed with no write.
  const baseline = await readStickyLedger(client, repository.owner, repository.repo, issueNumber);
  if (!baseline.ok) {
    return emit({ claimed: false, reviewsUsed: 0, reason: 'quota-unknown: sticky ledger unreadable', issueNumber });
  }
  const existing = baseline.ledger.get(claimSha) ?? 0;
  if (existing >= MAX_REVIEWS_PER_SHA) {
    return emit({ claimed: false, reviewsUsed: existing, reason: `quota-exhausted: head ${claimSha} already at ${existing}`, issueNumber, claimSha });
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

function saveReviewOutput(output: RunnerReviewOutput, context: RunnerContext): void {
  const env = context.env ?? process.env;
  const serialized = `${JSON.stringify(output, null, 2)}\n`;
  const outputPath = env.POCKETGUARD_OUTPUT;
  if (!outputPath) {
    stdout(context, serialized);
    return;
  }
  const resolved = path.resolve(outputPath);
  fs.mkdirSync(path.dirname(resolved), { recursive: true });
  fs.writeFileSync(resolved, serialized, { encoding: 'utf8', mode: 0o600 });
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
  // publish) plus a revision fingerprint over title/body/comments so publish
  // can verify freshness and update the same sticky on mismatch.
  // P1 #2: comments completeness over the live listComments read. Review
  // never APPROVEs when false; publish requires true on both the artifact
  // and the fresh re-read plus fingerprint equality, otherwise it falls back
  // to INCONCLUSIVE (same sticky, never a wrong APPROVE).
  suggestedLabels: string[];
  fingerprint: string;
  commentsComplete: boolean;
}

function issueRulesTags(title: string): string[] {
  return sanitizeLabels(resolveLabelsFromTitle(title));
}

// S5 content fingerprint for issues: SHA-256 over the normalized review
// context (title, body, human comments). PR freshness reuses the existing
// head/base SHA identity (sameReviewIdentity); issues hash their mutable
// text content instead. Publish recomputes from the current context and
// falls back to INCONCLUSIVE on the same sticky when the fingerprint
// mismatches.
export function issueContentFingerprint(title: string, body: string, comments: string[]): string {
  const normalized = JSON.stringify({ title, body, comments });
  return createHash('sha256').update(normalized, 'utf8').digest('hex');
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

function saveIssueOutput(output: RunnerIssueOutput, context: RunnerContext): void {
  const env = context.env ?? process.env;
  const serialized = `${JSON.stringify(output, null, 2)}\n`;
  const outputPath = env.POCKETGUARD_OUTPUT;
  if (!outputPath) {
    stdout(context, serialized);
    return;
  }
  const resolved = path.resolve(outputPath);
  fs.mkdirSync(path.dirname(resolved), { recursive: true });
  fs.writeFileSync(resolved, serialized, { encoding: 'utf8', mode: 0o600 });
}

export function validateIssueOutput(value: unknown): RunnerIssueOutput | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const raw = value as Record<string, unknown>;
  if (
    Object.keys(raw).some((key) => !['verdict', 'issueNumber', 'title', 'tags', 'summary', 'suggestedLabels', 'fingerprint', 'commentsComplete'].includes(key)) ||
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
  };
}

// P2 #5 issue freshness: title/body must come from the live GitHub issue,
// never from the webhook snapshot alone. Returns the fresh fields when the
// client exposes issues.get and the read validates; returns undefined when
// the API is unavailable (callers fall back to the webhook snapshot for
// backward compatibility with clients that lack `get`); throws a generic
// error when a present `get` fails or returns an identity mismatch so
// callers degrade to INCONCLUSIVE instead of reviewing stale content.
export async function fetchFreshIssueFields(
  client: RunnerGitHubClient | undefined,
  owner: string,
  repo: string,
  issueNumber: number,
): Promise<{ title: string; body: string } | undefined> {
  const issues = client?.rest.issues as RunnerGitHubClient['rest']['issues'] | undefined;
  if (!issues || typeof issues.get !== 'function') return undefined;
  let data: { number?: number; title?: string; body?: string | null };
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
  return { title: data.title, body: typeof data.body === 'string' ? data.body : '' };
}

// S4 issue context: title plus body plus human comments (bot authors and bot
// senders excluded by the same loop-protection rules as routing), truncated
// to MAX_ISSUE_CONTEXT_LENGTH. P1 #2 fail-closed completeness: commentsComplete
// is true only when every comment page was read successfully (client,
// repository, and listComments present, every response.data an array of
// objects, no throw) and no fetched comment was dropped by the length budget.
// Any other outcome — missing API, throw, non-array, non-object entry, or
// truncation that discards a comment — yields commentsComplete false (the
// caller must not APPROVE). A webhook issue_comment body may be merged as a
// minimal input after the same bot filter, but the result stays incomplete and
// never lifts the APPROVE ban. `truncated` distinguishes active budget
// truncation from transport incompleteness.
async function buildIssueContext(
  context: RunnerContext,
  repository: { owner: string; repo: string } | undefined,
  issueNumber: number,
  title: string,
  body: string,
): Promise<{ title: string; body: string; comments: string[]; commentsComplete: boolean; truncated: boolean }> {
  const comments: string[] = [];
  let commentsComplete = true;
  let truncated = false;
  const client = apiClient(context, (context.env ?? process.env).GITHUB_TOKEN ?? '');
  try {
    const issues = client?.rest.issues as RunnerGitHubClient['rest']['issues'] | undefined;
    if (!client || !repository || typeof issues?.listComments !== 'function') {
      commentsComplete = false;
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
          break;
        }
        if (!response || typeof response !== 'object' || !Array.isArray((response as { data: unknown }).data)) {
          commentsComplete = false;
          break;
        }
        const entries = (response as { data: unknown[] }).data;
        for (const comment of entries) {
          if (!comment || typeof comment !== 'object' || Array.isArray(comment)) {
            commentsComplete = false;
            continue;
          }
          const entry = comment as { body?: unknown; user?: { login?: unknown; type?: unknown } | null };
          if (typeof entry.body !== 'string' || !entry.body.trim()) continue;
          if (typeof entry.user?.type === 'string' && entry.user.type.toLowerCase() === 'bot') continue;
          if (typeof entry.user?.login === 'string' && isBotLogin(entry.user.login)) continue;
          const login = typeof entry.user?.login === 'string' && entry.user.login.trim()
            ? entry.user.login.trim()
            : 'unknown';
          comments.push(`${login}：${safeString(entry.body)}`);
        }
        if (entries.length < 100) {
          finished = true;
        }
      }
    }
  } catch {
    commentsComplete = false;
  }
  // Webhook minimal input: when the live read is incomplete, merge the
  // triggering issue_comment body (after bot filtering) so the model still
  // sees the immediate human input. The result stays incomplete and never
  // lifts the APPROVE ban.
  if (!commentsComplete) {
    try {
      const webhookEvent: GithubEvent = context.event ?? eventFrom(context);
      const webhookBody = webhookEvent.comment?.body;
      if (typeof webhookBody === 'string' && webhookBody.trim() && !isBotEventActor(webhookEvent)) {
        const rawLogin = webhookEvent.comment?.user?.login ?? webhookEvent.sender?.login;
        if (!(typeof rawLogin === 'string' && isBotLogin(rawLogin))) {
          const login = typeof rawLogin === 'string' && rawLogin.trim() ? rawLogin.trim() : 'unknown';
          const merged = `${login}：${safeString(webhookBody)}`;
          if (!comments.includes(merged)) comments.push(merged);
        }
      }
    } catch {
      // No webhook input available; stay incomplete with fetched comments only.
    }
  }
  const safeTitle = safeString(title);
  let safeBody = safeString(body, 8000);
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
  }
  return { title: safeTitle, body: safeBody, comments: kept, commentsComplete, truncated };
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
      const builtForPrint = await buildIssueContext(context, repository, issueNumber, title, body);
      return {
        fingerprint: issueContentFingerprint(builtForPrint.title, builtForPrint.body, builtForPrint.comments),
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
  const built = await buildIssueContext(context, repository, issueNumber, title, body);
  const fingerprint = issueContentFingerprint(built.title, built.body, built.comments);
  // P1 #2: never APPROVE on an incomplete comment set. Downgrade before any
  // OpenAI call (zero model traffic) so a fail-open read cannot approve.
  if (!built.commentsComplete) {
    const output: RunnerIssueOutput = {
      verdict: 'INCONCLUSIVE',
      issueNumber,
      title: safeString(title),
      tags: sanitizeLabels([...issueRulesTags(title), 'status:needs-decision']),
      summary: 'the issue comments could not be fully fetched from GitHub; review freshness could not be verified.',
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
    let verdict = triaged.verdict;
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

function safeString(value: unknown, maxLength = 2000): string {
  if (typeof value !== 'string') return '';
  return redactForModel(value)
    .replace(/[\u0000-\u001f\u007f]/g, ' ')
    .slice(0, maxLength);
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

function reviewComment(output: RunnerReviewOutput, appliedLabels: string[] = []): string {
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
    for (const finding of role.findings) {
      const location = finding.file
        ? ` (${escapeMarkdown(finding.file)}${finding.line ? `:${finding.line}` : ''})`
        : '';
      lines.push(`- **${finding.severity}**${location}: ${escapeMarkdown(finding.issue)}`);
      if (finding.suggestion) lines.push(`  - 建議：${escapeMarkdown(finding.suggestion)}`);
    }
    lines.push('');
  }
  for (const violation of output.deterministicViolations) {
    lines.push(`- **${violation.severity} ${escapeMarkdown(violation.ruleId)}**: ${escapeMarkdown(violation.message)}`);
  }
  return `${lines.join('\n')}\n`;
}

function inconclusiveComment(reason: string): string {
  return [
    REVIEW_MARKER,
    '',
    '## PocketGuard 審查',
    '',
    '**判定：INCONCLUSIVE**',
    `**審查結果不可用：${safeString(reason)}**`,
    `更新時間：${new Date().toISOString()}`,
    SAFE_MESSAGE,
    '',
  ].join('\n');
}

// S5 issue sticky: one comment per issue, edited in place (never a second
// comment). Carries verdict, summary, label decision, updated time, and the
// revision fingerprint over title/body/comments so readers can tell whether
// the published result matches the current issue content.
function issueReviewComment(output: RunnerIssueOutput, appliedLabels: string[]): string {
  const updatedAt = new Date().toISOString();
  return [
    REVIEW_MARKER,
    '',
    '## PocketGuard 議題審查',
    '',
    `**判定：${output.verdict}**`,
    `議題：#${output.issueNumber} ${escapeMarkdown(output.title)}`,
    `摘要：${escapeMarkdown(output.summary) || '（無摘要）'}`,
    `標籤決策：${appliedLabels.length > 0 ? appliedLabels.map((label) => escapeMarkdown(label)).join('、') : '無'}`,
    `修訂指紋：\`${output.fingerprint}\``,
    `更新時間：${updatedAt}`,
    '',
  ].join('\n');
}

function issueInconclusiveComment(issueNumber: number, title: string, reason: string, fingerprint: string): string {
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
  const reconciliation = await reconcileBotLabelsSafely({
    client,
    owner: repository.owner,
    repo: repository.repo,
    issueNumber,
    desiredLabels: ['status:needs-decision'],
    scope: { managedExactLabels: ['status:needs-decision'] } satisfies ReconcileScope,
    coverageComplete: false,
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
    let freshIssueReadFailed = false;
    if (typeof client.rest.issues.get === 'function') {
      try {
        const fresh = await fetchFreshIssueFields(client, repository.owner, repository.repo, issueNumber);
        if (fresh) {
          issueTitle = fresh.title;
          issueBody = fresh.body;
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
    try {
      const builtCurrent = await buildIssueContext(context, repository, issueNumber, issueTitle, issueBody);
      currentFingerprint = issueContentFingerprint(builtCurrent.title, builtCurrent.body, builtCurrent.comments);
      currentCommentsComplete = builtCurrent.commentsComplete;
    } catch {
      currentFingerprint = '0'.repeat(64);
      currentCommentsComplete = false;
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
          else if (currentCommentsComplete !== true) issueFallbackReason = 'the current issue comments could not be fully fetched from GitHub; review freshness could not be verified.';
          else if (validated.fingerprint === '0'.repeat(64) || currentFingerprint === '0'.repeat(64)) issueFallbackReason = 'the review output is unverifiable: review freshness could not be verified.';
          else if (validated.fingerprint !== currentFingerprint) issueFallbackReason = 'the review output is stale: the issue content changed after review.';
          else if (hasUnknownAiLabels(validated.suggestedLabels)) issueFallbackReason = 'the AI label suggestions contain unknown labels; discarded.';
        }
      }
    }

    if (issueFallbackReason || !validated) {
      const fallbackFingerprint = validated?.fingerprint && /^[0-9a-f]{64}$/.test(validated.fingerprint)
        ? validated.fingerprint
        : currentFingerprint;
      await publishStickyComment(
        client,
        repository,
        issueNumber,
        issueInconclusiveComment(issueNumber, issueTitle, issueFallbackReason ?? 'a valid review result was unavailable.', fallbackFingerprint),
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
        issueInconclusiveComment(issueNumber, issueTitle, 'the AI label suggestions contain unknown labels; discarded.', currentFingerprint),
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
    await publishStickyComment(
      client,
      repository,
      issueNumber,
      issueReviewComment(stickyOutput, desiredIssueLabels),
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
  if (freshPullRequest && safeSha(freshPullRequest.head.sha)) {
    const freshLower = freshPullRequest.head.sha.toLowerCase();
    const publishClaimKey = resolveCountClaimKey(env);
    if (publishClaimKey) {
      const ledger = await readStickyLedger(client, repository.owner, repository.repo, target.issueNumber);
      if (!ledger.ok) return;
      const used = ledger.ledger.get(freshLower) ?? 0;
      if (!ledger.claims.has(`${freshLower}:${publishClaimKey}`) && used >= MAX_REVIEWS_PER_SHA) return;
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
  if (fallbackReason || !output || !(output && safeSha(output.headSha))) {
    const reasonText = fallbackReason ?? 'a valid review result was unavailable.';
    const baseline = await readStickyLedger(client, repository.owner, repository.repo, target.issueNumber);
    if (!baseline.ok) return;
    await publishStickyComment(
      client,
      repository,
      target.issueNumber,
      buildStampedBody(inconclusiveComment(reasonText), baseline.ledger, baseline.claims, undefined, undefined, undefined),
      async () => buildPreservedBody(inconclusiveComment(reasonText)),
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
    await publishStickyComment(
      client,
      repository,
      target.issueNumber,
      reconciledInitialBody(inconclusiveComment(staleReason)),
      async () => buildReconciledBody(inconclusiveComment(staleReason)),
    );
    await reconcileNeedsDecisionOnly(client, repository, target.issueNumber);
    return;
  }

  // S5 AI label gate: raw suggestions are allowlisted at publish. Unknown
  // entries are discarded with a log and force INCONCLUSIVE on the same
  // sticky (never a wrong APPROVE, never writing illegal labels). Manual
  // labels outside the bot scope are always preserved by reconciliation.
  // The ledger stays as the claim left it (reconciliation only).
  const aiRaw = Array.isArray(output.suggestedLabels) ? output.suggestedLabels : [];
  const { sanitizedAi, hadUnknown } = mergeRulesWithAiSuggestions([], aiRaw);
  if (hadUnknown) {
    await publishStickyComment(
      client,
      repository,
      target.issueNumber,
      reconciledInitialBody(inconclusiveComment('the AI label suggestions contain unknown labels; discarded.')),
      async () => buildReconciledBody(inconclusiveComment('the AI label suggestions contain unknown labels; discarded.')),
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
  const labels = sanitizeLabels([...rulesLabels, ...sanitizedAi]);
  const coverageComplete = output.coverage.complete && output.changedFilesComplete;

  let publishFallbackReason: string | undefined;
  await publishStickyComment(client, repository, target.issueNumber, reconciledInitialBody(reviewComment(output, labels)), async () => {
    publishFallbackReason = await currentReviewProblem(client, repository, target.issueNumber!, output!);
    const content = publishFallbackReason ? inconclusiveComment(publishFallbackReason) : reviewComment(output!, labels);
    return buildReconciledBody(content);
  });
  if (publishFallbackReason) {
    await reconcileNeedsDecisionOnly(client, repository, target.issueNumber);
    return;
  }

  const labelFallbackReason = await currentReviewProblem(client, repository, target.issueNumber, output);
  if (labelFallbackReason) {
    await publishStickyComment(
      client,
      repository,
      target.issueNumber,
      reconciledInitialBody(inconclusiveComment(labelFallbackReason)),
      async () => buildReconciledBody(inconclusiveComment(labelFallbackReason)),
    );
    await reconcileNeedsDecisionOnly(client, repository, target.issueNumber);
    return;
  }

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
