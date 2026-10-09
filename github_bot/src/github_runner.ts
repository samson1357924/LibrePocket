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

const REVIEW_MARKER = '<!-- PocketGuard-review -->';
const REVIEW_COUNT_MARKER_PREFIX = '<!-- PocketGuard-reviews:';
// S4 execution matrix: at most two AI reviews per PR+head SHA (the first
// review plus one re-review); a new head SHA resets the budget. Issues are
// never counted. The per-PR workflow concurrency group serializes runs as the
// primary mutex; GitHub offers no compare-and-swap on comments, so a residual
// race remains if two runs for the same PR ever overlap (see readStickyReviewCount).
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
  authorized: boolean;
  issueNumber?: number;
  shouldReview: boolean;
  shouldTag: boolean;
  reason: string;
  routeKind: RouteKind;
  reviewGate: ReviewGate;
  reviewsUsed: number;
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

export function parseReviewCountMarker(body: unknown, sha: string): number {
  if (typeof body !== 'string' || !safeSha(sha)) return 0;
  const wanted = sha.toLowerCase();
  const pattern = new RegExp(
    `${REVIEW_COUNT_MARKER_PREFIX.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}([0-9a-f]{40}):(\\d+)\\s*-->`,
    'gi',
  );
  let count = 0;
  for (const match of body.matchAll(pattern)) {
    if (match[1].toLowerCase() === wanted) {
      const parsed = Number.parseInt(match[2], 10);
      if (Number.isSafeInteger(parsed) && parsed >= 0) count = parsed;
    }
  }
  return count;
}

