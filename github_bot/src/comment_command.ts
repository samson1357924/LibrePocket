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
  const slashCommand = body.match(/(?:^|\s)(\/[a-z][a-z0-9-]*)\b/i)?.[1]?.toLowerCase();
  if (slashCommand) return COMMANDS.get(slashCommand) ?? 'unsupported';

  const mentionCommand = body.match(/(?:^|\s)@pocketguard\s+(review|triage|explain|fix|fix-ci)\b/i)?.[1]?.toLowerCase();
  if (mentionCommand) return COMMANDS.get(`/${mentionCommand}`) ?? 'unsupported';
  if (/(?:^|\s)@pocketguard\b/i.test(body)) return 'review';
  return 'unsupported';
}

export function commentCommandNeedsGitDiff(command: CommentCommand): boolean {
  return NEEDS_DIFF.has(command);
}

export function isCommentCommandAllowed(command: CommentCommand, target: CommentTarget): boolean {
  return target === 'issue' ? command === 'triage' : commentCommandNeedsGitDiff(command);
}
