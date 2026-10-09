export interface OpenAIConfig {
  baseUrl: string;
  apiKey: string;
  timeoutMs: number;
}

export type OpenAIEnvironment = Readonly<Record<string, string | undefined>>;
export type OpenAIModelRole = 'chief' | 'android_sec' | 'android_code';

const DEFAULT_TIMEOUT_MS = 420000;

export const DEFAULT_OPENAI_BASE_URL = 'https://api.openai.com/v1';
export const DEFAULT_OPENAI_ORIGIN = 'https://api.openai.com';
// Default reasoning effort when no explicit effort is configured. Overridable
// via POCKETGUARD_REASONING_EFFORT (global) or
// POCKETGUARD_MODEL_<ROLE>_REASONING_EFFORT (per-role); see
// resolveDefaultReasoningEffort and README Configuration.
export const DEFAULT_REASONING_EFFORT = 'high';

/**
 * Resolves the configured default reasoning effort. Precedence: per-call
 * env POCKETGUARD_REASONING_EFFORT, then POCKETGUARD_DEFAULT_REASONING_EFFORT
 * alias, then the builtin 'high'. Placeholders never count as configured.
 */
export function resolveDefaultReasoningEffort(env: OpenAIEnvironment): string {
  const global = env.POCKETGUARD_REASONING_EFFORT?.trim() ?? '';
  if (global && !containsPlaceholder(global)) return global;
  const alias = env.POCKETGUARD_DEFAULT_REASONING_EFFORT?.trim() ?? '';
  if (alias && !containsPlaceholder(alias)) return alias;
  return DEFAULT_REASONING_EFFORT;
}

/**
 * Whether reasoning effort 'none' is supported for this model. Only the
 * gpt-5 family is verified to accept the omission path used here (see
 * sendOpenAISingleTurn). Other reasoning families (e.g. o-series) reject or
 * ignore it, so resolveModelProfile returns undefined for model+none
 * (fail-closed, zero fetch before send).
 */
export function isReasoningNoneSupported(modelId: string): boolean {
  const lower = modelId.trim().toLowerCase();
  if (!lower) return false;
  return lower.startsWith('gpt-5');
}

export type ModelProfileKind = 'reasoning' | 'chat';

export interface ModelProfile {
  kind: ModelProfileKind;
  effort?: string;
}

function containsPlaceholder(value: string): boolean {
  return /\$\{[^}]*\}/.test(value);
}

