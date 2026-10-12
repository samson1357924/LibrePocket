// Canonical deterministic redaction for model-bound prompts.
//
// Single source of truth for the credential patterns previously duplicated
// across orchestrator.ts (redactSensitiveText) and github_runner.ts
// (redactForModel / safeString). Callers that need truncation or control-char
// handling wrap this function; this function itself performs no truncation so
// upstream context budgets (MAX_DIFF_LENGTH / MAX_ISSUE_CONTEXT_LENGTH)
// remain the sole truncation authority.
//
// Order contract: deterministic scanning must run on the ORIGINAL content
// first; only the AI-bound copy is redacted. Scanning redacted text would
// miss credentials (patterns replaced), so never scan after redacting.
export function redactForModel(value: string): string {
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
