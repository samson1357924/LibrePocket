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
export const DEFAULT_REASONING_EFFORT = 'high';

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

function parseProfileString(raw: string): ModelProfile | undefined {
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
        const effort = typeof effortRaw === 'string' && effortRaw.trim() ? effortRaw.trim() : DEFAULT_REASONING_EFFORT;
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
  const effort = parts[1] ? parts[1].trim() : DEFAULT_REASONING_EFFORT;
  return { kind, effort: effort || DEFAULT_REASONING_EFFORT };
}

function lookupProfileInJsonMap(parsed: unknown, modelId: string): ModelProfile | undefined {
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
      const effort = typeof effortRaw === 'string' && effortRaw.trim() ? effortRaw.trim() : DEFAULT_REASONING_EFFORT;
      return { kind: 'reasoning', effort };
    }
    if (inChat && !inReasoning) return { kind: 'chat' };
    if (inReasoning && inChat) return undefined;
  }
  for (const key of Object.keys(record)) {
    if (key.toLowerCase() === lowerId) {
      const value = record[key];
      if (typeof value === 'string') {
        const profile = parseProfileString(value);
        if (profile) return profile;
      } else if (value && typeof value === 'object' && !Array.isArray(value)) {
        const obj = value as Record<string, unknown>;
        const kind = normalizeKind(obj.type ?? obj.kind ?? obj.profile ?? obj.mode);
        if (kind === 'chat') return { kind: 'chat' };
        if (kind === 'reasoning') {
          const effortRaw = obj.effort ?? obj.reasoningEffort;
          const effort = typeof effortRaw === 'string' && effortRaw.trim() ? effortRaw.trim() : DEFAULT_REASONING_EFFORT;
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
    if (typeof value === 'string') candidate = parseProfileString(value);
    else if (value && typeof value === 'object' && !Array.isArray(value)) {
      const obj = value as Record<string, unknown>;
      const kind = normalizeKind(obj.type ?? obj.kind ?? obj.profile ?? obj.mode);
      if (kind === 'chat') candidate = { kind: 'chat' };
      else if (kind === 'reasoning') {
        const effortRaw = obj.effort ?? obj.reasoningEffort;
        candidate = {
          kind: 'reasoning',
          effort: typeof effortRaw === 'string' && effortRaw.trim() ? effortRaw.trim() : DEFAULT_REASONING_EFFORT,
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
  const global = env.POCKETGUARD_REASONING_EFFORT?.trim() ?? '';
  if (global && !containsPlaceholder(global)) return global;
  return DEFAULT_REASONING_EFFORT;
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
// without sending a guessed body.
export function resolveModelProfile(modelId: string, env: OpenAIEnvironment): ModelProfile | undefined {
  const trimmedId = modelId.trim();
  if (!trimmedId || containsPlaceholder(trimmedId)) return undefined;

  for (const role of ['chief', 'android_sec', 'android_code'] as const) {
    const configuredModel = env[roleEnvironmentNames[role]]?.trim() ?? '';
    if (configuredModel && configuredModel === trimmedId) {
      const rawProfile = env[roleProfileEnvName(role)]?.trim() ?? '';
      if (rawProfile) {
        if (containsPlaceholder(rawProfile)) return undefined;
        const parsed = parseProfileString(rawProfile);
        if (!parsed) return undefined;
        if (parsed.kind === 'reasoning') {
          const override = env[roleEffortEnvName(role)]?.trim() ?? '';
          const global = env.POCKETGUARD_REASONING_EFFORT?.trim() ?? '';
          if (override && !containsPlaceholder(override)) return { kind: 'reasoning', effort: override };
          if (global && !containsPlaceholder(global)) return { kind: 'reasoning', effort: global };
        }
        return parsed;
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
    const found = lookupProfileInJsonMap(parsed, trimmedId);
    if (found) {
      if (found.kind === 'reasoning' && (!found.effort || !found.effort.trim())) {
        return { kind: 'reasoning', effort: effortForModel(trimmedId, env) };
      }
      return found;
    }
    // An explicitly configured map is authoritative: a model absent from it
    // is unknown rather than guessed from builtin families.
    return undefined;
  }

  const rawGlobal = env.POCKETGUARD_MODEL_PROFILE?.trim() ?? '';
  if (rawGlobal) {
    if (containsPlaceholder(rawGlobal)) return undefined;
    const parsed = parseProfileString(rawGlobal);
    if (!parsed) return undefined;
    if (parsed.kind === 'reasoning' && (!parsed.effort || !parsed.effort.trim())) {
      return { kind: 'reasoning', effort: effortForModel(trimmedId, env) };
    }
    return parsed;
  }

  if (isReasoningFamily(trimmedId)) return { kind: 'reasoning', effort: effortForModel(trimmedId, env) };
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
    body.reasoning = { effort: effort || DEFAULT_REASONING_EFFORT };
  } else {
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