export function withReviewCountMarker(body: string, sha: string, count: number): string {
  return `${body}${formatReviewCountMarker(sha, count)}\n`;
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

async function inspectTarget(context: RunnerContext): Promise<ReviewTarget> {
  const env = context.env ?? process.env;
  const event = eventFrom(context);
  const eventName = env.GITHUB_EVENT_NAME ?? '';
  const repository = repositoryParts(env, event);

  if (eventName === 'pull_request_target' || event.pull_request) {
    const pull = event.pull_request;
    if (!pull) return { target: 'none', command: 'unsupported', needsDiff: false, safeReview: false, authorized: false, title: '', };
    const headRepository = pull.head?.repo?.full_name;
    const baseSha = pull.base?.sha ?? '';
    const headSha = pull.head?.sha ?? '';
    const safeReview = Boolean(
      repository && sameRepository(headRepository, repository.fullName) && safeSha(baseSha) && safeSha(headSha),
    );
    const number = Number(pull.number ?? event.issue?.number);
    return {
      target: 'pull-request',
      command: 'review',
      needsDiff: true,
      safeReview,
      // pull_request_target carries no commenter to authorize; the same-repo
      // origin check above governs. authorized is always true here.
      authorized: true,
      ...(Number.isSafeInteger(number) && number > 0 ? { issueNumber: number } : {}),
      ...(safeReview ? {
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
      return { target, command, needsDiff, safeReview: false, authorized: false, ...issueRef, title };
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
        return { target, command, needsDiff, safeReview: false, authorized: false, ...issueRef, title };
      }
    } else {
      return { target, command, needsDiff, safeReview: false, authorized: false, ...issueRef, title };
    }
    const safeReview = Boolean(
      authorized && pullRequest && repository &&
      sameRepository(pullRequest.head.repo?.full_name, repository.fullName) &&
      safeSha(pullRequest.base.sha) && safeSha(pullRequest.head.sha),
    );
    const quotaHeadSha = pullRequest && safeSha(pullRequest.head.sha) ? pullRequest.head.sha : undefined;
    return {
      target,
      command,
      needsDiff,
      safeReview: safeReview && isCommentCommandAllowed(command, target),
      authorized,
      ...(Number.isSafeInteger(issueNumber) && issueNumber > 0 ? { issueNumber } : {}),
      ...(safeReview ? { pullRequest } : {}),
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
      authorized: false,
      ...(Number.isSafeInteger(Number(event.issue?.number)) && Number(event.issue?.number) > 0
        ? { issueNumber: Number(event.issue?.number) }
        : {}),
      title: typeof event.issue?.title === 'string' ? event.issue.title : '',
    };
  }

  return { target: 'none', command: 'unsupported', needsDiff: false, safeReview: false, authorized: false, title: '' };
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
  const changed = target.safeReview && target.pullRequest
    ? collectChangedPaths(context, target.pullRequest)
    : { changedFiles: [], complete: false };
  const areaLabels = target.safeReview && target.pullRequest
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
  // S4 quota: an open PR gate closes when this PR+head SHA already consumed
  // its budget, or when the counter is unreadable (fail-closed). Only started
  // reviews count (routing/authorization denials never reach a sticky write),
  // and a new SHA restarts. Issue targets never open a PR gate and never
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
    // Fail-closed: only an explicit true counts as authorized.
    authorized: target.authorized === true,
    ...(target.issueNumber ? { issueNumber: target.issueNumber } : {}),
    shouldReview,
    shouldTag: flags.shouldTag,
    reason,
    routeKind: route.kind,
    reviewGate,
    reviewsUsed,
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
    authorized: String(result.authorized),
    ...(result.issueNumber ? { issue_number: String(result.issueNumber) } : {}),
    should_review: String(result.shouldReview),
    should_tag: String(result.shouldTag),
    reason: result.reason,
    route_kind: result.routeKind,
    review_gate: result.reviewGate,
    reviews_used: String(result.reviewsUsed),
    is_owner: String(result.isOwner),
    ...(result.actor ? { actor: result.actor } : {}),
    ...(result.repoOwner ? { repo_owner: result.repoOwner } : {}),
    ...(result.eventName ? { event_name: result.eventName } : {}),
    ...(result.action ? { event_action: result.action } : {}),
  });
  stdout(context, `${JSON.stringify(result)}\n`);
  return result;
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

function collectChangedPaths(
  context: RunnerContext,
  pullRequest: PullRequestData,
): { changedFiles: string[]; complete: boolean } {
  try {
    const runGit = context.runGit ?? defaultGit;
    fetchReviewCommits(runGit, pullRequest.base.sha, pullRequest.head.sha);
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

function redactForModel(value: string): string {
  return value
    .replace(/-----BEGIN\s+(?:[A-Z0-9]+\s+)*PRIVATE\s+KEY-----[\s\S]*?-----END\s+(?:[A-Z0-9]+\s+)*PRIVATE\s+KEY-----/gi, '[REDACTED PRIVATE KEY]')
    .replace(/\bAIZA[A-Z0-9_-]{35}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bgh[pousr]_[A-Z0-9]{20,}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bgithub_pat_[A-Z0-9_]{20,}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bsk-(?:live|test)-[A-Z0-9_-]{8,}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bAKIA[0-9A-Z]{16}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bxox(?:[aboprs]|b)-[A-Z0-9-]{10,}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\b(Bearer|Basic)\s+[A-Z0-9._~+/-]+=*/gi, '$1 [REDACTED CREDENTIAL]')
    .replace(/\b(api[_-]?key|access[_-]?token|client[_-]?secret|password)\s*[:=]\s*["']?[^\s,;"'`]+/gi, '$1=[REDACTED CREDENTIAL]');
}

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
  suggestedLabels: string[];
  fingerprint: string;
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
    const unknown = rawList.filter((entry) => normalizeLabelName(entry) === undefined);
    console.warn(`[PocketGuard] Discarded unknown AI labels: [${unknown.join(', ')}]`);
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
    Object.keys(raw).some((key) => !['verdict', 'issueNumber', 'title', 'tags', 'summary', 'suggestedLabels', 'fingerprint'].includes(key)) ||
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
  };
}

// S4 issue context: title plus body plus human comments (bot authors and bot
// senders excluded by the same loop-protection rules as routing), truncated
// to MAX_ISSUE_CONTEXT_LENGTH. Comment reads are best-effort: any API failure
// yields title plus body only, never a throw.
async function buildIssueContext(
  context: RunnerContext,
  repository: { owner: string; repo: string } | undefined,
  issueNumber: number,
  title: string,
  body: string,
): Promise<{ title: string; body: string; comments: string[] }> {
  const comments: string[] = [];
  const client = apiClient(context, (context.env ?? process.env).GITHUB_TOKEN ?? '');
  try {
    const issues = client?.rest.issues as RunnerGitHubClient['rest']['issues'] | undefined;
    if (client && repository && typeof issues?.listComments === 'function') {
      for (let page = 1; ; page += 1) {
        const response = await issues.listComments({
          owner: repository.owner,
          repo: repository.repo,
          issue_number: issueNumber,
          per_page: 100,
          page,
        });
        if (!Array.isArray(response.data)) break;
        for (const comment of response.data) {
          if (typeof comment?.body !== 'string' || !comment.body.trim()) continue;
          if (comment.user?.type?.toLowerCase() === 'bot') continue;
          if (typeof comment.user?.login === 'string' && isBotLogin(comment.user.login)) continue;
          const login = typeof comment.user?.login === 'string' && comment.user.login.trim()
            ? comment.user.login.trim()
            : 'unknown';
          comments.push(`${login}：${safeString(comment.body)}`);
        }
        if (response.data.length < 100) break;
      }
    }
  } catch {
    // Best-effort: fall through with title plus body only.
  }
  const safeTitle = safeString(title);
  let safeBody = safeString(body, 8000);
  const kept = [...comments];
  const assembledLength = (): number =>
    safeTitle.length + safeBody.length + kept.reduce((total, comment) => total + comment.length, 0);
  while (kept.length > 0 && assembledLength() > MAX_ISSUE_CONTEXT_LENGTH) kept.pop();
  if (assembledLength() > MAX_ISSUE_CONTEXT_LENGTH) {
    safeBody = safeBody.slice(0, Math.max(0, MAX_ISSUE_CONTEXT_LENGTH - safeTitle.length));
  }
  return { title: safeTitle, body: safeBody, comments: kept };
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
    const output: RunnerIssueOutput = { verdict: 'INCONCLUSIVE', issueNumber: 0, title: '', tags: [], summary: '', suggestedLabels: [], fingerprint: '0'.repeat(64) };
    saveIssueOutput(output, context);
    return output;
  }
  const target = await inspectTarget(context);
  const title = target.title;
  const body = typeof event.issue?.body === 'string' ? event.issue.body : '';
  const issueNumber = target.issueNumber ?? 0;
  const repository = repositoryParts(env, event);
  const fingerprintFor = async (): Promise<string> => {
    try {
      const builtForPrint = await buildIssueContext(context, repository, issueNumber, title, body);
      return issueContentFingerprint(builtForPrint.title, builtForPrint.body, builtForPrint.comments);
    } catch {
      return '0'.repeat(64);
    }
  };
  const rulesOnly = async (summary: string): Promise<RunnerIssueOutput> => ({
    verdict: 'INCONCLUSIVE',
    issueNumber,
    title: safeString(title),
    tags: issueRulesTags(title),
    summary: safeString(summary),
    suggestedLabels: [],
    fingerprint: issueNumber ? await fingerprintFor() : '0'.repeat(64),
  });
  const route = routeEvent(event, env);
  if (target.target !== 'issue' || !issueNumber || (route.kind !== 'first-review' && route.kind !== 'issue-update')) {
    const output = await rulesOnly(`no issue review: ${route.reason}`);
    saveIssueOutput(output, context);
    return output;
  }
  const built = await buildIssueContext(context, repository, issueNumber, title, body);
  const fingerprint = issueContentFingerprint(built.title, built.body, built.comments);
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
    // every call, so safeReview === false (including any auth deny) returns
    // the generic output before any OpenAI call, even with POCKETGUARD_SAFE_REVIEW
    // set. No separate auth logic is needed here.
    if (
      target.target !== 'pull-request' || !target.safeReview || forcedUnsafe || !target.pullRequest ||
      !target.issueNumber || !isCommentCommandAllowed(target.command, 'pull-request')
    ) {
      saveReviewOutput(output, context);
      return output;
    }

    // S4 execution matrix: routed-ignore events (non-owner synchronize,
    // ordinary chatter, unsubscribed actions, bot events) never reach AI, and
    // review mode re-reads the sticky counter immediately before any git or
    // OpenAI work as defense in depth against concurrent runs that both
    // passed tag mode. A quota denial or an unreadable counter (fail-closed)
    // returns the generic output with zero OpenAI calls and — like every
    // routing or authorization denial — is never counted.
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

    const baseSha = target.pullRequest.base.sha;
    const headSha = target.pullRequest.head.sha;
    const runGit = context.runGit ?? defaultGit;
    try {
      fetchReviewCommits(runGit, baseSha, headSha);
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
  return value
    .replace(/-----BEGIN\s+(?:[A-Z0-9]+\s+)*PRIVATE\s+KEY-----[\s\S]*?-----END\s+(?:[A-Z0-9]+\s+)*PRIVATE\s+KEY-----/gi, '[REDACTED PRIVATE KEY]')
    .replace(/\bAIZA[A-Z0-9_-]{35}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bgh[pousr]_[A-Z0-9]{20,}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bgithub_pat_[A-Z0-9_]{20,}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bsk-(?:live|test)-[A-Z0-9_-]{8,}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bAKIA[0-9A-Z]{16}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\bxox(?:[aboprs]|b)-[A-Z0-9-]{10,}\b/gi, '[REDACTED CREDENTIAL]')
    .replace(/\b(Bearer|Basic)\s+[A-Z0-9._~+/-]+=*/gi, '$1 [REDACTED CREDENTIAL]')
    .replace(/\b(api[_-]?key|access[_-]?token|client[_-]?secret|password)\s*[:=]\s*["']?[^\s,;"'`]+/gi, '$1=[REDACTED CREDENTIAL]')
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
    const issueTitle = target.title;
    const issueBody = typeof event.issue?.body === 'string' ? event.issue.body : '';
    const rulesTitle = sanitizeLabels([
      ...configuredTagLabels(env),
      ...resolveLabelsFromTitle(issueTitle),
    ]);
    const builtCurrent = await buildIssueContext(context, repository, issueNumber, issueTitle, issueBody);
    const currentFingerprint = issueContentFingerprint(builtCurrent.title, builtCurrent.body, builtCurrent.comments);

    let validated: RunnerIssueOutput | undefined;
    let issueFallbackReason: string | undefined;
    const reviewJobResult = env.POCKETGUARD_REVIEW_JOB_RESULT ?? 'unavailable';
    if (reviewJobResult !== 'success') {
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

  // S4 quota: an exhausted budget — or an unreadable counter (fail-closed) —
  // publishes nothing — the sticky comment and labels keep the previous
  // review untouched. Counting happens only on the sticky writes below
  // (started reviews, including INCONCLUSIVE fallbacks); every write in this
  // run shares one stamp, so the label-fallback overwrite cannot double-count.
  // Without a fresh head SHA the write carries no marker (degraded, uncounted)
  // rather than a wrong one.
  let reviewsUsed = 0;
  if (freshPullRequest && safeSha(freshPullRequest.head.sha)) {
    const quota = await readStickyReviewCount(
      client,
      repository.owner,
      repository.repo,
      target.issueNumber,
      freshPullRequest.head.sha,
    );
    if (!quota.ok) return;
    if (quota.used >= MAX_REVIEWS_PER_SHA) return;
    reviewsUsed = quota.used;
  }
  const stampSticky = (body: string): string =>
    freshPullRequest && safeSha(freshPullRequest.head.sha)
      ? withReviewCountMarker(body, freshPullRequest.head.sha, reviewsUsed + 1)
      : body;

  const outputPath = env.POCKETGUARD_OUTPUT ?? 'review-output.json';
  let output: RunnerReviewOutput | undefined;
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
        fallbackReason = 'the review output is stale: its PR number, base SHA, head SHA, or head repository no longer matches GitHub.';
      }
    }
  }

  if (fallbackReason || !output) {
    await publishStickyComment(
      client,
      repository,
      target.issueNumber,
      stampSticky(inconclusiveComment(fallbackReason ?? 'a valid review result was unavailable.')),
    );
    await reconcileNeedsDecisionOnly(client, repository, target.issueNumber);
    return;
  }

  // S5 AI label gate: raw suggestions are allowlisted at publish. Unknown
  // entries are discarded with a log and force INCONCLUSIVE on the same
  // sticky (never a wrong APPROVE, never writing illegal labels). Manual
  // labels outside the bot scope are always preserved by reconciliation.
  const aiRaw = Array.isArray(output.suggestedLabels) ? output.suggestedLabels : [];
  const { sanitizedAi, hadUnknown } = mergeRulesWithAiSuggestions([], aiRaw);
  if (hadUnknown) {
    await publishStickyComment(
      client,
      repository,
      target.issueNumber,
      stampSticky(inconclusiveComment('the AI label suggestions contain unknown labels; discarded.')),
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
  await publishStickyComment(client, repository, target.issueNumber, stampSticky(reviewComment(output, labels)), async () => {
    publishFallbackReason = await currentReviewProblem(client, repository, target.issueNumber!, output!);
    return publishFallbackReason ? stampSticky(inconclusiveComment(publishFallbackReason)) : stampSticky(reviewComment(output!, labels));
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
      stampSticky(inconclusiveComment(labelFallbackReason)),
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

function modeFromArgs(args: string[]): 'tag' | 'review' | 'publish' | undefined {
  const inline = args.find((arg) => arg.startsWith('--mode='))?.slice('--mode='.length);
  const index = args.indexOf('--mode');
  const mode = inline ?? (index >= 0 ? args[index + 1] : undefined);
  return mode === 'tag' || mode === 'review' || mode === 'publish' ? mode : undefined;
}

async function main(): Promise<void> {
  const mode = modeFromArgs(process.argv.slice(2));
  if (!mode) {
    process.stderr.write('PocketGuard: specify --mode=tag, --mode=review, or --mode=publish.\n');
    process.exitCode = 2;
    return;
  }
  try {
    if (mode === 'tag') await runTagMode();
    if (mode === 'review') await runReviewEntryMode();
    if (mode === 'publish') await runPublishMode();
  } catch {
    process.stderr.write('PocketGuard: operation failed.\n');
    process.exitCode = 1;
  }
}

if (require.main === module) void main();
