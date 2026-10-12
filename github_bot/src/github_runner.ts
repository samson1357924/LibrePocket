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
  sanitizeIssueAiSuggestions,
  sanitizePrAiSuggestions,
  type GitHubLabelClient,
  type ReconcileScope,
} from './label_manager';
import {
  collectFileDiffs,
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
// Single-field caps (title 2000 / body 8000 / comment 2000) plus the total
// budget MAX_ISSUE_CONTEXT_LENGTH bound the single-turn model copy; any cut
// sets commentsComplete=false and the review stays INCONCLUSIVE (never
// APPROVE, deterministic BLOCK may still NEEDS_CHANGES). Truncated reviews
// carry honest shown/total counts; issues never consume the PR quota.
export const MAX_ISSUE_CONTEXT_LENGTH = 20000;
// P6 two-layer diagnosis budgets: at most 10 default-branch files, 6000
// chars each, 30000 chars total. Over-budget files are truncated (the
// truncation flag travels in the snippet header); any fetch failure aborts the second layer (the first-round
// result stands).
export const MAX_ISSUE_CODE_FILES = 10;
export const MAX_ISSUE_CODE_CHARS_PER_FILE = 6000;
export const MAX_ISSUE_CODE_TOTAL_CHARS = 30000;
export const MAX_STICKY_SUMMARY_LENGTH = 2000;
export const MAX_STICKY_FINDINGS_PER_ROLE = 20;
// Phase 3 (P2 #3): whole-sticky hard budget. GitHub issue/PR comments reject
// bodies past 65536 chars, so the PR sticky (reviewComment) is capped at
// MAX_STICKY_TOTAL_LENGTH leaving headroom for ledger/claim markers stamped
// later by buildStampedBody. Single-item and file-list caps bound each entry;
// the candidate pools (60 findings = 3 roles x 20, 20 violations) are selected
// by BLOCK>WARN>SUGGESTION with stable original order within a severity, then
// filled incrementally (truncate->escape, accounted post-escape) until the
// budget stops; the tail carries total/shown/omitted plus artifact guidance.
// Overflow never opens a second comment: excess stays in the report artifact.
export const MAX_STICKY_TOTAL_LENGTH = 50000;
export const MAX_STICKY_ITEM_LENGTH = 500;
export const MAX_STICKY_FILE_NAME_LENGTH = 200;
export const MAX_STICKY_FILE_LIST_SHOWN = 20;
export const MAX_STICKY_FINDING_POOL_SIZE = 60;
export const MAX_STICKY_VIOLATION_POOL_SIZE = 20;
// GitHub comment hard limit (fail-safe in publishStickyComment).
export const GITHUB_COMMENT_HARD_LIMIT = 65536;
export const REVIEW_REPORT_JSON_NAME = 'review-report.json';
export const REVIEW_REPORT_MD_NAME = 'review-report.md';
export const REVIEW_REPORT_ARTIFACT_NAME = 'pocketguard-review-report';
// Phase 3 (P2 #3) deadline/concurrency/timeout (fail-closed, never
// silent omission). t0 + effectiveDeadline bounds the whole PR
// review, where effectiveDeadline = min(configured POCKETGUARD_REVIEW_DEADLINE_MS
// (default 20min minus REVIEW_DEADLINE_MARGIN_MS for artifact/report upload),
// jobRemaining - REVIEW_JOB_UPLOAD_SKEW_MS) with jobRemaining =
// REVIEW_JOB_TIMEOUT_MS - (now - POCKETGUARD_JOB_STARTED_MS). The trusted
// jobStarted is recorded by the first review-send step into $GITHUB_ENV so
// slow hosted setup still leaves upload headroom; a missing/invalid
// jobStarted downgrades to the configured deadline (fail-closed, never
// extends). Before the single wave the runner estimates need (the per-wave
// timeout: min(420s, effectiveRemaining/remainingWaves) with
// effectiveRemaining = min(reviewRemaining, jobRemaining - skew));
// remaining < need exits early with an INCONCLUSIVE checkpoint (roles
// marked not-run, diff coverage as computed) and never writes true.
// Longest configured model fallback chain across the three review roles.
// Mirrors the model client's chain composition without importing it: tag
// and publish entry points must stay free of the OpenAI client so secrets never
// reach those jobs. A role may burn a whole chain before it finishes, so
// wall-clock budgets divide by this factor.
export function maxModelChainAttempts(env: NodeJS.ProcessEnv): number {
  const count = (raw: string | undefined): number =>
    (raw ?? '').split(',').map((part) => part.trim()).filter((part) => part !== '').length;
  const chains = (['ARCHITECTURE', 'ANDROID_SEC', 'ANDROID_CODE'] as const).map((role) =>
    1 + count(env[`POCKETGUARD_MODEL_${role}_FALLBACKS`]) +
    // Deprecated pre-rename fallback list still extends the chain (see the
    // model client's legacy env fallback table); count it so the
    // wall-clock budget never underestimates.
    count(role === 'ARCHITECTURE' ? env.POCKETGUARD_MODEL_CHIEF_FALLBACKS : undefined) +
    count(env.POCKETGUARD_MODEL_FALLBACKS));
  return Math.max(1, ...chains);
}

// Fixed-concurrency review waves run orchestrateReview; timeoutMs passes
// through min(420s, remaining/remainingWaves) so slow roles abort via the
// transport timeout. The claim pre-flight adds a wave x timeout budget
// check; over budget claims nothing with zero writes. Every deadline exit
// saves via saveReviewOutput (checkpoint artifact) as INCONCLUSIVE.
export const REVIEW_DEADLINE_MARGIN_MS = 60_000;
export const DEFAULT_REVIEW_DEADLINE_MS = 20 * 60 * 1000 - REVIEW_DEADLINE_MARGIN_MS;
// Phase 2 (P2 #2) job wall-clock: the review-send job runs with
// timeout-minutes: 20, so the trusted POCKETGUARD_JOB_STARTED_MS (ms since
// epoch, recorded by the first review-send step into $GITHUB_ENV) bounds the
// whole review by jobRemaining = REVIEW_JOB_TIMEOUT_MS - (now - jobStarted).
// The effective budget is min(configured, jobRemaining - UPLOAD_SKEW) so slow
// hosted setup (checkout + setup-node + npm ci) cannot push the artifact
// upload past the job timeout. Missing/invalid jobStarted falls back to the
// configured deadline (fail-closed conservative downgrade: never extends).
export const REVIEW_JOB_TIMEOUT_MS = 20 * 60 * 1000;
export const REVIEW_JOB_UPLOAD_SKEW_MS = 60_000;
export const PR_CHUNK_CONCURRENCY = 2;
export const MAX_PER_CHUNK_TIMEOUT_MS = 420_000;
export const MIN_VIABLE_PER_CHUNK_TIMEOUT_MS = 10_000;
export const CHUNK_DEADLINE_SAVE_MARGIN_MS = 1_000;
// Phase D (P1 #4): identifiable report-write failure. writeReviewReports
// returns this message in its status (and logs it) instead of swallowing the
// error; saveReviewOutput/saveIssueOutput downgrade APPROVE to INCONCLUSIVE
// on failure so a missing report can never approve.
export const REVIEW_REPORT_WRITE_ERROR = 'PocketGuard: failed to write review reports.';
export type ReviewReportWriteResult = { ok: true } | { ok: false; reason: string };
const ROLE_NAMES = ['architecture', 'android_sec', 'android_code'] as const;
const SAFE_MESSAGE = 'Automated review was not executed; please have maintainers review the changes.';

// Phase 3 (P2 #3) helpers: deadline/timeout estimation. All are pure
// (Date.now injected by callers via remainingMs) so tests use a fake clock
// plus a fake fetch without flakiness.
export function resolveReviewDeadlineMs(env?: NodeJS.ProcessEnv): number {
  const raw = env?.POCKETGUARD_REVIEW_DEADLINE_MS?.trim() ?? '';
  if (raw) {
    const parsed = Number(raw);
    if (Number.isFinite(parsed) && parsed > 0) return Math.floor(parsed);
  }
  return DEFAULT_REVIEW_DEADLINE_MS;
}

// Phase 2 (P2 #2) trusted job-start instant (ms since epoch). The workflow
// records it in the first review-send step
// (`date +%s%3N` into $GITHUB_ENV); it is workflow-defined, never taken from
// PR content or the event payload. Missing/non-numeric/non-positive values
// return undefined so callers downgrade to the configured deadline.
export function resolveJobStartedMs(env?: NodeJS.ProcessEnv): number | undefined {
  const raw = env?.POCKETGUARD_JOB_STARTED_MS?.trim() ?? '';
  if (!raw) return undefined;
  const parsed = Number(raw);
  if (!Number.isFinite(parsed) || parsed <= 0) return undefined;
  return Math.floor(parsed);
}

// Phase 2 (P2 #2) effective whole-review budget:
// min(configured, jobRemaining - UPLOAD_SKEW) where
// jobRemaining = REVIEW_JOB_TIMEOUT_MS - (nowMs - jobStartedMs).
// Missing/invalid jobStarted falls back to configured (fail-closed
// conservative: never extends the budget). A negative clock skew
// (now < started) clamps elapsed to 0; an exhausted job budget clamps to 0
// so callers exit early with INCONCLUSIVE instead of starting work.
// nowMs defaults to Date.now() so production callers pass nothing while
// tests inject a fake clock.
export function resolveEffectiveReviewDeadlineMs(env?: NodeJS.ProcessEnv, nowMs?: number): number {
  const configured = resolveReviewDeadlineMs(env);
  const jobStarted = resolveJobStartedMs(env);
  if (jobStarted === undefined) return configured;
  const now = Number.isFinite(nowMs) ? Math.floor(nowMs as number) : Date.now();
  if (!Number.isFinite(now)) return configured;
  const elapsed = Math.max(0, now - jobStarted);
  const jobBudget = REVIEW_JOB_TIMEOUT_MS - elapsed - REVIEW_JOB_UPLOAD_SKEW_MS;
  return Math.max(0, Math.min(configured, Math.floor(jobBudget)));
}

// Per-chunk timeout passthrough: min(420s, remaining/remainingWaves) with an
// optional POCKETGUARD_CHUNK_TIMEOUT_MS override (tests) clamped to the same
// ceiling. remainingWaves is ceil(remainingChunks / PR_CHUNK_CONCURRENCY).
// Returns at least 1ms (send rejects <=0 fail-closed); callers check
// remaining < need before starting a wave so a 1ms timeout never starts.
export function resolvePerChunkTimeoutMs(
  env: NodeJS.ProcessEnv | undefined,
  remainingMs: number,
  remainingWaves: number,
  attemptFactor: number = 1,
): number {
  const waves = Number.isFinite(remainingWaves) && remainingWaves > 0 ? Math.max(1, Math.floor(remainingWaves)) : 1;
  // A model can fall through a fallback chain, so one role may burn
  // chainLength attempts before it is done. Budget per attempt, not per role.
  const attempts = Number.isFinite(attemptFactor) && attemptFactor >= 1 ? Math.max(1, Math.floor(attemptFactor)) : 1;
  const remaining = Number.isFinite(remainingMs) ? Math.max(0, Math.floor(remainingMs)) : 0;
  const raw = env?.POCKETGUARD_CHUNK_TIMEOUT_MS?.trim() ?? '';
  let ceiling = MAX_PER_CHUNK_TIMEOUT_MS;
  if (raw) {
    const parsed = Number(raw);
    if (Number.isFinite(parsed) && parsed > 0) ceiling = Math.min(MAX_PER_CHUNK_TIMEOUT_MS, Math.floor(parsed));
  }
  return Math.max(1, Math.min(ceiling, Math.floor(remaining / (waves * attempts))));
}

// Wall-time estimate for the remaining chunked work under fixed concurrency:
// waves (= ceil(chunkCount / concurrency)) * perChunkTimeoutMs.
export function estimateChunkedReviewMs(chunkCount: number, perChunkTimeoutMs: number, concurrency: number = PR_CHUNK_CONCURRENCY): number {
  const count = Number.isFinite(chunkCount) ? Math.max(0, Math.floor(chunkCount)) : 0;
  const timeout = Number.isFinite(perChunkTimeoutMs) ? Math.max(0, Math.floor(perChunkTimeoutMs)) : 0;
  const conc = Number.isFinite(concurrency) && concurrency >= 1 ? Math.max(1, Math.floor(concurrency)) : PR_CHUNK_CONCURRENCY;
  if (count === 0 || timeout === 0) return 0;
  return Math.ceil(count / conc) * timeout;
}

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
    summary?: string;
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
  // P4 second-round synthesis: the architecture guard over a first-round
  // APPROVE. skipped is true when synthesis never ran (non-APPROVE first
  // round, or only one wave fit the deadline); otherwise verdict carries the
  // synthesis outcome and the stored top-level verdict already reflects it
  // (confirmed APPROVE, or tightened NEEDS_CHANGES with evidenced BLOCK
  // findings attached to the architecture role, or INCONCLUSIVE).
  synthesis?: {
    modelUsed: string;
    verdict: RunnerVerdict;
    summary?: string;
    skipped?: boolean;
  };
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
  // review mode); issue execution is a single architecture triage turn with no
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

// Phase 1 (ninth P1 #1) sticky overflow fail-closed: metadata (ledger/claim
// markers plus this footer) is never truncated — only the visible summary is
// trimmed (reviewComment's metadataReserve). A metadata-carrying body that
// still exceeds GITHUB_COMMENT_HARD_LIMIT fails closed (no write, old ledger
// preserved) instead of being blindly cut. Single sticky unchanged; the
// report artifact is never a ledger (counts are not recoverable from it).
export const STICKY_TAIL_MARGIN = 256;
export const STICKY_LIMIT_EXCEEDED_MESSAGE =
  'PocketGuard: sticky body exceeds the GitHub comment limit; fail-closed without writing.';
export const STICKY_LEDGER_UNREADABLE_MESSAGE =
  'PocketGuard: sticky ledger unreadable during publish; fail-closed without writing.';

export function isStickyLimitError(error: unknown): boolean {
  return error instanceof Error && error.message.includes('exceeds the GitHub comment limit');
}

export function isStickyLedgerUnreadableError(error: unknown): boolean {
  return error instanceof Error && error.message.includes('sticky ledger unreadable during publish');
}

// Hosted claim-slot diagnostics (sanitized, never serializes the error).
// publishStickyComment tags the failing write op plus the Octokit HTTP status
// on the generic throw; classifyStickyWriteError recovers only those two
// fields so callers can emit a fail-closed reason without leaking tokens,
// headers, response bodies, or diffs. Only HTTP status, method/path resource
// type (as op), PR number, SHA prefix, and run/attempt are ever recorded.
export type StickyWriteOp = 'list' | 'create' | 'update' | 'auth';
export interface StickyWriteErrorInfo {
  op: StickyWriteOp;
  status?: number;
}

function readOctokitStatus(error: unknown): number | undefined {
  if (!error || typeof error !== 'object') return undefined;
  const record = error as Record<string, unknown>;
  const direct = record['status'];
  if (typeof direct === 'number' && Number.isSafeInteger(direct) && direct >= 100 && direct <= 599) {
    return direct;
  }
  const response = record['response'];
  if (response && typeof response === 'object' && !Array.isArray(response)) {
    const nested = (response as Record<string, unknown>)['status'];
    if (typeof nested === 'number' && Number.isSafeInteger(nested) && nested >= 100 && nested <= 599) {
      return nested;
    }
  }
  return undefined;
}

export function classifyStickyWriteError(error: unknown): StickyWriteErrorInfo {
  let op: StickyWriteOp | undefined;
  let status: number | undefined;
  if (error && typeof error === 'object' && !Array.isArray(error)) {
    const record = error as Record<string, unknown>;
    const rawOp = record['stickyOp'];
    if (rawOp === 'list' || rawOp === 'create' || rawOp === 'update' || rawOp === 'auth') {
      op = rawOp;
    }
    const rawStatus = record['stickyStatus'];
    if (typeof rawStatus === 'number' && Number.isSafeInteger(rawStatus) && rawStatus >= 100 && rawStatus <= 599) {
      status = rawStatus;
    }
    if (status === undefined) status = readOctokitStatus(error);
    if (op === undefined) {
      const cause = record['cause'];
      if (cause && typeof cause === 'object' && !Array.isArray(cause)) {
        const causeRecord = cause as Record<string, unknown>;
        const causeOp = causeRecord['stickyOp'];
        if (causeOp === 'list' || causeOp === 'create' || causeOp === 'update' || causeOp === 'auth') {
          op = causeOp;
        }
        // Our own tag on the wrapped cause is read back with the same bounds
        // as the top-level tag (an untagged cause still falls back to its
        // Octokit status only).
        const causeStatus = causeRecord['stickyStatus'];
        if (
          status === undefined &&
          typeof causeStatus === 'number' &&
          Number.isSafeInteger(causeStatus) &&
          causeStatus >= 100 &&
          causeStatus <= 599
        ) {
          status = causeStatus;
        }
        if (status === undefined) status = readOctokitStatus(cause);
      }
    }
  } else {
    status = readOctokitStatus(error);
  }
  if (op === undefined) op = 'update';
  return status === undefined ? { op } : { op, status };
}

// Sanitized one-line error summary for stderr: message only (never headers,
// response bodies, or diffs), token shapes redacted first, then sliced.
export function sanitizeErrorSummary(error: unknown, maxLength = 200): string {
  let raw: string;
  if (error instanceof Error) raw = error.message;
  else if (typeof error === 'string') raw = error;
  else {
    try {
      raw = String(error);
    } catch {
      raw = 'unknown error';
    }
  }
  let redacted = raw.replace(/gh[pousr]_[A-Za-z0-9_]+/g, '[redacted-token]');
  redacted = redacted.replace(/Bearer\s+[^\s]+/gi, 'Bearer [redacted-token]');
  redacted = redacted.replace(/[\r\n]+/g, ' ');
  const limit = Number.isSafeInteger(Math.floor(maxLength)) && maxLength > 0 ? Math.floor(maxLength) : 200;
  if (redacted.length > limit) redacted = redacted.slice(0, limit);
  return redacted;
}

// True when the body carries sticky metadata that must never be truncated:
// the review marker, count/claim ledger markers, or the ledger footer below.
export function hasStickyMetadata(body: unknown): boolean {
  if (typeof body !== 'string') return false;
  return body.includes(REVIEW_MARKER) ||
    body.includes(REVIEW_COUNT_MARKER_PREFIX) ||
    body.includes(REVIEW_CLAIM_MARKER_PREFIX) ||
    body.includes(LEDGER_META_MARKER_PREFIX);
}

// Ledger integrity footer: appended by buildStampedBody so a truncated body
// is detectable on re-read. `counts`/`claims` are the marker totals and `len`
// is the char length of the body without the footer line itself. A legacy
// body without a footer verifies as unknown-provenance (accepted) so old
// stickies keep working; a present footer with mismatched counts or length
// means truncation and readers fail closed (ok:false).
export const LEDGER_META_MARKER_PREFIX = '<!-- PocketGuard-ledger-meta:';

export function formatLedgerFooter(ledgerSize: number, claimSize: number, bodyLength: number): string {
  const lc = Number.isSafeInteger(Math.floor(ledgerSize)) ? Math.max(0, Math.floor(ledgerSize)) : 0;
  const cc = Number.isSafeInteger(Math.floor(claimSize)) ? Math.max(0, Math.floor(claimSize)) : 0;
  const len = Number.isSafeInteger(Math.floor(bodyLength)) ? Math.max(0, Math.floor(bodyLength)) : 0;
  return `${LEDGER_META_MARKER_PREFIX}counts=${lc}:claims=${cc}:len=${len} -->`;
}

export function parseLedgerFooter(body: unknown): { counts: number; claims: number; len: number } | undefined {
  if (typeof body !== 'string') return undefined;
  const pattern = new RegExp(
    `${LEDGER_META_MARKER_PREFIX.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}counts=(\\d+):claims=(\\d+):len=(\\d+)\\s*-->`,
    'gi',
  );
  let found: { counts: number; claims: number; len: number } | undefined;
  for (const match of body.matchAll(pattern)) {
    const counts = Number.parseInt(match[1], 10);
    const claims = Number.parseInt(match[2], 10);
    const len = Number.parseInt(match[3], 10);
    if (!Number.isSafeInteger(counts) || !Number.isSafeInteger(claims) || !Number.isSafeInteger(len)) continue;
    found = { counts, claims, len };
  }
  return found;
}

export function stripLedgerFooters(body: string): string {
  const pattern = new RegExp(
    `${LEDGER_META_MARKER_PREFIX.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}counts=\\d+:claims=\\d+:len=\\d+\\s*-->\n?`,
    'gi',
  );
  return body.replace(pattern, '');
}

export function verifyLedgerFooter(body: string, ledger: Map<string, number>, claims: Set<string>): boolean {
  const footer = parseLedgerFooter(body);
  if (!footer) return true;
  const stripped = stripLedgerFooters(body);
  return stripped.length === footer.len &&
    ledger.size === footer.counts &&
    claims.size === footer.claims;
}

// Length of the marker suffix buildStampedBody would append for the given
// merged ledger/claims (markers plus footer, probed against an empty visible
// base; the footer's embedded length digits may shift by a few chars against
// a real base, covered by STICKY_TAIL_MARGIN at the callers).
export function estimateStickyMetadataLength(ledger: Map<string, number>, claims: Set<string>): number {
  return appendStickyMetadataSuffix('', ledger, claims).length;
}

function appendStickyMetadataSuffix(base: string, ledger: Map<string, number>, claims: Set<string>): string {
  const ledgerPart = [...ledger.entries()]
    .sort((left, right) => left[0].localeCompare(right[0]))
    .map(([entrySha, entryCount]) => formatReviewCountMarker(entrySha, entryCount))
    .join('\n');
  const claimMarkers = [...claims]
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
  result += `${formatLedgerFooter(ledger.size, claims.size, result.length)}\n`;
  return result;
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
// unchanged (uncounted fallback: no new marker, no increment). Always
// appends the ledger integrity footer (Phase 1); any stale footer riding on
// freshContent is stripped first so exactly one footer exists. The returned
// body is never truncated here — callers check requiredLength/fits and fail
// closed instead of cutting metadata.
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
  const stripped = stripLedgerFooters(stripReviewCountMarkers(freshContent));
  const base = stripped.length === 0 || stripped.endsWith('\n') ? stripped : `${stripped}\n`;
  return appendStickyMetadataSuffix(base, mergedLedger, mergedClaims);
}

export interface StampedBodyLength {
  body: string;
  requiredLength: number;
  fits: boolean;
}

// Length-reporting build (Phase 1): requiredLength is the exact stamped body
// length; fits is requiredLength within GITHUB_COMMENT_HARD_LIMIT.
export function buildStampedBodyWithLength(
  freshContent: string,
  freshLedger: Map<string, number>,
  freshClaims: Set<string>,
  targetSha: string | undefined,
  expectedCount: number | undefined,
  claimKey: string | undefined,
): StampedBodyLength {
  const body = buildStampedBody(freshContent, freshLedger, freshClaims, targetSha, expectedCount, claimKey);
  return { body, requiredLength: body.length, fits: body.length <= GITHUB_COMMENT_HARD_LIMIT };
}

// Fit-or-throw build (Phase 1): returns the stamped body, or throws a
// sticky-limit error (see isStickyLimitError) instead of truncating metadata.
export function assertStampedBodyFits(
  freshContent: string,
  freshLedger: Map<string, number>,
  freshClaims: Set<string>,
  targetSha: string | undefined,
  expectedCount: number | undefined,
  claimKey: string | undefined,
): string {
  const built = buildStampedBodyWithLength(freshContent, freshLedger, freshClaims, targetSha, expectedCount, claimKey);
  if (!built.fits) throw new Error(STICKY_LIMIT_EXCEEDED_MESSAGE);
  return built.body;
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
      if (sticky) {
        const text = typeof sticky.body === 'string' ? sticky.body : '';
        // Phase 1: same truncation guard as the full ledger read.
        if (!verifyLedgerFooter(text, parseReviewCountLedger(text), parseReviewClaimSet(text))) return { ok: false };
        return { ok: true, used: parseReviewCountMarker(text, headSha) };
      }
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
        const text = typeof sticky.body === 'string' ? sticky.body : '';
        const ledger = parseReviewCountLedger(text);
        const claims = parseReviewClaimSet(text);
        // Phase 1: a present-but-mismatched integrity footer means truncation;
        // fail closed instead of trusting a partial ledger.
        if (!verifyLedgerFooter(text, ledger, claims)) return { ok: false };
        return { ok: true, ledger, claims, body: text };
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

const CLAIM_PLACEHOLDER_BODY = `${REVIEW_MARKER}\n\n## PocketGuard Review\n\n**Verdict: INCONCLUSIVE**\n**Review slot claimed, awaiting review results.**\n`;

export async function runClaimMode(context: RunnerContext = {}): Promise<ClaimResult> {
  const env = context.env ?? process.env;
  const emit = (result: ClaimResult): ClaimResult => {
    try {
      appendWorkflowOutputs(env, {
        claimed: String(result.claimed),
        ...(result.claimSha ? { claim_sha: result.claimSha } : {}),
        reviews_used: String(result.reviewsUsed),
        ...(result.issueNumber ? { issue_number: String(result.issueNumber) } : {}),
        claim_reason: result.reason,
      });
    } catch {
      // Degraded: an unwritable GITHUB_OUTPUT must never block stdout JSON.
    }
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
  let target: Awaited<ReturnType<typeof inspectTarget>>;
  try {
    target = await inspectTarget(context);
  } catch {
    return emit({ claimed: false, reviewsUsed: 0, reason: 'unstarted: target inspection failed' });
  }
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
    // Phase 3 (P2 #3) claim budget: wave x timeout estimation (fail-
    // closed, zero writes when over budget). The review runs one wave with
    // per-wave timeoutMs min(420s, remaining/remainingWaves); the claim
    // refuses when the clamped per-wave share would fall below
    // MIN_VIABLE_PER_CHUNK_TIMEOUT_MS, i.e. the deadline budget cannot
    // reliably cover the waves. A refused claim
    // occupies nothing (claimed:false, reviewsUsed 0); a granted claim whose
    // later review times out still holds its slot (never refunded).
    // Phase 2 (P2 #2): the budget is the effective deadline
    // min(configured, jobRemaining - UPLOAD_SKEW) via
    // resolveEffectiveReviewDeadlineMs so a slow-hosted claim (setup already
    // consumed job wall-clock) refuses instead of reserving a slot the review
    // job cannot finish; a missing jobStarted downgrades to configured.
    {
      try {
        collectFileDiffs(runGit, mergeBase, freshPullRequest.head.sha, changedFiles);
      } catch {
        throw new Error('PocketGuard: diff pre-flight failed.');
      }
      const deadlineMs = resolveEffectiveReviewDeadlineMs(env, Date.now());
      const waves = 1;
      const perChunkShare = Math.floor(deadlineMs / waves);
      const perChunkTimeout = Math.min(MAX_PER_CHUNK_TIMEOUT_MS, perChunkShare);
      if (perChunkTimeout < MIN_VIABLE_PER_CHUNK_TIMEOUT_MS) {
        return emit({ claimed: false, reviewsUsed: 0, reason: `budget-exceeded: single wave needs ${waves} wave(s) x ${MAX_PER_CHUNK_TIMEOUT_MS}ms over deadline ${deadlineMs}ms (share ${perChunkShare}ms < viable ${MIN_VIABLE_PER_CHUNK_TIMEOUT_MS}ms); fail-closed without occupying a slot`, issueNumber });
      }
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
  // Phase 1: estimate before any write. A stamped body past the GitHub hard
  // limit fails closed with zero writes (claimed:false, quota-unknown,
  // reviewsUsed 0, nothing consumed) instead of a truncated write that would
  // silently drop ledger markers. The minimal visible (placeholder plus
  // markers) is reported so callers can tell a hopelessly overgrown ledger
  // from a merely verbose visible body; either way nothing is written.
  const initialMeasured = buildStampedBodyWithLength(cleanText, baseline.ledger, baseline.claims, claimSha, expected, claimKey);
  if (!initialMeasured.fits) {
    const minimalLength = buildStampedBody(
      CLAIM_PLACEHOLDER_BODY,
      baseline.ledger,
      baseline.claims,
      claimSha,
      expected,
      claimKey,
    ).length;
    return emit({
      claimed: false,
      reviewsUsed: 0,
      reason: `quota-unknown: sticky body would exceed the comment limit (needs ${initialMeasured.requiredLength}, minimal ${minimalLength} > ${GITHUB_COMMENT_HARD_LIMIT}); fail-closed without occupying a slot`,
      issueNumber,
    });
  }
  const initialBody = initialMeasured.body;
  const buildFreshBody = async (): Promise<string> => {
    const fresh = await readStickyLedger(client, repository.owner, repository.repo, issueNumber);
    if (!fresh.ok) throw new Error(STICKY_LEDGER_UNREADABLE_MESSAGE);
    const freshExisting = fresh.ledger.get(claimSha) ?? 0;
    const freshPresent = claimKey ? fresh.claims.has(`${claimSha}:${claimKey}`) : false;
    const freshExpected = freshPresent ? freshExisting : freshExisting + 1;
    const freshBase = fresh.body.includes(REVIEW_MARKER) ? fresh.body : CLAIM_PLACEHOLDER_BODY;
    return buildStampedBody(stripReviewClaimMarkers(freshBase), fresh.ledger, fresh.claims, claimSha, freshExpected, claimKey);
  };
  try {
    await publishStickyComment(client, repository, issueNumber, initialBody, buildFreshBody);
  } catch (error) {
    // Over-limit or mid-claim unreadable ledger: explicit failure with zero
    // consumption (nothing was written).
    if (isStickyLimitError(error) || isStickyLedgerUnreadableError(error)) {
      return emit({
        claimed: false,
        reviewsUsed: 0,
        reason: 'quota-unknown: sticky body would exceed the comment limit or the ledger became unreadable; fail-closed without occupying a slot',
        issueNumber,
      });
    }
    // Sanitized sticky-write diagnostics: resolve (never throw) with a
    // fail-closed reason that carries only http-<status>-<op>. No tokens,
    // headers, response bodies, or diffs are recorded.
    const info = classifyStickyWriteError(error);
    const label = info.status !== undefined ? `http-${info.status}-${info.op}` : `http-unknown-${info.op}`;
    return emit({
      claimed: false,
      reviewsUsed: existing,
      reason: `claim-failed: sticky write failed (${label}); fail-closed`,
      issueNumber,
      claimSha,
    });
  }
  // Phase 1: the claim counts only when a re-read proves the marker survived
  // the write (no truncation, no lost race). Otherwise fail closed.
  const verify = await readStickyLedger(client, repository.owner, repository.repo, issueNumber);
  if (!verify.ok) {
    return emit({
      claimed: false,
      reviewsUsed: expected,
      reason: 'quota-unknown: claim write unverifiable; sticky ledger unreadable after write',
      issueNumber,
      claimSha,
    });
  }
  if (claimKey) {
    if (!verify.claims.has(`${claimSha}:${claimKey}`)) {
      const current = verify.ledger.get(claimSha) ?? 0;
      return emit({
        claimed: false,
        reviewsUsed: current,
        reason: 'quota-unknown: claim marker missing after write; fail-closed',
        issueNumber,
        claimSha,
      });
    }
  } else {
    const current = verify.ledger.get(claimSha) ?? 0;
    if (current < expected) {
      return emit({
        claimed: false,
        reviewsUsed: current,
        reason: 'quota-unknown: claim count missing after write; fail-closed',
        issueNumber,
        claimSha,
      });
    }
  }
  return emit({ claimed: true, reviewsUsed: expected, reason: `claimed: head ${claimSha} now at ${expected}`, issueNumber, claimSha });
}

// Git commands must resolve repo-root-relative pathspecs regardless of the
// process working directory. The workflow invokes the runner with
// `working-directory: github_bot`, and a path like `app/src/...` would then be
// interpreted as `github_bot/app/src/...` (silently empty diff, exit code 0),
// failing every per-file review. Anchor once at the worktree root.
let cachedRepoRoot: string | undefined;
function repoRoot(): string {
  if (cachedRepoRoot === undefined) {
    cachedRepoRoot = execFileSync('git', ['rev-parse', '--show-toplevel'], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    }).trim();
  }
  return cachedRepoRoot;
}

export function defaultGit(args: string[]): string {
  return execFileSync('git', args, {
    cwd: repoRoot(),
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'ignore'],
    maxBuffer: 64 * 1024 * 1024,
  });
}

export function getChangedPaths(context: RunnerContext, mergeBaseSha: string, headSha: string): string[] {
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
): { diff: string; coverage: ReviewCoverage; fullDiff: string; fileDiffs: Map<string, string> } {
  const runGit = context.runGit ?? defaultGit;
  const diffs = collectFileDiffs(runGit, compareBaseSha, headSha, changedFiles);
  const reviewFiles = new Set(filterReviewDiffFiles(changedFiles));
  let originalLength = 0;
  let includedDiffCount = 0;
  for (const file of changedFiles) {
    const fileDiff = diffs.get(file) ?? '';
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
    fileDiffs: diffs,
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
  const responseText = JSON.stringify({ verdict: 'APPROVE', summary: 'Test review complete.', findings: [] });
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
  // Honest truncation proof for budget-truncated reviews (transport-complete
  // but over budget): reviewed vs total chars and comments. Absent when the
  // full content was reviewed. Publish and sticky render it; it never lifts
  // the INCONCLUSIVE ban on truncated content.
  truncation?: {
    shownChars: number;
    totalChars: number;
    shownComments: number;
    totalComments: number;
  };
  // P6 proof of the second-layer code read: default-branch paths reviewed
  // with shown/total chars. Absent when the second layer never ran.
  codeRefs?: Array<{
    path: string;
    shownChars: number;
    totalChars: number;
  }>;
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

// P6 second-layer code access: read-only file reads from the trusted
// default branch only (never a PR/issue-supplied ref, never executed).
// Paths are validated literally (no traversal, no absolute paths, no
// pathspec magic). F2: codeRefs use their own allowlist, independent of the
// PR diff-review filter (whose must-include exceptions would override its
// excludes). The denylist below is non-overridable and runs first; the
// positive allowlist then limits reads to text source/config extensions;
// the shared diff-review excludes remain as a final backstop. Returns
// undefined when anything is invalid or any fetch fails, in which case the
// caller keeps the first-round result.
// Denylist uses prefix matching so backup copies (google-services.json.bak,
// local.properties.txt, .env_vars) are denied as well.
const CODE_REF_DENY_BASENAME = /(^|\/)\.env[^/]*$|(^|\/)local\.properties[^/]*$|(^|\/)google-services\.json[^/]*$|(^|\/)googleservice-info\.plist[^/]*$|(^|\/)(credentials|secrets)\.json[^/]*$|(^|\/)service-account[^/]*$|(^|\/)keystore\.properties[^/]*$|(^|\/)\.(npmrc|pypirc)([./][^/]*)?$/i;
const CODE_REF_DENY_EXT = /\.(keystore|jks|key|pem|p12|pfx|asc|apk|aab|png|jpe?g|webp|gif|mp4|map|lock|sqlite3?|db|realm)$/i;
const CODE_REF_DENY_MINIFIED = /(^|\/)[^/]*\.min\.[^/]+$/i;
const CODE_REF_DENY_DIR = /(^|\/)(\.git|build|\.gradle|\.idea)(\/|$)/i;
const CODE_REF_DENY_KEYFILE = /[^/]*id_rsa[^/]*$|[^/]*id_ed25519[^/]*$/i;
const CODE_REF_ALLOW_EXT = /\.(kt|java|kts|xml|toml|yml|yaml|md|txt|gradle|pro)$/i;

function isCodeRefDenied(normalized: string): boolean {
  return CODE_REF_DENY_BASENAME.test(normalized)
    || CODE_REF_DENY_EXT.test(normalized)
    || CODE_REF_DENY_MINIFIED.test(normalized)
    || CODE_REF_DENY_DIR.test(normalized)
    || CODE_REF_DENY_KEYFILE.test(normalized);
}
export interface IssueCodeRef {
  path: string;
  content: string;
  truncated: boolean;
  totalChars: number;
}

export function isIssueCodeRefAllowed(rawPath: string): boolean {
  if (typeof rawPath !== 'string') return false;
  const trimmed = rawPath.trim();
  if (!trimmed || trimmed.length > 256) return false;
  if (trimmed.startsWith('/') || trimmed.startsWith('\\')) return false;
  const normalized = trimmed.replace(/\\/g, '/').replace(/^\.\//, '');
  if (!normalized) return false;
  const segments = normalized.split('/');
  if (segments.some((segment) => segment === '' || segment === '.' || segment === '..')) return false;
  if (/[:*?[\](){}!$`'"\\\s]/.test(normalized)) return false;
  if (/[\u0000-\u001f\u007f]/.test(normalized)) return false;
  // Denylist first (never overridable), then the positive allowlist, then
  // the shared diff-review excludes as a final backstop.
  if (isCodeRefDenied(normalized)) return false;
  if (!CODE_REF_ALLOW_EXT.test(normalized)) return false;
  return filterReviewDiffFiles([normalized]).length === 1;
}

export function resolveIssueCodeRef(ref: unknown): string | undefined {
  if (typeof ref !== 'string') return undefined;
  const trimmed = ref.trim();
  if (!trimmed || trimmed.length > 64) return undefined;
  // Defense in depth: the ref comes from trusted event metadata, but a
  // leading dash/dot/slash must never reach git as a potential option.
  if (/^[-+./]/.test(trimmed)) return undefined;
  if (!/^[A-Za-z0-9_.\-/]+$/.test(trimmed) || trimmed.includes('..')) return undefined;
  return trimmed;
}

export function fetchIssueCodeRefs(
  runGit: (args: string[]) => string,
  ref: unknown,
  paths: string[],
): IssueCodeRef[] | undefined {
  const branch = resolveIssueCodeRef(ref);
  if (!branch) return undefined;
  const out: IssueCodeRef[] = [];
  let total = 0;
  // The 10-file cap applies before allowlist filtering: a list whose first
  // 10 entries are all illegal skips the second layer (fail-closed).
  for (const raw of paths.slice(0, MAX_ISSUE_CODE_FILES)) {
    if (!isIssueCodeRefAllowed(raw)) {
      // Never log raw paths: they are model-controlled.
      console.warn('[PocketGuard] Discarded 1 issue code reference outside the allowlist.');
      continue;
    }
    const normalized = raw.trim().replace(/\\/g, '/').replace(/^\.\//, '');
    let text: string;
    try {
      text = runGit(['show', `${branch}:${normalized}`]);
    } catch {
      return undefined;
    }
    if (!text) return undefined;
    const redacted = redactForModel(text);
    const remaining = Math.max(0, MAX_ISSUE_CODE_TOTAL_CHARS - total);
    const budget = Math.min(MAX_ISSUE_CODE_CHARS_PER_FILE, remaining);
    const content = redacted.length > budget ? redacted.slice(0, budget) : redacted;
    out.push({ path: normalized, content, truncated: redacted.length > content.length, totalChars: redacted.length });
    total += content.length;
    if (total >= MAX_ISSUE_CODE_TOTAL_CHARS) break;
  }
  return out.length > 0 ? out : undefined;
}

// Deterministic BLOCK for issues (fail-closed NEEDS_CHANGES even when the
// issue review is incomplete): scan the full RAW content with the shared
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
  if (!available) return 'Report: unavailable (review report could not be generated or failed to upload, see Actions run log).';
  const runUrl = getReportRunUrl(env);
  if (!runUrl) return 'Report: unavailable (review report could not be generated or failed to upload, see Actions run log).';
  return `Report: [run](${runUrl}) (artifact: ${REVIEW_REPORT_ARTIFACT_NAME}, includes review-report.md / review-report.json, retained for 30 days).`;
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
    reportJson = {
      kind: 'issue',
      verdict: issue.verdict,
      issueNumber: issue.issueNumber,
      fingerprint: issue.fingerprint,
      roles: [{ role: 'architecture', verdict: issue.verdict, findings: [] }],
      coverage: {
        complete: issue.commentsComplete === true,
        commentsComplete: issue.commentsComplete,
      },
      findings: [],
      ...(issue.truncation ? { truncation: issue.truncation } : {}),
      ...(issue.codeRefs ? { codeRefs: issue.codeRefs } : {}),
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
      ...(pr.synthesis ? { synthesis: pr.synthesis } : {}),
      time,
    };
  }
  // Redact the serialized report so pasted credentials never persist in
  // cleartext (single source of truth: redactForModel).
  const redactedJsonText = `${redactForModel(JSON.stringify(reportJson, null, 2))}\n`;
  const redactedMd = redactForModel([
    '# PocketGuard Review Report',
    '',
    `Verdict: ${(reportJson as { verdict?: string }).verdict ?? 'INCONCLUSIVE'}`,
    `Fingerprint: \`${(reportJson as { fingerprint?: string }).fingerprint ?? ''}\``,
    `Time: ${time}`,
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

// Issue-side merge: deterministic title rules plus AI suggestions converged
// through the issue allowlist. Human-only labels (gate:*/verified statuses)
// are discarded here; callers treat hadIllegal exactly like hadUnknown
// (rules-only tags, never APPROVE). Deterministic labels are computed
// without any AI input; the LLM only supplements labels no deterministic
// rule can produce (see the taxonomy in label_manager.ts).
function mergeIssueRulesWithAiSuggestions(
  rulesLabels: string[],
  aiRawLabels: string[],
): { merged: string[]; hadIllegal: boolean } {
  // sanitizeIssueAiSuggestions counts non-string entries as discarded, so no
  // pre-filter here: every illegal entry (unknown, human-only, non-string)
  // is counted. (Publish stays stricter: a non-string suggestedLabels entry
  // invalidates the whole artifact fail-closed.)
  const filtered = sanitizeIssueAiSuggestions(Array.isArray(aiRawLabels) ? aiRawLabels : []);
  if (filtered.discardedCount > 0) {
    // Never log raw label values (model-controlled); record only the count.
    console.warn(`[PocketGuard] Discarded ${filtered.discardedCount} issue AI label(s) outside the convergence allowlist.`);
  }
  return { merged: sanitizeLabels([...rulesLabels, ...filtered.kept]), hadIllegal: filtered.discardedCount > 0 };
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
      const note = ' (Review report could not be generated; downgraded to INCONCLUSIVE, see Actions run log.)';
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
    Object.keys(raw).some((key) => !['verdict', 'issueNumber', 'title', 'tags', 'summary', 'suggestedLabels', 'fingerprint', 'commentsComplete', 'truncation', 'codeRefs'].includes(key)) ||
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
  // Optional honest-truncation proof (budget-truncated reviews). Malformed
  // truncation never validates; a present proof does not lift the
  // INCONCLUSIVE ban (review mode never APPROVEs truncated content).
  let truncation: RunnerIssueOutput['truncation'];
  if (raw.truncation !== undefined) {
    if (!raw.truncation || typeof raw.truncation !== 'object' || Array.isArray(raw.truncation)) return undefined;
    const proof = raw.truncation as Record<string, unknown>;
    if (
      Object.keys(proof).some((key) => !['shownChars', 'totalChars', 'shownComments', 'totalComments'].includes(key)) ||
      !Number.isSafeInteger(proof.shownChars) || Number(proof.shownChars) < 0 ||
      !Number.isSafeInteger(proof.totalChars) || Number(proof.totalChars) < 0 ||
      !Number.isSafeInteger(proof.shownComments) || Number(proof.shownComments) < 0 ||
      !Number.isSafeInteger(proof.totalComments) || Number(proof.totalComments) < 0 ||
      Number(proof.shownChars) > Number(proof.totalChars) ||
      Number(proof.shownComments) > Number(proof.totalComments)
    ) return undefined;
    truncation = {
      shownChars: Number(proof.shownChars),
      totalChars: Number(proof.totalChars),
      shownComments: Number(proof.shownComments),
      totalComments: Number(proof.totalComments),
    };
  }
  // The producer never emits a truncation proof on an APPROVE artifact
  // (truncated content cannot approve); a forged combination is rejected so
  // publish can never show APPROVE next to a Truncated line.
  if (truncation && raw.verdict === 'APPROVE') return undefined;
  // Optional second-layer proof: validated paths with sane counts. Malformed
  // proofs reject; a present proof never lifts any gate.
  let codeRefs: RunnerIssueOutput['codeRefs'];
  if (raw.codeRefs !== undefined) {
    if (!Array.isArray(raw.codeRefs) || raw.codeRefs.length === 0 || raw.codeRefs.length > MAX_ISSUE_CODE_FILES) return undefined;
    const refs: NonNullable<RunnerIssueOutput['codeRefs']> = [];
    let shownTotal = 0;
    for (const entry of raw.codeRefs) {
      if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return undefined;
      const ref = entry as Record<string, unknown>;
      if (
        Object.keys(ref).some((key) => !['path', 'shownChars', 'totalChars'].includes(key)) ||
        typeof ref.path !== 'string' || !isIssueCodeRefAllowed(ref.path) ||
        !Number.isSafeInteger(ref.shownChars) || Number(ref.shownChars) < 0 ||
        !Number.isSafeInteger(ref.totalChars) || Number(ref.totalChars) < 0 ||
        Number(ref.shownChars) > Number(ref.totalChars) ||
        Number(ref.shownChars) > MAX_ISSUE_CODE_CHARS_PER_FILE
      ) return undefined;
      shownTotal += Number(ref.shownChars);
      refs.push({ path: ref.path, shownChars: Number(ref.shownChars), totalChars: Number(ref.totalChars) });
    }
    if (shownTotal > MAX_ISSUE_CODE_TOTAL_CHARS) return undefined;
    codeRefs = refs;
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
    ...(truncation ? { truncation } : {}),
    ...(codeRefs ? { codeRefs } : {}),
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
// split: the redacted copy (cleanForModel) feeds the model,
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
// fullTitle/fullBody/fullComments stay full REDACTED for honest-truncation
// counts and coverage, rawTitle/rawBody/rawComments (+ids/updated_ats) stay full RAW for hashing
// and scanning so a tail change past any cap still alters the fingerprint (no
// tail collision) and two secrets sharing one mask hash differently (no mask
// collision). Only the hash is persisted; raw text never leaves the
// hash/scan path.
// A webhook issue_comment body may be merged as a minimal input after the
// same bot filter, but the result stays incomplete and never lifts the
// APPROVE ban. `fetchComplete` distinguishes active budget truncation from
// transport incompleteness.
async function buildIssueContext(
  context: RunnerContext,
  repository: { owner: string; repo: string } | undefined,
  issueNumber: number,
  title: string,
  body: string,
  issueMeta?: { updatedAt?: string | null; id?: number | null },
): Promise<{ title: string; body: string; comments: string[]; commentsComplete: boolean; fullFingerprint: string; fetchComplete: boolean; fullTitle: string; fullBody: string; fullComments: string[]; rawTitle: string; rawBody: string; rawComments: string[]; rawCommentIds: Array<number | null>; rawCommentUpdatedAts: Array<string | null>; rawIssueUpdatedAt?: string; rawIssueId?: number }> {
  const comments: string[] = [];
  const fullComments: string[] = [];
  const rawComments: string[] = [];
  const rawCommentIds: Array<number | null> = [];
  const rawCommentUpdatedAts: Array<string | null> = [];
  let commentsComplete = true;
  let fetchComplete = true;
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
  // Full REDACTED-but-unsliced content for honest-truncation counts. Any cut by the
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
    commentsComplete = false;
  }
  if (assembledLength() > MAX_ISSUE_CONTEXT_LENGTH) {
    safeBody = safeBody.slice(0, Math.max(0, MAX_ISSUE_CONTEXT_LENGTH - safeTitle.length));
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
// human issue comments each take one architecture single-turn over the issue
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
  // miss credentials. The model-bound copies stay REDACTED.
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
  if (!built.commentsComplete) {
    // Honest truncation: the transport read completed but the content
    // exceeded the review budget (single-field caps or the total budget), so
    // only a prefix was reviewed. Report exact shown/total counts; the
    // review stays INCONCLUSIVE (deterministic BLOCK may NEEDS_CHANGES).
    const shownChars = built.title.length + built.body.length + built.comments.reduce((sum, c) => sum + c.length, 0);
    const totalChars = built.fullTitle.length + built.fullBody.length + built.fullComments.reduce((sum, c) => sum + c.length, 0);
    const truncation = {
      shownChars,
      totalChars,
      shownComments: built.comments.length,
      totalComments: built.fullComments.length,
    };
    const output: RunnerIssueOutput = {
      verdict: deterministicBlock ? 'NEEDS_CHANGES' : 'INCONCLUSIVE',
      issueNumber,
      title: safeString(title),
      tags: sanitizeLabels([...issueRulesTags(title), 'status:needs-decision']),
      summary: deterministicBlock
        ? `deterministic checks found a blocking finding; showing ${shownChars}/${totalChars} chars across ${truncation.shownComments}/${truncation.totalComments} comments; review freshness could not be fully verified.`
        : `the issue content exceeded the review budget and was truncated (showing ${shownChars}/${totalChars} chars across ${truncation.shownComments}/${truncation.totalComments} comments); review freshness could not be verified.`,
      suggestedLabels: [],
      fingerprint,
      commentsComplete: false,
      truncation,
    };
    saveIssueOutput(output, context);
    return output;
  }
  const restoreFetch = installOpenAIStub(env);
  try {
    const { triageIssue, diagnoseIssue } = await import('./orchestrator');
    const triaged = await triageIssue({
      input: { title: built.title, body: built.body, comments: built.comments },
      env,
      allowedOrigins: parseAllowedOrigins(env),
    });
    // P6 second layer: when the first round names readable files and the
    // verdict is not already fixed, fetch them read-only from the trusted
    // default branch and diagnose with code. The final verdict is the
    // stricter of both rounds; labels union before the allowlist gate.
    // Anything invalid (bad ref/paths, fetch failure, diagnosis failure)
    // keeps the first-round result.
    let roundVerdict = triaged.verdict;
    let roundSummary = triaged.summary;
    let aiRaw = Array.isArray(triaged.suggestedLabels) ? triaged.suggestedLabels : [];
    let codeRefsProof: RunnerIssueOutput['codeRefs'];
    if (!deterministicBlock && triaged.verdict !== 'NEEDS_CHANGES' && triaged.codeRefs.length > 0) {
      const runGit = context.runGit ?? defaultGit;
      const snippets = fetchIssueCodeRefs(runGit, event.repository?.default_branch, triaged.codeRefs);
      if (snippets) {
        codeRefsProof = snippets.map((snippet) => ({
          path: snippet.path,
          shownChars: snippet.content.length,
          totalChars: snippet.totalChars,
        }));
        const diagnosed = await diagnoseIssue({
          input: {
            title: built.title,
            body: built.body,
            comments: built.comments,
            firstRoundVerdict: triaged.verdict,
            firstRoundSummary: triaged.summary,
            codeRefs: snippets.map((snippet) => ({
              path: snippet.path,
              content: snippet.content,
              truncated: snippet.truncated,
              totalChars: snippet.totalChars,
            })),
          },
          env,
          allowedOrigins: parseAllowedOrigins(env),
        });
        const strictness: Record<RunnerVerdict, number> = { NEEDS_CHANGES: 2, INCONCLUSIVE: 1, APPROVE: 0 };
        // A failed diagnosis (ok:false) never changes the first-round
        // result; only a successful diagnosis combines stricter-wins.
        if (!diagnosed.ok) {
          // Keep round one entirely.
        } else if (strictness[diagnosed.verdict] > strictness[roundVerdict]) {
          roundVerdict = diagnosed.verdict;
          if (diagnosed.summary) roundSummary = diagnosed.summary;
        } else if (diagnosed.verdict === roundVerdict && diagnosed.summary) {
          roundSummary = diagnosed.summary;
        }
        if (diagnosed.ok && Array.isArray(diagnosed.suggestedLabels)) {
          aiRaw = [...aiRaw, ...diagnosed.suggestedLabels.filter((entry): entry is string => typeof entry === 'string')];
        }
      }
    }
    const { merged, hadIllegal } = mergeIssueRulesWithAiSuggestions(issueRulesTags(title), aiRaw);
    // Deterministic needs-decision for non-APPROVE cannot be cleared by AI.
    // Phase 2 (H): deterministic BLOCK forces NEEDS_CHANGES (never APPROVE).
    let verdict = roundVerdict;
    if (deterministicBlock) verdict = 'NEEDS_CHANGES';
    if (hadIllegal && verdict === 'APPROVE') verdict = 'INCONCLUSIVE';
    const tags = hadIllegal
      ? sanitizeLabels([...issueRulesTags(title), ...(verdict !== 'APPROVE' ? ['status:needs-decision'] : [])])
      : sanitizeLabels([...merged, ...(verdict !== 'APPROVE' ? ['status:needs-decision'] : [])]);
    const output: RunnerIssueOutput = {
      verdict,
      issueNumber,
      title: safeString(title),
      tags,
      summary: safeString(roundSummary),
      suggestedLabels: aiRaw.filter((entry): entry is string => typeof entry === 'string'),
      fingerprint,
      commentsComplete: built.commentsComplete,
      ...(codeRefsProof ? { codeRefs: codeRefsProof } : {}),
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
        const t0 = Date.now();
        const effectiveMs = resolveEffectiveReviewDeadlineMs(env, t0);
        let remainingMs = effectiveMs;
        const jobStartedMs = resolveJobStartedMs(env);
        if (jobStartedMs !== undefined) {
          const jobRemainingMs = REVIEW_JOB_TIMEOUT_MS - (t0 - jobStartedMs) - REVIEW_JOB_UPLOAD_SKEW_MS;
          remainingMs = Math.min(effectiveMs, jobRemainingMs);
        }
        const timeoutMs = resolvePerChunkTimeoutMs(env, remainingMs, 1);
        const needMs = timeoutMs + CHUNK_DEADLINE_SAVE_MARGIN_MS;
        if (remainingMs < needMs) {
          const runnerRoles: RunnerReviewOutput['roles'] = ROLE_NAMES.map((role) => ({
            role,
            modelUsed: 'not-run',
            verdict: 'INCONCLUSIVE',
            findings: [],
          }));
          output = {
            verdict: 'INCONCLUSIVE',
            roles: runnerRoles,
            coverage: reviewDiff.coverage,
            deterministicViolations: scan.violations,
            pullRequestNumber: target.pullRequest.number,
            baseSha,
            headSha,
            headRepository: target.pullRequest.head.repo?.full_name ?? '',
            areaLabels: resolveAreaLabelsFromPaths(changedFiles),
            changedFiles,
            changedFilesComplete,
            suggestedLabels: [],
          };
        } else {
          const { orchestrateReview, synthesizeReview, SYNTHESIS_DIFF_EXCERPT_CHARS, SYNTHESIS_MAX_APPENDED_FINDINGS, selectUsableBlockFindings } = await import('./orchestrator');
          const restoreFetch = installOpenAIStub(env);
          try {
            // P4 two-wave budget: the first wave runs the 3 parallel roles,
            // the second wave runs the architecture synthesis guard. F3: the
            // save margin is subtracted BEFORE splitting (margin-first), so a
            // budget that fits two waves is never misjudged as single-wave;
            // the old allocate-then-add-margin formula could never fit below
            // the per-wave ceiling. A skipped synthesis never stores APPROVE.
            const waveBudget = remainingMs - CHUNK_DEADLINE_SAVE_MARGIN_MS;
            const waveTimeout = waveBudget > 0 ? resolvePerChunkTimeoutMs(env, waveBudget, 2) : 1;
            const singleTimeout = resolvePerChunkTimeoutMs(env, remainingMs, 1);
            const canTwoWaves = waveBudget > 0
              && waveTimeout >= MIN_VIABLE_PER_CHUNK_TIMEOUT_MS
              && waveTimeout * 2 + CHUNK_DEADLINE_SAVE_MARGIN_MS <= remainingMs;
            const firstWaveTimeout = canTwoWaves ? waveTimeout : singleTimeout;
            const orchestrated = await orchestrateReview({
              changedFiles,
              diff: redactForModel(reviewDiff.diff),
              coverage: reviewDiff.coverage,
              deterministicViolations: scan.violations,
              env,
              allowedOrigins: parseAllowedOrigins(env),
              timeoutMs: firstWaveTimeout,
            });
            const mergedSuggested = [...new Set(orchestrated.roles.flatMap((role) => role.suggestedLabels))];
            const runnerRoles: RunnerReviewOutput['roles'] = orchestrated.roles.map((role) => ({
              role: role.role as ReviewRoleName,
              modelUsed: role.modelUsed,
              verdict: role.verdict as RunnerVerdict,
              findings: role.findings.map((finding) => ({ ...finding })),
              ...(typeof role.summary === 'string' ? { summary: role.summary } : {}),
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
            // P4 synthesis guard: a first-round APPROVE is stored only after
            // the architecture synthesis confirms it (or tightens with
            // evidenced BLOCK findings). Any other first-round verdict is
            // already strict enough and skips synthesis. A failed synthesis
            // never stores APPROVE.
            let synthesis: RunnerReviewOutput['synthesis'];
            if (reviewVerdict === 'APPROVE' && canTwoWaves) {
              const redactedDiff = redactForModel(reviewDiff.diff);
              const excerpt = redactedDiff.length > SYNTHESIS_DIFF_EXCERPT_CHARS
                ? redactedDiff.slice(0, SYNTHESIS_DIFF_EXCERPT_CHARS)
                : redactedDiff;
              const synth = await synthesizeReview({
                firstRound: orchestrated,
                diffExcerpt: excerpt,
                diffTruncated: redactedDiff.length > excerpt.length,
                originalLength: reviewDiff.coverage.originalLength,
                env,
                allowedOrigins: parseAllowedOrigins(env),
                timeoutMs: waveTimeout,
              });
              if (!synth.ok) {
                console.warn('[PocketGuard] Synthesis failed; downgrading review verdict to INCONCLUSIVE.');
                reviewVerdict = 'INCONCLUSIVE';
                synthesis = {
                  modelUsed: synth.modelUsed,
                  verdict: 'INCONCLUSIVE',
                  ...(synth.summary ? { summary: synth.summary } : {}),
                };
              } else if (synth.verdict === 'APPROVE' &&
                !synth.findings.some((finding) => finding.severity === 'BLOCK')) {
                synthesis = {
                  modelUsed: synth.modelUsed,
                  verdict: 'APPROVE',
                  ...(synth.summary ? { summary: synth.summary } : {}),
                };
              } else if (synth.verdict === 'NEEDS_CHANGES') {
                // Tighten only with evidenced BLOCK findings: the evidence
                // quote must literally appear in the reviewed excerpt or a
                // deterministic violation, otherwise the dissent is
                // unverifiable and the review falls back to INCONCLUSIVE.
                // An APPROVE verdict carrying BLOCK findings is likewise
                // contradictory and can never be stored.
                const violationTexts = orchestrated.deterministicViolations.map(
                  (violation) => `${violation.ruleId} ${violation.message} ${violation.file ?? ''}`,
                );
                const usable = selectUsableBlockFindings(synth.findings, synth.evidences, excerpt, violationTexts)
                  .slice(0, SYNTHESIS_MAX_APPENDED_FINDINGS)
                  .map((finding) => ({ ...finding }));
                const architectureRole = runnerRoles.find((role) => role.role === 'architecture');
                if (architectureRole && usable.length > 0) {
                  // Attach the evidenced BLOCK findings to the architecture
                  // role (and flip its verdict) so the deterministic
                  // recalculation below and at publish converge to
                  // NEEDS_CHANGES.
                  architectureRole.findings.push(...usable);
                  architectureRole.verdict = 'NEEDS_CHANGES';
                  reviewVerdict = 'NEEDS_CHANGES';
                } else {
                  reviewVerdict = 'INCONCLUSIVE';
                }
                synthesis = {
                  modelUsed: synth.modelUsed,
                  verdict: reviewVerdict,
                  ...(synth.summary ? { summary: synth.summary } : {}),
                };
              } else {
                reviewVerdict = 'INCONCLUSIVE';
                synthesis = {
                  modelUsed: synth.modelUsed,
                  verdict: 'INCONCLUSIVE',
                  ...(synth.summary ? { summary: synth.summary } : {}),
                };
              }
            } else {
              // F3: an unverified APPROVE is not stored. When synthesis is
              // skipped (budget or non-APPROVE first round) an APPROVE first
              // round converges to INCONCLUSIVE; other verdicts are already
              // strict enough and pass through unchanged.
              if (reviewVerdict === 'APPROVE') {
                console.warn('[PocketGuard] Synthesis skipped; downgrading unverified APPROVE to INCONCLUSIVE.');
                reviewVerdict = 'INCONCLUSIVE';
              }
              synthesis = { modelUsed: 'not-run', verdict: 'INCONCLUSIVE', skipped: true };
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
              synthesis,
            };
          } finally {
            restoreFetch();
          }
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

export function validateReviewOutput(value: unknown): RunnerReviewOutput | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const raw = value as Record<string, unknown>;
  const verdicts = new Set(['APPROVE', 'NEEDS_CHANGES', 'INCONCLUSIVE']);
  if (
    Object.keys(raw).some((key) => ![
      'verdict', 'pullRequestNumber', 'baseSha', 'headSha', 'headRepository', 'roles', 'coverage',
      'deterministicViolations', 'areaLabels', 'changedFiles', 'changedFilesComplete', 'suggestedLabels',
      'synthesis',
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
  let synthesis: RunnerReviewOutput['synthesis'];
  if (raw.synthesis !== undefined) {
    if (!raw.synthesis || typeof raw.synthesis !== 'object' || Array.isArray(raw.synthesis)) return undefined;
    const synth = raw.synthesis as Record<string, unknown>;
    if (
      Object.keys(synth).some((key) => !['modelUsed', 'verdict', 'summary', 'skipped'].includes(key)) ||
      typeof synth.modelUsed !== 'string' || !synth.modelUsed.trim() ||
      !verdicts.has(String(synth.verdict)) ||
      (synth.summary !== undefined && typeof synth.summary !== 'string') ||
      (synth.skipped !== undefined && typeof synth.skipped !== 'boolean')
    ) return undefined;
    // F3: a skipped synthesis never confirms anything, so a stored
    // APPROVE paired with skipped:true is a forged or pre-fix artifact and
    // is rejected (fail-closed to INCONCLUSIVE at publish).
    if (String(raw.verdict) === 'APPROVE' && synth.skipped === true) return undefined;
    synthesis = {
      modelUsed: safeString(synth.modelUsed, 200),
      verdict: synth.verdict as RunnerVerdict,
      ...(typeof synth.summary === 'string' ? { summary: safeString(synth.summary, 2000) } : {}),
      ...(typeof synth.skipped === 'boolean' ? { skipped: synth.skipped } : {}),
    };
  }
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
      Object.keys(role).some((key) => !['role', 'modelUsed', 'verdict', 'findings', 'summary'].includes(key)) ||
      !ROLE_NAMES.includes(role.role as ReviewRoleName) || typeof role.modelUsed !== 'string' || !role.modelUsed.trim() ||
      !verdicts.has(String(role.verdict)) || !Array.isArray(role.findings) ||
      (role.summary !== undefined && typeof role.summary !== 'string')
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
      ...(typeof role.summary === 'string' ? { summary: safeString(role.summary, 2000) } : {}),
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
  // Fail-closed in the dangerous direction only: a stored verdict looser
  // than the recalculation (e.g. forged APPROVE over BLOCK roles) is
  // rejected. A stored verdict stricter than the recalculation (label-gate
  // or synthesis downgrades to INCONCLUSIVE/NEEDS_CHANGES) is accepted as
  // stored so the downgrade — and its reason — survives publish.
  const strictness: Record<RunnerVerdict, number> = { NEEDS_CHANGES: 2, INCONCLUSIVE: 1, APPROVE: 0 };
  if (strictness[raw.verdict as RunnerVerdict] < strictness[calculatedVerdict]) return undefined;

  return {
    verdict: raw.verdict as RunnerVerdict,
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
    ...(synthesis ? { synthesis } : {}),
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
  return `${src.slice(0, maxLength)}… (${src.length - maxLength} chars omitted)`;
}

// Phase 3 (P2 #3) sticky helpers: truncate raw first, then escape, and
// account post-escape lengths so escape expansion (e.g. `*` -> `\*`) never
// overflows the total budget.
function stickySeverityRank(severity: string): number {
  if (severity === 'BLOCK') return 0;
  if (severity === 'WARN') return 1;
  return 2;
}

// Unified truncate->escape field: raw is cut to cap first, then escaped, and
// callers account the escaped length. The omission note uses full-width
// parentheses so escaping never litters it with backslashes.
export function stickyField(raw: unknown, cap: number): string {
  const src = typeof raw === 'string' ? raw : '';
  const budget = Math.max(0, Math.floor(cap));
  if (src.length <= budget) return escapeMarkdown(src);
  return `${escapeMarkdown(src.slice(0, budget))}… (${src.length - budget} chars omitted)`;
}

function formatStickyFileList(label: string, files: string[]): string {
  const total = files.length;
  if (total === 0) return `${label}: none`;
  const shownCount = Math.min(total, MAX_STICKY_FILE_LIST_SHOWN);
  const shown = files.slice(0, shownCount).map((file) => stickyField(file, MAX_STICKY_FILE_NAME_LENGTH));
  const omitted = total - shownCount;
  const counts = ` (${total} total, ${shownCount} shown, ${omitted} omitted)`;
  if (omitted > 0) {
    return `${label}${counts}: ${shown.join(', ')}… (${omitted} more file(s) omitted, full list in artifact)`;
  }
  return `${label}${counts}: ${shown.join(', ')}`;
}

function hardTruncateSticky(body: string, limit: number): string {
  if (body.length <= limit) return body;
  // Visible-only last resort (never used on a metadata-carrying ledger path;
  // see publishStickyComment). Counts live only in the sticky ledger and are
  // never recoverable from the report artifact.
  const note = '… (long review details omitted, full details in artifact; counts in this comment prevail)';
  const keep = Math.max(0, limit - note.length);
  return `${body.slice(0, keep)}${note}`;
}

// Pre-send cap for metadata-free plain text only. Ledger paths must precheck
// with buildStampedBodyWithLength/assertStampedBodyFits and fail closed
// instead of calling this: GitHub rejects bodies past 65536 chars, and a
// blind cut could drop ledger markers and silently lose counts.
export function enforceGithubCommentLimit(body: string): string {
  if (typeof body !== 'string') return body;
  return hardTruncateSticky(body, GITHUB_COMMENT_HARD_LIMIT);
}

// metadataReserve (Phase 1): expected marker-suffix length from
// estimateStickyMetadataLength. The visible-findings budget reserves
// max(1500, metadataReserve + margin) so visible + markers still fits while
// header metadata and the tail counts line are always kept — only the
// summary/findings list is trimmed, never metadata.
export function reviewComment(output: RunnerReviewOutput, appliedLabels: string[] = [], reportLine?: string, metadataReserve = 0): string {
  const updatedAt = new Date().toISOString();
  const omittedFiles = Array.isArray(output.coverage?.omittedFiles)
    ? output.coverage.omittedFiles.filter((file): file is string => typeof file === 'string')
    : [];
  const truncatedFiles = Array.isArray(output.coverage?.truncatedFiles)
    ? output.coverage.truncatedFiles.filter((file): file is string => typeof file === 'string')
    : [];
  const labelsLine = appliedLabels.length > 0
    ? appliedLabels.filter((label): label is string => typeof label === 'string')
      .map((label) => stickyField(label, MAX_STICKY_FILE_NAME_LENGTH)).join(', ')
    : 'none';
  const roleVerdicts = Array.isArray(output.roles) && output.roles.length > 0
    ? output.roles.map((role) => `${role.role}=${role.verdict}`).join(', ')
    : 'none';
  // PR reviews are always single-segment: one visible diff with
  // truncation/omission recorded in coverage.
  const prChunkLine = 'Segments: single segment';
  const headerLines = [
    REVIEW_MARKER,
    '',
    '## PocketGuard Review',
    '',
    `**Verdict: ${output.verdict}**`,
    `Reviewed head SHA: \`${output.headSha}\``,
    `Reviewed base SHA: \`${output.baseSha}\``,
    `Label decision: ${labelsLine}`,
    `Updated: ${updatedAt}`,
    reportLine ?? formatReportLine(process.env, false),
    coverageSummary(output.coverage),
    formatStickyFileList('Omitted files', omittedFiles),
    formatStickyFileList('Truncated files', truncatedFiles),
    `Role verdicts: ${roleVerdicts}`,
    prChunkLine,
    '',
  ];

  const summaryLines: string[] = ['### Summary'];
  const architecture = output.roles?.find((r) => r.role === 'architecture');
  const architectureSummary = typeof architecture?.summary === 'string' ? architecture.summary.trim() : '';
  const otherRoles = (output.roles ?? []).filter(
    (r) => r.role !== 'architecture' && typeof r.summary === 'string' && r.summary.trim().length > 0,
  );
  if (architectureSummary && otherRoles.length === 0) {
    summaryLines.push(truncateStickyText(architectureSummary, MAX_STICKY_SUMMARY_LENGTH));
  } else if (architectureSummary && otherRoles.length > 0) {
    summaryLines.push(`- **architecture**: ${truncateStickyText(architectureSummary, MAX_STICKY_SUMMARY_LENGTH)}`);
    for (const r of otherRoles) {
      summaryLines.push(`- **${r.role}**: ${truncateStickyText(r.summary!.trim(), MAX_STICKY_SUMMARY_LENGTH)}`);
    }
  } else if (otherRoles.length > 0) {
    for (const r of otherRoles) {
      summaryLines.push(`- **${r.role}**: ${truncateStickyText(r.summary!.trim(), MAX_STICKY_SUMMARY_LENGTH)}`);
    }
  } else {
    summaryLines.push('No summary provided.');
  }

  // Candidate pools: all findings/violations sorted by BLOCK>WARN>SUGGESTION
  // with stable original order within a severity, then capped to 60/20. Totals
  // below stay over the full sets so the tail omission counts are honest.
  const totalFindings = Array.isArray(output.roles)
    ? output.roles.reduce((sum, role) => sum + (Array.isArray(role.findings) ? role.findings.length : 0), 0)
    : 0;
  const totalViolations = Array.isArray(output.deterministicViolations) ? output.deterministicViolations.length : 0;
  interface PooledFinding {
    severity: string;
    file?: string;
    line?: number;
    issue: string;
    suggestion?: string;
    role: string;
    order: number;
  }
  const allFindings: PooledFinding[] = [];
  if (Array.isArray(output.roles)) {
    output.roles.forEach((role, roleIdx) => {
      const list = Array.isArray(role.findings) ? role.findings : [];
      list.forEach((finding, findingIdx) => {
        allFindings.push({
          severity: finding.severity,
          file: finding.file,
          line: finding.line,
          issue: finding.issue,
          suggestion: finding.suggestion,
          role: role.role,
          order: roleIdx * 1000000 + findingIdx,
        });
      });
    });
  }
  const poolFindings = [...allFindings]
    .sort((left, right) => stickySeverityRank(left.severity) - stickySeverityRank(right.severity) || left.order - right.order)
    .slice(0, MAX_STICKY_FINDING_POOL_SIZE);
  interface PooledViolation {
    severity: string;
    ruleId: string;
    message: string;
    order: number;
  }
  const allViolations: PooledViolation[] = Array.isArray(output.deterministicViolations)
    ? output.deterministicViolations.map((violation, idx) => ({
      severity: violation.severity,
      ruleId: violation.ruleId,
      message: violation.message,
      order: idx,
    }))
    : [];
  const poolViolations = [...allViolations]
    .sort((left, right) => stickySeverityRank(left.severity) - stickySeverityRank(right.severity) || left.order - right.order)
    .slice(0, MAX_STICKY_VIOLATION_POOL_SIZE);
  // Incremental fill under the total budget: each entry is truncated->escaped
  // first and accounted post-escape; over budget stops (never a second
  // comment). The reserve keeps room for the tail counts line plus the marker
  // suffix stamped later, so metadata always survives and only the
  // summary/findings list shrinks.
  const reserveInput = typeof metadataReserve === 'number' && Number.isFinite(metadataReserve)
    ? Math.max(0, Math.floor(metadataReserve))
    : 0;
  const TAIL_RESERVE = Math.max(1500, reserveInput + STICKY_TAIL_MARGIN);
  const lines: string[] = [
    ...headerLines,
    ...summaryLines,
    '',
    `### Findings & Recommendations (total ${totalFindings}, candidate ${poolFindings.length}, prioritized by BLOCK>WARN>SUGGESTION, preserving order within severity)`,
  ];
  const joinedLength = (entries: string[]): number => entries.join('\n').length + 1;
  let shownFindings = 0;
  for (const finding of poolFindings) {
    const severity = finding.severity === 'BLOCK' || finding.severity === 'WARN' || finding.severity === 'SUGGESTION'
      ? finding.severity
      : 'WARN';
    const role = typeof finding.role === 'string' && finding.role ? finding.role : 'architecture';
    const location = typeof finding.file === 'string' && finding.file
      ? ` (${stickyField(finding.file, MAX_STICKY_FILE_NAME_LENGTH)}${typeof finding.line === 'number' && Number.isInteger(finding.line) && finding.line >= 1 ? `:${finding.line}` : ''})`
      : '';
    const main = `- **${severity}** [${role}]${location}: ${stickyField(finding.issue, MAX_STICKY_ITEM_LENGTH)}`;
    const extra = typeof finding.suggestion === 'string' && finding.suggestion
      ? `  - Recommendation: ${stickyField(finding.suggestion, MAX_STICKY_ITEM_LENGTH)}`
      : undefined;
    const probe = extra ? [...lines, main, extra] : [...lines, main];
    if (joinedLength(probe) + TAIL_RESERVE > MAX_STICKY_TOTAL_LENGTH) break;
    lines.push(main);
    if (extra) lines.push(extra);
    shownFindings += 1;
  }
  lines.push(`### Deterministic Checks (total ${totalViolations}, candidate ${poolViolations.length})`);
  let shownViolations = 0;
  for (const violation of poolViolations) {
    const severity = violation.severity === 'BLOCK' || violation.severity === 'WARN' ? violation.severity : 'WARN';
    const main = `- **${severity} ${stickyField(violation.ruleId, MAX_STICKY_ITEM_LENGTH)}**: ${stickyField(violation.message, MAX_STICKY_ITEM_LENGTH)}`;
    if (joinedLength([...lines, main]) + TAIL_RESERVE > MAX_STICKY_TOTAL_LENGTH) break;
    lines.push(main);
    shownViolations += 1;
  }
  const omittedFindings = totalFindings - shownFindings;
  const omittedViolations = totalViolations - shownViolations;
  lines.push(
    '',
    `Total ${totalFindings} finding(s), ${shownFindings} shown, ${omittedFindings} omitted; total ${totalViolations} deterministic violation(s), ${shownViolations} shown, ${omittedViolations} omitted.`,
    `Full review details available in artifact \`${REVIEW_REPORT_ARTIFACT_NAME}\`; excess review content is retained in artifact only. Counts in this comment ledger prevail and are not recovered from artifact.`,
  );
  const body = `${lines.join('\n')}\n`;
  if (body.length <= MAX_STICKY_TOTAL_LENGTH) return body;
  return hardTruncateSticky(body, MAX_STICKY_TOTAL_LENGTH);
}

function inconclusiveComment(reason: string, reportLine?: string): string {
  return [
    REVIEW_MARKER,
    '',
    '## PocketGuard Review',
    '',
    '**Verdict: INCONCLUSIVE**',
    `**Review result unavailable: ${safeString(reason)}**`,
    `Updated: ${new Date().toISOString()}`,
    reportLine ?? formatReportLine(process.env, false),
    SAFE_MESSAGE,
    '',
  ].join('\n');
}

// S5 issue sticky: one comment per issue, edited in place (never a second
// comment). Carries verdict, summary, label decision, updated time, and the
// revision fingerprint over title/body/comments so readers can tell whether
// the published result matches the current issue content. Budget-truncated
// reviews carry an honest truncation line with shown/total counts;
// summaries are bounded with omission notes.
export function issueReviewComment(output: RunnerIssueOutput, appliedLabels: string[], reportLine?: string): string {
  const updatedAt = new Date().toISOString();
  const truncationLine = output.truncation
    ? `Truncated: showing ${output.truncation.shownChars}/${output.truncation.totalChars} chars, ${output.truncation.shownComments}/${output.truncation.totalComments} comments (${output.truncation.totalChars - output.truncation.shownChars} chars omitted)`
    : 'Segments: single segment (complete)';
  const codeLine = output.codeRefs && output.codeRefs.length > 0
    ? truncateStickyText(`Code: ${output.codeRefs.length} file(s) reviewed (${output.codeRefs.map((ref) => ref.path).join(', ')})`, 500)
    : undefined;
  const boundedSummary = truncateStickyText(output.summary || '', MAX_STICKY_SUMMARY_LENGTH);
  const issueBody = [
    REVIEW_MARKER,
    '',
    '## PocketGuard Issue Review',
    '',
    `**Verdict: ${output.verdict}**`,
    `Issue: #${output.issueNumber} ${escapeMarkdown(output.title)}`,
    `Summary: ${escapeMarkdown(boundedSummary) || '(No summary)'}`,
    ...(boundedSummary.length < (output.summary || '').length ? ['(Summary partially omitted; see report artifact for full content)'] : []),
    `Label decision: ${appliedLabels.length > 0 ? appliedLabels.map((label) => escapeMarkdown(label)).join(', ') : 'none'}`,
    `Revision fingerprint: \`${output.fingerprint}\``,
    truncationLine,
    ...(codeLine ? [escapeMarkdown(codeLine)] : []),
    `Updated: ${updatedAt}`,
    reportLine ?? formatReportLine(process.env, false),
    '',
  ].join('\n');
  // Phase 3 (P2 #3): whole-sticky hard budget also guards the issue path
  // (normally far below it); excess stays in the report artifact.
  if (issueBody.length <= MAX_STICKY_TOTAL_LENGTH) return issueBody;
  return hardTruncateSticky(issueBody, MAX_STICKY_TOTAL_LENGTH);
}

function issueInconclusiveComment(issueNumber: number, title: string, reason: string, fingerprint: string, reportLine?: string): string {
  return [
    REVIEW_MARKER,
    '',
    '## PocketGuard Issue Review',
    '',
    '**Verdict: INCONCLUSIVE**',
    `Issue: #${issueNumber} ${escapeMarkdown(title)}`,
    `**Review result unavailable: ${safeString(reason)}**`,
    `Revision fingerprint: \`${safeString(fingerprint, 100)}\``,
    `Updated: ${new Date().toISOString()}`,
    reportLine ?? formatReportLine(process.env, false),
    SAFE_MESSAGE,
    '',
  ].join('\n');
}

// F1 (review #77 P1-1): honest-truncation fallback. The transport read
// completed but the content exceeded the model-input budget, so review mode
// stored commentsComplete=false with a validated truncation proof. When the
// proof is fresh (artifact fingerprint matches the current full-RAW
// fingerprint) this renderer preserves the verdict (INCONCLUSIVE or
// deterministic NEEDS_CHANGES — never APPROVE), the bounded summary, and
// the shown/total counts instead of collapsing everything into a generic
// "unverifiable" line. Transport failures (no proof), forged proofs
// (rejected by validateIssueOutput), and stale content keep the generic
// fallback below.
function issueTruncatedComment(
  output: RunnerIssueOutput,
  appliedLabels: string[],
  reason: string,
  reportLine?: string,
): string {
  const verdict = output.verdict === 'NEEDS_CHANGES' ? 'NEEDS_CHANGES' : 'INCONCLUSIVE';
  const truncationLine = output.truncation
    ? `Truncated: showing ${output.truncation.shownChars}/${output.truncation.totalChars} chars, ${output.truncation.shownComments}/${output.truncation.totalComments} comments (${output.truncation.totalChars - output.truncation.shownChars} chars omitted)`
    : 'Segments: single segment (complete)';
  const boundedSummary = truncateStickyText(output.summary || '', MAX_STICKY_SUMMARY_LENGTH);
  return [
    REVIEW_MARKER,
    '',
    '## PocketGuard Issue Review',
    '',
    `**Verdict: ${verdict}**`,
    `Issue: #${output.issueNumber} ${escapeMarkdown(output.title)}`,
    `Summary: ${escapeMarkdown(boundedSummary) || '(No summary)'}`,
    ...(boundedSummary.length < (output.summary || '').length ? ['(Summary partially omitted; see report artifact for full content)'] : []),
    `Label decision: ${appliedLabels.length > 0 ? appliedLabels.map((label) => escapeMarkdown(label)).join(', ') : 'none'}`,
    `Revision fingerprint: \`${safeString(output.fingerprint, 100)}\``,
    truncationLine,
    `Note: ${safeString(reason, 300)}`,
    `Updated: ${new Date().toISOString()}`,
    reportLine ?? formatReportLine(process.env, false),
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

  let currentOp: StickyWriteOp = 'list';
  try {
    let existing: GithubComment | undefined;
    for (let page = 1; ; page += 1) {
      currentOp = 'list';
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
    const resolvedBody = bodyBeforeWrite ? await bodyBeforeWrite() : body;
    // Phase 1 fail-closed: a metadata-carrying body past the GitHub hard
    // limit is never truncated (a blind cut could drop ledger markers and
    // silently lose counts). Callers convert this throw into a no-write
    // fail-closed that preserves the old ledger. Only metadata-free plain
    // text keeps the legacy enforce cap.
    if (resolvedBody.length > GITHUB_COMMENT_HARD_LIMIT && hasStickyMetadata(resolvedBody)) {
      throw new Error(STICKY_LIMIT_EXCEEDED_MESSAGE);
    }
    const currentBody = enforceGithubCommentLimit(resolvedBody);
    if (existing) {
      currentOp = 'update';
      await client.rest.issues.updateComment({
        owner: repository.owner,
        repo: repository.repo,
        comment_id: existing.id,
        body: currentBody,
      });
    } else {
      currentOp = 'create';
      await client.rest.issues.createComment({
        owner: repository.owner,
        repo: repository.repo,
        issue_number: issueNumber,
        body: currentBody,
      });
    }
  } catch (error) {
    // Distinct fail-closed signals propagate so callers can keep the old
    // ledger with zero writes; anything else stays a generic publish failure
    // tagged with the sanitized op/status (no tokens, headers, bodies, diffs).
    // Do not leak API details, and do not let labels advertise an unpublished result.
    if (isStickyLimitError(error) || isStickyLedgerUnreadableError(error)) throw error;
    let op: StickyWriteOp = currentOp;
    if (error && typeof error === 'object' && !Array.isArray(error)) {
      const rawOp = (error as Record<string, unknown>)['stickyOp'];
      if (rawOp === 'list' || rawOp === 'create' || rawOp === 'update' || rawOp === 'auth') op = rawOp;
    }
    const status = readOctokitStatus(error);
    const wrapped = new Error('PocketGuard: failed to publish review comment.');
    (wrapped as unknown as Record<string, unknown>)['stickyOp'] = op;
    if (status !== undefined) (wrapped as unknown as Record<string, unknown>)['stickyStatus'] = status;
    throw wrapped;
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

interface ReconcileFallbackPrLabelsOptions {
  client: RunnerGitHubClient;
  repository: { owner: string; repo: string };
  target: ReviewTarget;
  context: RunnerContext;
  env: NodeJS.ProcessEnv;
  output?: RunnerReviewOutput;
  freshPullRequest?: PullRequestData;
  botWrittenLabels?: ReadonlySet<string> | readonly string[];
  isStale?: boolean;
}

async function reconcileFallbackPrLabels(options: ReconcileFallbackPrLabelsOptions): Promise<void> {
  const { client, repository, target, context, env, output, freshPullRequest, botWrittenLabels } = options;
  if (!target.issueNumber) return;

  const pullRequest = target.pullRequest ?? (
    freshPullRequest && safeSha(freshPullRequest.base?.sha) && safeSha(freshPullRequest.head?.sha)
      ? freshPullRequest
      : undefined
  );
  const isStale = Boolean(options.isStale || (output && pullRequest && !sameReviewIdentity(output, pullRequest)));

  let areaLabels: string[] = [];
  if (output && !isStale && Array.isArray(output.areaLabels)) {
    areaLabels = output.areaLabels;
  } else if ((target.diffSafe || Boolean(pullRequest && safeSha(pullRequest.base?.sha) && safeSha(pullRequest.head?.sha))) && pullRequest) {
    const changed = collectChangedPaths(context, pullRequest);
    areaLabels = resolveAreaLabelsFromPaths(changed.changedFiles, changed.complete);
  }

  const titleLabels = resolveLabelsFromTitle(target.title);
  const tagLabels = configuredTagLabels(env);

  const hasSecurityBlock = Boolean(
    !isStale && (
      output?.deterministicViolations?.some((violation) =>
        violation.severity === 'BLOCK' && (violation.category === 'security' || violation.ruleId.startsWith('SEC-'))
      ) ||
      output?.roles?.some((role) =>
        role.role === 'android_sec' && role.findings?.some((finding) => finding.severity === 'BLOCK')
      )
    )
  );

  const fallbackDesired = sanitizeLabels([
    ...tagLabels,
    ...titleLabels,
    ...areaLabels,
    'status:needs-decision',
    ...(hasSecurityBlock ? ['security'] : []),
  ]);

  const reconciliation = await reconcileBotLabelsSafely({
    client,
    owner: repository.owner,
    repo: repository.repo,
    issueNumber: target.issueNumber,
    desiredLabels: fallbackDesired,
    scope: DEFAULT_PR_RECONCILE_SCOPE,
    coverageComplete: false,
    botWrittenLabels,
  });

  const reconciled = new Set([...reconciliation.added, ...reconciliation.skipped]);
  if (
    !reconciled.has('status:needs-decision') ||
    fallbackDesired.some((label) => !reconciled.has(label))
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
    try {
      const builtCurrent = await buildIssueContext(context, repository, issueNumber, issueTitle, issueBody, publishIssueMeta);
      // Phase B v2: compare full RAW-content fingerprints (pre-cut, no
      // masking) on both sides so a tail edit past any cap -- or a
      // same-mask secret swap -- is detected as stale.
      currentFingerprint = builtCurrent.fullFingerprint;
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
          else if (sanitizeIssueAiSuggestions(Array.isArray(validated.suggestedLabels) ? validated.suggestedLabels : []).discardedCount > 0) issueFallbackReason = 'the AI label suggestions contain labels outside the issue allowlist; discarded.';
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
      // F1: honest-truncation branch — a fresh budget-truncation proof keeps
      // its verdict/summary/counts; everything else keeps the generic fallback.
      const fallbackDesiredEarly = sanitizeLabels([...rulesTitle, 'status:needs-decision']);
      const honestTruncated = validated
        && validated.commentsComplete === false
        && validated.truncation !== undefined
        && (validated.verdict === 'INCONCLUSIVE' || validated.verdict === 'NEEDS_CHANGES')
        && /^[0-9a-f]{64}$/.test(validated.fingerprint)
        && validated.fingerprint === currentFingerprint
        && issueFallbackReason !== undefined
        && issueFallbackReason.includes('not fully fetched during review')
        ? validated
        : undefined;
      const fallbackReportLine = formatReportLine(env, false);
      const honestReportLine = formatReportLine(env, areReviewReportsAvailable(env));
      await publishStickyComment(
        client,
        repository,
        issueNumber,
        honestTruncated
          ? issueTruncatedComment(honestTruncated, fallbackDesiredEarly, issueFallbackReason ?? 'the issue content exceeded the review budget and was truncated.', honestReportLine)
          : issueInconclusiveComment(issueNumber, issueTitle, issueFallbackReason ?? 'a valid review result was unavailable.', fallbackFingerprint, fallbackReportLine),
      );
      const fallbackDesired = fallbackDesiredEarly;
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

    const { merged, hadIllegal } = mergeIssueRulesWithAiSuggestions(rulesTitle, validated.suggestedLabels);
    if (hadIllegal) {
      await publishStickyComment(
        client,
        repository,
        issueNumber,
        issueInconclusiveComment(issueNumber, issueTitle, 'the AI label suggestions contain labels outside the issue allowlist; discarded.', currentFingerprint, formatReportLine(env, false)),
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
  // Phase 1: every write below would rather not write (old ledger preserved,
  // no label changes) than write a truncated body — publishPrSticky converts
  // the over-limit/unreadable-ledger throws into a quiet false.
  const buildPreservedBody = async (freshContent: string): Promise<string> => {
    const fresh = await readStickyLedger(client, repository.owner, repository.repo, target.issueNumber!);
    if (!fresh.ok) throw new Error(STICKY_LEDGER_UNREADABLE_MESSAGE);
    return buildStampedBody(freshContent, fresh.ledger, fresh.claims, undefined, undefined, undefined);
  };
  const publishPrSticky = async (initialBody: string, rebuild: () => Promise<string>): Promise<boolean> => {
    try {
      await publishStickyComment(client, repository, target.issueNumber!, initialBody, rebuild);
      return true;
    } catch (error) {
      if (isStickyLimitError(error) || isStickyLedgerUnreadableError(error)) return false;
      throw error;
    }
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
    const wroteFallback = await publishPrSticky(
      buildStampedBody(inconclusiveComment(reasonText, unavailableLine), baseline.ledger, baseline.claims, undefined, undefined, undefined),
      async () => buildPreservedBody(inconclusiveComment(reasonText, unavailableLine)),
    );
    if (!wroteFallback) return;
    await reconcileFallbackPrLabels({
      client,
      repository,
      target,
      context,
      env,
      output,
      freshPullRequest,
      botWrittenLabels: parseBotLabelsMarker(baseline.body),
    });
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
    if (!fresh.ok) throw new Error(STICKY_LEDGER_UNREADABLE_MESSAGE);
    return buildStampedBody(freshContent, fresh.ledger, fresh.claims, undefined, undefined, undefined);
  };
  const reconciledInitialBody = (freshContent: string): string =>
    buildStampedBody(freshContent, baselineReconciled.ledger, baselineReconciled.claims, undefined, undefined, undefined);

  // Stale but verifiable output: preserved INCONCLUSIVE fallback that keeps
  // the claim's ledger untouched (never polluting another SHA).
  if (staleReason) {
    const staleLine = formatReportLine(env, false);
    const wroteStale = await publishPrSticky(
      reconciledInitialBody(inconclusiveComment(staleReason, staleLine)),
      async () => buildReconciledBody(inconclusiveComment(staleReason, staleLine)),
    );
    if (!wroteStale) return;
    await reconcileFallbackPrLabels({
      client,
      repository,
      target,
      context,
      env,
      output,
      freshPullRequest,
      botWrittenLabels: parseBotLabelsMarker(baselineReconciled.body),
      isStale: true,
    });
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
    const wroteDiscard = await publishPrSticky(
      reconciledInitialBody(inconclusiveComment(discardReason, discardLine)),
      async () => buildReconciledBody(inconclusiveComment(discardReason, discardLine)),
    );
    if (!wroteDiscard) return;
    await reconcileFallbackPrLabels({
      client,
      repository,
      target,
      context,
      env,
      output,
      freshPullRequest,
      botWrittenLabels: parseBotLabelsMarker(baselineReconciled.body),
    });
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
  // link plus reconcileFallbackPrLabels. Single comment ID updated in place.
  const prReportsAvailable = areReviewReportsAvailable(env);
  if (!prReportsAvailable) {
    const unavailableLine = formatReportLine(env, false);
    const reportsMissingReason = 'the review reports are missing or inconsistent; review freshness could not be verified.';
    const wroteReportsMissing = await publishPrSticky(
      reconciledInitialBody(inconclusiveComment(reportsMissingReason, unavailableLine)),
      async () => buildReconciledBody(inconclusiveComment(reportsMissingReason, unavailableLine)),
    );
    if (!wroteReportsMissing) return;
    await reconcileFallbackPrLabels({
      client,
      repository,
      target,
      context,
      env,
      output,
      freshPullRequest,
      botWrittenLabels: parseBotLabelsMarker(baselineReconciled.body),
    });
    return;
  }
  // Phase 2 (H): success writes back the artifact/run link; the gate above
  // guarantees content-valid reports, so only a missing run identity degrades
  // to unavailable (still no fake link).
  const prReportLine = formatReportLine(env, getReportRunUrl(env) !== undefined);
  // Phase 1: size the visible summary against the marker suffix stamped
  // later, so visible + ledger still fits and only findings shrink.
  const prMetadataReserve = estimateStickyMetadataLength(baselineReconciled.ledger, baselineReconciled.claims);

  let publishFallbackReason: string | undefined;
  const wroteSuccess = await publishPrSticky(reconciledInitialBody(reviewComment(output, labels, prReportLine, prMetadataReserve)), async () => {
    publishFallbackReason = await currentReviewProblem(client, repository, target.issueNumber!, output!);
    const unavailableFallback = formatReportLine(env, false);
    const content = publishFallbackReason ? inconclusiveComment(publishFallbackReason, unavailableFallback) : reviewComment(output!, labels, prReportLine, prMetadataReserve);
    return buildReconciledBody(content);
  });
  if (!wroteSuccess) return;
  if (publishFallbackReason) {
    await reconcileFallbackPrLabels({
      client,
      repository,
      target,
      context,
      env,
      output,
      freshPullRequest,
      botWrittenLabels: parseBotLabelsMarker(baselineReconciled.body),
      isStale: true,
    });
    return;
  }

  const labelFallbackReason = await currentReviewProblem(client, repository, target.issueNumber, output);
  if (labelFallbackReason) {
    const labelFallbackLine = formatReportLine(env, false);
    const wroteLabelFallback = await publishPrSticky(
      reconciledInitialBody(inconclusiveComment(labelFallbackReason, labelFallbackLine)),
      async () => buildReconciledBody(inconclusiveComment(labelFallbackReason, labelFallbackLine)),
    );
    if (!wroteLabelFallback) return;
    await reconcileFallbackPrLabels({
      client,
      repository,
      target,
      context,
      env,
      output,
      freshPullRequest,
      botWrittenLabels: parseBotLabelsMarker(baselineReconciled.body),
      isStale: true,
    });
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
  } catch (error) {
    // Sanitized summary only: message sliced to 200 chars after redacting
    // token shapes (gh[pousr]_* and Bearer). Never serializes the error, and
    // never logs headers, response bodies, or diffs. Still exits 1.
    const summary = sanitizeErrorSummary(error, 200);
    process.stderr.write(`PocketGuard: operation failed. ${summary}\n`);
    process.exitCode = 1;
  }
}

if (require.main === module) void main();
