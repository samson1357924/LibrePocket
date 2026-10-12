export type CommentCommand = 'explain' | 'fix' | 'triage' | 'review' | 'fix-ci' | 'unsupported';
export type CommentTarget = 'issue' | 'pull-request';

export const NEEDS_DIFF: ReadonlySet<CommentCommand> = new Set([
  'explain',
  'fix',
  'review',
  'fix-ci',
]);
// NEEDS_DIFF marks commands that conceptually require a diff. Only 'review'
// is currently actionable on pull requests (see isCommentCommandAllowed):
// 'explain'/'fix'/'fix-ci' have no independent implementation yet and are
// legal no-ops (routed to ignore, zero AI, zero quota, zero writes).

const COMMANDS = new Map<string, CommentCommand>([
  ['/review', 'review'],
  ['/triage', 'triage'],
  ['/explain', 'explain'],
  ['/fix', 'fix'],
  ['/fix-ci', 'fix-ci'],
]);

// Strip content where a command-looking string must not trigger: fenced
// code blocks (``` ... ``` and ~~~ ... ~~~, unclosed runs to end of input),
// inline code spans (`...`), and GitHub markdown quoted lines (a line whose
// first non-space character is `>`). Only the remaining standalone text is
// classified, so `/review` inside a fence or quotation is ignored.
export function stripNonCommandContent(body: string): string {
  const input = typeof body === 'string' ? body : '';
  let stripped = input.replace(/```[\s\S]*?(?:```|$)/g, '\n');
  stripped = stripped.replace(/~~~[\s\S]*?(?:~~~|$)/g, '\n');
  stripped = stripped.replace(/`[^`\n]*`/g, ' ');
  stripped = stripped
    .split('\n')
    .filter((line) => !/^\s*>/.test(line))
    .join('\n');
  return stripped;
}

export function classifyCommentCommand(commentBody: string): CommentCommand {
  const body = typeof commentBody === 'string' ? commentBody : '';
  // Normalize fullwidth variants so ／review / ＠pocketguard behave like ASCII.
  // Fenced code blocks, inline code spans, and quoted lines are stripped
  // before matching so commands inside them never trigger (see README).
  const normalized = body.replace(/／/g, '/').replace(/＠/g, '@');
  const visible = stripNonCommandContent(normalized);
  const slashCommand = visible.match(/(?:^|\s)(\/[a-z][a-z0-9-]*)\b/i)?.[1]?.toLowerCase();
  if (slashCommand) return COMMANDS.get(slashCommand) ?? 'unsupported';

  const mentionCommand = visible.match(/(?:^|\s)@pocketguard\s+\/?(review|triage|explain|fix|fix-ci)\b/i)?.[1]?.toLowerCase();
  if (mentionCommand) return COMMANDS.get(`/${mentionCommand}`) ?? 'unsupported';
  // Bare "@pocketguard" (no verb) is NOT a review command; it classifies as
  // unsupported so PR re-review requires an explicit instruction.
  return 'unsupported';
}

export function commentCommandNeedsGitDiff(command: CommentCommand): boolean {
  return NEEDS_DIFF.has(command);
}

export function isCommentCommandAllowed(command: CommentCommand, target: CommentTarget): boolean {
  // Owner scope (Stage 4): only an explicit `/review` (or the equivalent
  // `@pocketguard review` form) re-triggers a PR review. `/explain`, `/fix`,
  // and `/fix-ci` have no independent implementation yet: they classify
  // (and still report needsDiff) but are never allowed, so routing ignores
  // them with zero AI, zero quota, and zero writes. `/triage` stays
  // issue-only.
  if (target === 'issue') return command === 'triage';
  return command === 'review';
}