export function resolveOpenAIConfig(
  env: OpenAIEnvironment,
  allowedOrigins: string[] = [],
): OpenAIConfig {
  const rawUrl = env.OPENAI_BASE_URL?.trim() ?? '';
  const apiKey = env.OPENAI_API_KEY?.trim() ?? '';
  if (!apiKey || containsPlaceholder(apiKey)) {
    throw new Error('OpenAI configuration not configured');
  }
  const configuredUrl = !rawUrl || containsPlaceholder(rawUrl) ? DEFAULT_OPENAI_BASE_URL : rawUrl;

  let parsed: URL;
  try {
    parsed = new URL(configuredUrl);
  } catch {
    throw new Error('OpenAI configuration invalid');
  }
  const effectiveOrigins = allowedOrigins.length > 0 ? allowedOrigins : [DEFAULT_OPENAI_ORIGIN];
  if (
    parsed.protocol !== 'https:' ||
    parsed.username.length > 0 ||
    parsed.password.length > 0 ||
    /@/.test(configuredUrl.slice(configuredUrl.indexOf('://') + 3).split(/[/?#]/, 1)[0] ?? '') ||
    configuredUrl.includes('#') ||
    configuredUrl.includes('?') ||
    parsed.hash.length > 0 ||
    parsed.search.length > 0 ||
    !effectiveOrigins.includes(parsed.origin)
  ) {
    throw new Error('OpenAI configuration rejected');
  }

  const baseUrl = parsed.toString().replace(/\/+$/, '');
  return { baseUrl, apiKey, timeoutMs: DEFAULT_TIMEOUT_MS };
}

const roleEnvironmentNames: Record<OpenAIModelRole, string> = {
  chief: 'POCKETGUARD_MODEL_CHIEF',
  android_sec: 'POCKETGUARD_MODEL_ANDROID_SEC',
  android_code: 'POCKETGUARD_MODEL_ANDROID_CODE',
};

function roleProfileEnvName(role: OpenAIModelRole): string {
  return `${roleEnvironmentNames[role]}_PROFILE`;
}

function roleEffortEnvName(role: OpenAIModelRole): string {
  return `${roleEnvironmentNames[role]}_REASONING_EFFORT`;
}

export function resolveRoleModel(role: OpenAIModelRole, env: OpenAIEnvironment): string {
  const modelId = env[roleEnvironmentNames[role]]?.trim() ?? '';
  if (!modelId || containsPlaceholder(modelId)) throw new Error('model not configured');
  return modelId;
}

function normalizeKind(value: unknown): ModelProfileKind | undefined {
  if (typeof value !== 'string') return undefined;
  const lower = value.trim().toLowerCase();
  if (lower === 'reasoning' || lower === 'reasoning-model') return 'reasoning';
  if (lower === 'chat' || lower === 'non-reasoning' || lower === 'nonreasoning' || lower === 'standard') return 'chat';
  return undefined;
}

function parseProfileString(raw: string, defaultEffort: string = DEFAULT_REASONING_EFFORT): ModelProfile | undefined {
  const trimmed = raw.trim();
  if (!trimmed || containsPlaceholder(trimmed)) return undefined;
  if (trimmed.startsWith('{')) {
    try {
      const parsed: unknown = JSON.parse(trimmed);
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        const record = parsed as Record<string, unknown>;
        const kind = normalizeKind(record.type ?? record.kind ?? record.profile ?? record.mode);
        if (!kind) return undefined;
        if (kind === 'chat') return { kind };
        const effortRaw = record.effort ?? record.reasoningEffort;
        const effort = typeof effortRaw === 'string' && effortRaw.trim() ? effortRaw.trim() : defaultEffort;
        return { kind, effort };
      }
    } catch {
      return undefined;
    }
    return undefined;
  }
  const parts = trimmed.split(/[:/=]/).map((part) => part.trim()).filter(Boolean);
  if (parts.length === 0) return undefined;
  const kind = normalizeKind(parts[0]);
  if (!kind) return undefined;
  if (kind === 'chat') return { kind };
  const effort = parts[1] ? parts[1].trim() : defaultEffort;
  return { kind, effort: effort || defaultEffort };
}

function lookupProfileInJsonMap(parsed: unknown, modelId: string, defaultEffort: string = DEFAULT_REASONING_EFFORT): ModelProfile | undefined {
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return undefined;
  const record = parsed as Record<string, unknown>;
  const lowerId = modelId.toLowerCase();
  const reasoningList = record.reasoning ?? record.reasoningModels;
  const chatList = record.chat ?? record.chatModels;
  if (Array.isArray(reasoningList) || Array.isArray(chatList)) {
    const matches = (entries: unknown): boolean => {
      if (!Array.isArray(entries)) return false;
      return entries.some((entry) => {
        if (typeof entry !== 'string' || !entry.trim()) return false;
        const needle = entry.trim().toLowerCase();
        return lowerId === needle || lowerId.startsWith(needle);
      });
    };
    const inReasoning = matches(reasoningList);
    const inChat = matches(chatList);
    if (inReasoning && !inChat) {
      const effortRaw = record.reasoningEffort ?? record.effort;
      const effort = typeof effortRaw === 'string' && effortRaw.trim() ? effortRaw.trim() : defaultEffort;
      return { kind: 'reasoning', effort };
    }
    if (inChat && !inReasoning) return { kind: 'chat' };
    if (inReasoning && inChat) return undefined;
  }
  for (const key of Object.keys(record)) {
    if (key.toLowerCase() === lowerId) {
      const value = record[key];
      if (typeof value === 'string') {
        const profile = parseProfileString(value, defaultEffort);
        if (profile) return profile;
      } else if (value && typeof value === 'object' && !Array.isArray(value)) {
        const obj = value as Record<string, unknown>;
        const kind = normalizeKind(obj.type ?? obj.kind ?? obj.profile ?? obj.mode);
        if (kind === 'chat') return { kind: 'chat' };
        if (kind === 'reasoning') {
          const effortRaw = obj.effort ?? obj.reasoningEffort;
          const effort = typeof effortRaw === 'string' && effortRaw.trim() ? effortRaw.trim() : defaultEffort;
          return { kind: 'reasoning', effort };
        }
      }
      return undefined;
    }
  }
  let best: ModelProfile | undefined;
  let bestLength = -1;
  for (const key of Object.keys(record)) {
    const trimmedKey = key.trim();
    if (trimmedKey.length < 3) continue;
    const lowerKey = trimmedKey.toLowerCase();
    if (lowerKey === 'reasoning' || lowerKey === 'chat') continue;
    if (!lowerId.startsWith(lowerKey)) continue;
    const value = record[key];
    let candidate: ModelProfile | undefined;
    if (typeof value === 'string') candidate = parseProfileString(value, defaultEffort);
    else if (value && typeof value === 'object' && !Array.isArray(value)) {
      const obj = value as Record<string, unknown>;
      const kind = normalizeKind(obj.type ?? obj.kind ?? obj.profile ?? obj.mode);
      if (kind === 'chat') candidate = { kind: 'chat' };
      else if (kind === 'reasoning') {
        const effortRaw = obj.effort ?? obj.reasoningEffort;
        candidate = {
          kind: 'reasoning',
          effort: typeof effortRaw === 'string' && effortRaw.trim() ? effortRaw.trim() : defaultEffort,
        };
      }
    } else continue;
    if (candidate && trimmedKey.length > bestLength) {
      best = candidate;
      bestLength = trimmedKey.length;
    }
  }
  return best;
}

function effortForModel(modelId: string, env: OpenAIEnvironment): string {
  for (const role of ['chief', 'android_sec', 'android_code'] as const) {
    const configuredModel = env[roleEnvironmentNames[role]]?.trim() ?? '';
    if (configuredModel && configuredModel === modelId) {
      const raw = env[roleEffortEnvName(role)]?.trim() ?? '';
      if (raw && !containsPlaceholder(raw)) return raw;
      break;
    }
  }
  return resolveDefaultReasoningEffort(env);
}

function isReasoningFamily(modelId: string): boolean {
  return modelId.toLowerCase().startsWith('gpt-5');
}

function isChatFamily(modelId: string): boolean {
  const lower = modelId.toLowerCase();
  return lower.startsWith('gpt-4o') || lower.startsWith('gpt-4.1');
}

// Per-model request profile. Explicit configuration wins; builtin family
// defaults cover GPT-5 reasoning and gpt-4o/gpt-4.1 chat only. Anything else
// without an explicit profile returns undefined so callers fail closed
// without sending a guessed body. A reasoning+none profile for a model that
// does not support none (see isReasoningNoneSupported) likewise returns
// undefined so sendOpenAISingleTurn fails closed with zero fetch.
export function resolveModelProfile(modelId: string, env: OpenAIEnvironment): ModelProfile | undefined {
  const trimmedId = modelId.trim();
  if (!trimmedId || containsPlaceholder(trimmedId)) return undefined;
  const gateNone = (profile: ModelProfile | undefined): ModelProfile | undefined => {
    if (!profile) return undefined;
    if (profile.kind === 'reasoning' && (profile.effort ?? '').trim().toLowerCase() === 'none' && !isReasoningNoneSupported(trimmedId)) {
      return undefined;
    }
    return profile;
  };
  const defaultEffort = resolveDefaultReasoningEffort(env);

  for (const role of ['chief', 'android_sec', 'android_code'] as const) {
    const configuredModel = env[roleEnvironmentNames[role]]?.trim() ?? '';
    if (configuredModel && configuredModel === trimmedId) {
      const rawProfile = env[roleProfileEnvName(role)]?.trim() ?? '';
      if (rawProfile) {
        if (containsPlaceholder(rawProfile)) return undefined;
        const parsed = parseProfileString(rawProfile, defaultEffort);
        if (!parsed) return undefined;
        if (parsed.kind === 'reasoning') {
          const override = env[roleEffortEnvName(role)]?.trim() ?? '';
          const global = env.POCKETGUARD_REASONING_EFFORT?.trim() ?? '';
          const alias = env.POCKETGUARD_DEFAULT_REASONING_EFFORT?.trim() ?? '';
          if (override && !containsPlaceholder(override)) return gateNone({ kind: 'reasoning', effort: override });
          if (global && !containsPlaceholder(global)) return gateNone({ kind: 'reasoning', effort: global });
          if (alias && !containsPlaceholder(alias)) return gateNone({ kind: 'reasoning', effort: alias });
        }
        return gateNone(parsed);
      }
      break;
    }
  }

  const rawMap = env.POCKETGUARD_MODEL_PROFILES?.trim() ?? '';
  if (rawMap) {
    if (containsPlaceholder(rawMap)) return undefined;
    let parsed: unknown;
    try {
      parsed = JSON.parse(rawMap);
    } catch {
      return undefined;
    }
    const found = lookupProfileInJsonMap(parsed, trimmedId, defaultEffort);
    if (found) {
      if (found.kind === 'reasoning' && (!found.effort || !found.effort.trim())) {
        return gateNone({ kind: 'reasoning', effort: effortForModel(trimmedId, env) });
      }
      return gateNone(found);
    }
    // An explicitly configured map is authoritative: a model absent from it
    // is unknown rather than guessed from builtin families.
    return undefined;
  }

  const rawGlobal = env.POCKETGUARD_MODEL_PROFILE?.trim() ?? '';
  if (rawGlobal) {
    if (containsPlaceholder(rawGlobal)) return undefined;
    const parsed = parseProfileString(rawGlobal, defaultEffort);
    if (!parsed) return undefined;
    if (parsed.kind === 'reasoning' && (!parsed.effort || !parsed.effort.trim())) {
      return gateNone({ kind: 'reasoning', effort: effortForModel(trimmedId, env) });
    }
    return gateNone(parsed);
  }

  if (isReasoningFamily(trimmedId)) return gateNone({ kind: 'reasoning', effort: effortForModel(trimmedId, env) });
  if (isChatFamily(trimmedId)) return { kind: 'chat' };
  return undefined;
}

export interface SendOpenAISingleTurnOptions {
  modelId: string;
  systemPrompt: string;
  userPrompt: string;
  temperature?: number;
  topP?: number;
  maxOutputTokens?: number;
  timeoutMs?: number;
  allowedOrigins?: string[];
  /** An injection seam for offline callers and tests; omitted in production. */
  env?: OpenAIEnvironment;
}

export interface OpenAISingleTurnResult {
  content: string;
  modelId: string;
}

function errorWithStatus(status: number): Error & { statusCode: number } {
  const error = new Error('OpenAI request failed') as Error & { statusCode: number };
  error.statusCode = status;
  return error;
}

function extractOutputText(payload: unknown): string {
  if (!payload || typeof payload !== 'object') return '';
  const output = (payload as { output?: unknown }).output;
  if (!Array.isArray(output)) return '';

  const pieces: string[] = [];
  for (const item of output) {
    if (!item || typeof item !== 'object') continue;
    const content = (item as { content?: unknown }).content;
    if (!Array.isArray(content)) continue;
    for (const part of content) {
      if (!part || typeof part !== 'object') continue;
      const textPart = part as { type?: unknown; text?: unknown };
      if (textPart.type === 'output_text' && typeof textPart.text === 'string') pieces.push(textPart.text);
    }
  }
  return pieces.join('');
}

export async function sendOpenAISingleTurn(options: SendOpenAISingleTurnOptions): Promise<OpenAISingleTurnResult> {
  if (!options.modelId.trim() || containsPlaceholder(options.modelId)) throw new Error('model not configured');
  const env = options.env ?? process.env;
  const profile = resolveModelProfile(options.modelId, env);
  // Fail closed before any network I/O: an unknown/unconfigured profile must
  // not be guessed into a request body that the model rejects with 400.
  if (!profile) throw new Error('model profile not configured');
  const config = resolveOpenAIConfig(env, options.allowedOrigins ?? []);
  const timeoutMs = options.timeoutMs ?? config.timeoutMs;
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new Error('OpenAI timeout invalid');

  const body: Record<string, unknown> = {
    model: options.modelId,
    input: [
      { role: 'system', content: options.systemPrompt },
      { role: 'user', content: options.userPrompt },
    ],
    max_output_tokens: options.maxOutputTokens ?? 4096,
    stream: false,
  };
  const effort = (profile.effort ?? '').trim();
  const reasoningActive = profile.kind === 'reasoning' && effort.toLowerCase() !== 'none';
  if (reasoningActive) {
    // GPT-5-class reasoning with effort != none rejects temperature/top_p.
    body.reasoning = { effort: effort || resolveDefaultReasoningEffort(env) };
  } else {
    // P2 #5 Responses API semantics (pinned): effort 'none' OMITS the
    // reasoning key entirely (never sends reasoning:{effort:'none'}).
    // Rationale: omitting reasoning selects the model default non-reasoning
    // path, while an explicit {effort:'none'} is rejected with 400 on models
    // that do not support none (see isReasoningNoneSupported, which fails
    // closed before any fetch). Reference:
    // https://platform.openai.com/docs/api-reference/responses/create
    // (reasoning.effort; omitted reasoning == default behavior).
    // Non-reasoning (or reasoning with effort none): never send reasoning.
    // Temperature/top_p are opt-in only, so the default body stays minimal.
    if (options.temperature !== undefined) body.temperature = options.temperature;
    if (options.topP !== undefined) body.top_p = options.topP;
  }

  const endpoint = new URL('responses', `${config.baseUrl.replace(/\/+$/, '')}/`).toString();
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    let response: Response;
    try {
      response = await fetch(endpoint, {
        method: 'POST',
        redirect: 'error',
        headers: {
          Authorization: `Bearer ${config.apiKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
    } catch {
      throw new Error('OpenAI request failed');
    }

    // No retry or fallback here: a 400 for an unsupported parameter must
    // surface with its status so the orchestrator converges to INCONCLUSIVE
    // without downgrading to APPROVE or writing labels.
    if (!response.ok) throw errorWithStatus(response.status);

    let payload: unknown;
    try {
      payload = await response.json();
    } catch {
      throw new Error('OpenAI response invalid');
    }
    const content = extractOutputText(payload).trim();
    if (!content) throw new Error('OpenAI response empty');
    return { content, modelId: options.modelId };
  } finally {
    clearTimeout(timeout);
  }
}
