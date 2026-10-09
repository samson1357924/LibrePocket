export type CommentCommand = 'explain' | 'fix' | 'triage' | 'review' | 'fix-ci' | 'unsupported';
export type CommentTarget = 'issue' | 'pull-request';

export const NEEDS_DIFF: ReadonlySet<CommentCommand> = new Set([
  'explain',
  'fix',
  'review',
  'fix-ci',
]);

const COMMANDS = new Map<string, CommentCommand>([
  ['/review', 'review'],
  ['/triage', 'triage'],
  ['/explain', 'explain'],
  ['/fix', 'fix'],
  ['/fix-ci', 'fix-ci'],
]);

export function classifyCommentCommand(commentBody: string): CommentCommand {
  const body = typeof commentBody === 'string' ? commentBody : '';
  // Normalize fullwidth variants so ／review / ＠pocketguard behave like ASCII.
  // Code-fence/quote stripping is NOT handled here (known limitation, see README).
  const normalized = body.replace(/／/g, '/').replace(/＠/g, '@');
  const slashCommand = normalized.match(/(?:^|\s)(\/[a-z][a-z0-9-]*)\b/i)?.[1]?.toLowerCase();
  if (slashCommand) return COMMANDS.get(slashCommand) ?? 'unsupported';

  const mentionCommand = normalized.match(/(?:^|\s)@pocketguard\s+\/?(review|triage|explain|fix|fix-ci)\b/i)?.[1]?.toLowerCase();
  if (mentionCommand) return COMMANDS.get(`/${mentionCommand}`) ?? 'unsupported';
  // Bare "@pocketguard" (no verb) is NOT a review command; it classifies as
  // unsupported so PR re-review requires an explicit instruction.
  return 'unsupported';
}

export function commentCommandNeedsGitDiff(command: CommentCommand): boolean {
  return NEEDS_DIFF.has(command);
}

export function isCommentCommandAllowed(command: CommentCommand, target: CommentTarget): boolean {
  return target === 'issue' ? command === 'triage' : commentCommandNeedsGitDiff(command);
}
