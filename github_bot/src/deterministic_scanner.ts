export type ScanSeverity = 'BLOCK' | 'WARN';
export type ScanCategory = 'security' | 'reliability';

export interface ScanViolation {
  ruleId: string;
  severity: ScanSeverity;
  category: ScanCategory;
  message: string;
  file?: string;
  line?: number;
}

export interface DeterministicScanResult {
  passed: boolean;
  hasBlockers: boolean;
  violations: ScanViolation[];
}

interface AddedLine {
  file?: string;
  line?: number;
  hunk?: number;
  text: string;
}

const secretPatterns: RegExp[] = [
  /-----BEGIN\s+(?:[A-Z0-9]+\s+)*PRIVATE\s+KEY-----/i,
  new RegExp('\\bA' + 'Iza[A-Za-z0-9_-]{35}\\b'),
  new RegExp('\\b' + ['gh', 'p_'].join('') + '[A-Za-z0-9]{20,}'),
  new RegExp('\\b' + ['gh', 'o_'].join('') + '[A-Za-z0-9]{20,}'),
  new RegExp('\\b' + ['github', '_pat_'].join('') + '[A-Za-z0-9_]{20,}'),
  new RegExp('\\b' + ['s', 'k-'].join('') + '(?:live|test)-[A-Za-z0-9_-]{8,}'),
  new RegExp('\\b' + ['AK', 'IA'].join('') + '[0-9A-Z]{16}\\b'),
  /\bxox(?:[aboprs]|b)-[A-Za-z0-9-]{10,}/,
];

const forbiddenManifestPermissions = new Set([
  'SEND_SMS',
  'READ_SMS',
  'RECEIVE_SMS',
  'MANAGE_EXTERNAL_STORAGE',
  'BIND_VPN_SERVICE',
]);

function normalizePath(path: string): string {
  return path.replace(/\\/g, '/').replace(/^\.\//, '');
}

function unquoteDiffPath(path: string): string {
  let unquoted = path;
  if (unquoted.startsWith('"') && unquoted.endsWith('"')) {
    unquoted = unquoted.slice(1, -1).replace(/\\([\\"])/g, '$1');
  }
  return unquoted.replace(/^[ab]\//, '');
}

function addedLinesFromDiff(gitDiff: string, changedFiles: string[]): AddedLine[] {
  const normalizedFiles = changedFiles.map(normalizePath);
  const fallbackFile = normalizedFiles.length === 1 ? normalizedFiles[0] : undefined;
  const diffMetadata = /^(?:diff --git |--- |\+\+\+ |@@ )/m.test(gitDiff);
  if (!diffMetadata) {
    return gitDiff.split(/\r?\n/).map((text, index) => ({ file: fallbackFile, line: index + 1, text }));
  }

  const rows: AddedLine[] = [];
  let currentFile: string | undefined;
  let newLineNumber: number | undefined;
  let currentHunk: number | undefined;
  let nextHunk = 0;

  for (const line of gitDiff.split(/\r?\n/)) {
    if (line.startsWith('+++ ')) {
      const path = line.slice(4).split('\t', 1)[0];
      currentFile = path === '/dev/null' ? undefined : normalizePath(unquoteDiffPath(path));
      newLineNumber = undefined;
      currentHunk = undefined;
      continue;
    }

    const hunk = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line);
    if (hunk) {
      newLineNumber = Number(hunk[1]);
      currentHunk = nextHunk;
      nextHunk += 1;
      continue;
    }

    if (line.startsWith('+') && !line.startsWith('+++')) {
      rows.push({
        file: currentFile ?? fallbackFile,
        line: newLineNumber,
        hunk: currentHunk,
        text: line.slice(1),
      });
      if (newLineNumber !== undefined) newLineNumber += 1;
      continue;
    }

    if (!line.startsWith('diff --git ') && !line.startsWith('--- ') && line.startsWith(' ')) {
      if (newLineNumber !== undefined) newLineNumber += 1;
    }
  }

  return rows;
}

function addViolation(
  violations: ScanViolation[],
  ruleId: string,
  severity: ScanSeverity,
  category: ScanCategory,
  message: string,
  file?: string,
  line?: number,
): void {
  violations.push({ ruleId, severity, category, message, ...(file ? { file } : {}), ...(line ? { line } : {}) });
}

function rowAtOffset(rows: AddedLine[], offset: number): AddedLine | undefined {
  let currentOffset = 0;
  for (const row of rows) {
    if (offset <= currentOffset + row.text.length) return row;
    currentOffset += row.text.length + 1;
  }
  return rows[rows.length - 1];
}

function rowAtJoinedOffset(rows: AddedLine[], offset: number): AddedLine | undefined {
  let currentOffset = 0;
  for (const row of rows) {
    if (offset < currentOffset + row.text.length) return row;
    currentOffset += row.text.length;
  }
  return rows[rows.length - 1];
}

/**
 * Bounded credential heuristic: join only 2–3 adjacent added lines from the
 * same known file and diff hunk when their new-line numbers are contiguous.
 * This intentionally does not attempt arbitrary multiline/source reconstruction.
 */
function scanMultilineSecrets(rows: AddedLine[], violations: ScanViolation[]): void {
  const reportedLocations = new Set<string>();

  for (let startIndex = 0; startIndex < rows.length; startIndex += 1) {
    const first = rows[startIndex];
    if (!first?.file || first.line === undefined || first.hunk === undefined) continue;

    const windowRows = [first];
    let joinedText = first.text;
    for (
      let nextIndex = startIndex + 1;
      nextIndex < rows.length && windowRows.length < 3;
      nextIndex += 1
    ) {
      const previous = windowRows[windowRows.length - 1];
      const next = rows[nextIndex];
      if (
        !next ||
        next.file !== first.file ||
        next.hunk !== first.hunk ||
        next.line === undefined ||
        previous.line === undefined ||
        next.line !== previous.line + 1
      ) {
        break;
      }

      windowRows.push(next);
      joinedText += next.text;
      for (const pattern of secretPatterns) {
        const match = pattern.exec(joinedText);
        if (!match) continue;

        const matchRow = rowAtJoinedOffset(windowRows, match.index);
        if (!matchRow?.file || matchRow.line === undefined) continue;
        let matchRowOffset = 0;
        for (const row of windowRows) {
          if (row === matchRow) break;
          matchRowOffset += row.text.length;
        }
        const offsetInRow = match.index - matchRowOffset;
        // Single-line matches were already reported by the original scan above.
        if (offsetInRow + match[0].length <= matchRow.text.length) continue;

        const locationKey = `${matchRow.file}\0${matchRow.line}\0${offsetInRow}`;
        if (reportedLocations.has(locationKey)) continue;
        reportedLocations.add(locationKey);
        addViolation(
          violations,
          'SEC-PRIVATE-KEY',
          'BLOCK',
          'security',
          'Added content contains a value matching a private-key or credential pattern.',
          matchRow.file,
          matchRow.line,
        );
      }
    }
  }
}

function isManifest(path: string): boolean {
  return path.split('/').pop()?.toLowerCase() === 'androidmanifest.xml';
}

function scanManifestCapabilities(rows: AddedLine[], file: string, violations: ScanViolation[]): void {
  const additions = rows.map((row) => row.text).join('\n');
  const permissionPattern = /\bandroid:(?:name|permission)\s*=\s*["'](?:android\.permission\.)?([A-Z0-9_]+)["']/gi;
  let match: RegExpExecArray | null;
  while ((match = permissionPattern.exec(additions)) !== null) {
    const permission = match[1].toUpperCase();
    if (!forbiddenManifestPermissions.has(permission)) continue;
    const row = rowAtOffset(rows, match.index);
    addViolation(
      violations,
      'SEC-MANIFEST-CAPABILITY',
      'BLOCK',
      'security',
      'Added manifest declares a forbidden Android capability.',
      file,
      row?.line,
    );
  }
}

function scanFlavorAccessibility(rows: AddedLine[], file: string, violations: ScanViolation[]): void {
  if (!isManifest(file)) return;
  const normalized = normalizePath(file);
  const isPlay = /(?:^|\/)src\/play\//i.test(normalized);
  const isFossOrGithub = /(?:^|\/)src\/(?:foss|github)\//i.test(normalized);
  if (!isPlay && !isFossOrGithub) return;

  const additions = rows.map((row) => row.text).join('\n');
  const accessibility = /BIND_ACCESSIBILITY_SERVICE|android\.accessibilityservice\.AccessibilityService/i;
  const match = accessibility.exec(additions);
  if (!match) return;
  const accessibilityIndex = match.index;

  const openings = Array.from(additions.matchAll(/<service\b[^>]*>/gi));
  const closings = Array.from(additions.matchAll(/<\/service\s*>/gi));
  const opening = openings.filter((tag) => (tag.index ?? -1) <= accessibilityIndex).pop();
  const lastClosingIndex = closings
    .filter((tag) => (tag.index ?? -1) < accessibilityIndex)
    .reduce((latest, tag) => Math.max(latest, tag.index ?? -1), -1);
  const markerInService = opening !== undefined && (opening.index ?? -1) > lastClosingIndex;
  const explicitDefaultOff = markerInService &&
    /android:enabled\s*=\s*["']false["']|tools:node\s*=\s*["']remove["']/i.test(opening?.[0] ?? '');
  if (isPlay || !explicitDefaultOff) {
    const row = rowAtOffset(rows, match.index);
    addViolation(
      violations,
      'FLAVOR-BOUNDARY',
      'WARN',
      'security',
      isPlay
        ? 'Accessibility service declaration in the Play flavor requires human review.'
        : 'Accessibility service may be enabled by default in this flavor; verify it is disabled.',
      file,
      row?.line,
    );
  }
}

function scanBuildFlags(rows: AddedLine[], file: string, violations: ScanViolation[]): void {
  if (!file.toLowerCase().endsWith('.xml')) return;
  rows.forEach((row) => {
    if (/\b(?:android:)?debuggable\s*=\s*["']true["']/i.test(row.text)) {
      addViolation(
        violations,
        'BUILD-DEBUG-FLAG',
        'WARN',
        'reliability',
        'Added XML enables debugging; review the release impact.',
        file,
        row.line,
      );
    }
    if (/\b(?:android:)?allowBackup\s*=\s*["']true["']/i.test(row.text)) {
      addViolation(
        violations,
        'BUILD-DEBUG-FLAG',
        'WARN',
        'reliability',
        'Added XML broadens backup behavior; review backup policy.',
        file,
        row.line,
      );
    }
  });
}

export class DeterministicScanner {
  public static extractAddedContent(gitDiff: string): string {
    return gitDiff
      .split(/\r?\n/)
      .filter((line) => line.startsWith('+') && !line.startsWith('+++'))
      .map((line) => line.substring(1))
      .join('\n');
  }

  public static scan(changedFiles: string[], addedDiff: string): DeterministicScanResult {
    const violations: ScanViolation[] = [];
    const rows = addedLinesFromDiff(addedDiff, changedFiles);

    for (const row of rows) {
      for (const pattern of secretPatterns) {
        if (pattern.test(row.text)) {
          addViolation(
            violations,
            'SEC-PRIVATE-KEY',
            'BLOCK',
            'security',
            'Added content contains a value matching a private-key or credential pattern.',
            row.file,
            row.line,
          );
        }
      }
    }
    scanMultilineSecrets(rows, violations);

    const rowsByFile = new Map<string, AddedLine[]>();
    for (const row of rows) {
      if (!row.file) continue;
      const fileRows = rowsByFile.get(row.file) ?? [];
      fileRows.push(row);
      rowsByFile.set(row.file, fileRows);
    }

    for (const [file, fileRows] of rowsByFile) {
      if (isManifest(file)) scanManifestCapabilities(fileRows, file, violations);
      scanFlavorAccessibility(fileRows, file, violations);
      scanBuildFlags(fileRows, file, violations);
    }

    const hasBlockers = violations.some((violation) => violation.severity === 'BLOCK');
    return { passed: !hasBlockers, hasBlockers, violations };
  }
}

export function securityLabelsFor(violations: ScanViolation[]): string[] {
  return violations.some((violation) => violation.category === 'security' && violation.severity === 'BLOCK')
    ? ['security']
    : [];
}
