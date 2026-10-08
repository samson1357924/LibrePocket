export interface CpaConfig {
  baseUrl: string;
  apiKey: string;
  timeoutMs: number;
}

export type CpaEnvironment = Readonly<Record<string, string | undefined>>;
export type CpaModelRole = 'chief' | 'android_sec' | 'android_code';

const DEFAULT_TIMEOUT_MS = 420000;

function containsPlaceholder(value: string): boolean {
  return /\$\{[^}]*\}/.test(value);
}

export function resolveCpaConfig(
  env: CpaEnvironment,
  allowedOrigins: string[] = [],
): CpaConfig {
  const configuredUrl = env.CPA_BASE_URL?.trim() ?? '';
  const apiKey = env.CPA_API_KEY?.trim() ?? '';
  if (!configuredUrl || !apiKey || containsPlaceholder(configuredUrl) || containsPlaceholder(apiKey)) {
    throw new Error('CPA configuration not configured');
  }

  let parsed: URL;
  try {
    parsed = new URL(configuredUrl);
  } catch {
    throw new Error('CPA configuration invalid');
  }
  if (
    parsed.protocol !== 'https:' ||
    parsed.username.length > 0 ||
    parsed.password.length > 0 ||
    /@/.test(configuredUrl.slice(configuredUrl.indexOf('://') + 3).split(/[/?#]/, 1)[0] ?? '') ||
    configuredUrl.includes('#') ||
    configuredUrl.includes('?') ||
    parsed.hash.length > 0 ||
    parsed.search.length > 0 ||
    !allowedOrigins.includes(parsed.origin)
  ) {
    throw new Error('CPA configuration rejected');
  }

  const baseUrl = parsed.toString().replace(/\/+$/, '');
  return { baseUrl, apiKey, timeoutMs: DEFAULT_TIMEOUT_MS };
}

const roleEnvironmentNames: Record<CpaModelRole, string> = {
  chief: 'POCKETGUARD_MODEL_CHIEF',
  android_sec: 'POCKETGUARD_MODEL_ANDROID_SEC',
  android_code: 'POCKETGUARD_MODEL_ANDROID_CODE',
};

export function resolveRoleModel(role: CpaModelRole, env: CpaEnvironment): string {
  const modelId = env[roleEnvironmentNames[role]]?.trim() ?? '';
  if (!modelId || containsPlaceholder(modelId)) throw new Error('model not configured');
  return modelId;
}

export interface SendCpaSingleTurnOptions {
  modelId: string;
  systemPrompt: string;
  userPrompt: string;
  temperature?: number;
  maxOutputTokens?: number;
  timeoutMs?: number;
  allowedOrigins?: string[];
  /** An injection seam for offline callers and tests; omitted in production. */
  env?: CpaEnvironment;
}

export interface CpaSingleTurnResult {
  content: string;
  modelId: string;
}

function errorWithStatus(status: number): Error & { statusCode: number } {
  const error = new Error('CPA request failed') as Error & { statusCode: number };
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

export async function sendCpaSingleTurn(options: SendCpaSingleTurnOptions): Promise<CpaSingleTurnResult> {
  if (!options.modelId.trim() || containsPlaceholder(options.modelId)) throw new Error('model not configured');
  const config = resolveCpaConfig(options.env ?? process.env, options.allowedOrigins ?? []);
  const timeoutMs = options.timeoutMs ?? config.timeoutMs;
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new Error('CPA timeout invalid');

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
        body: JSON.stringify({
          model: options.modelId,
          input: [
            { role: 'system', content: options.systemPrompt },
            { role: 'user', content: options.userPrompt },
          ],
          temperature: options.temperature ?? 0.2,
          max_output_tokens: options.maxOutputTokens ?? 4096,
          reasoning: { effort: 'high' },
          stream: false,
        }),
        signal: controller.signal,
      });
    } catch {
      throw new Error('CPA request failed');
    }

    if (!response.ok) throw errorWithStatus(response.status);

    let payload: unknown;
    try {
      payload = await response.json();
    } catch {
      throw new Error('CPA response invalid');
    }
    const content = extractOutputText(payload).trim();
    if (!content) throw new Error('CPA response empty');
    return { content, modelId: options.modelId };
  } finally {
    clearTimeout(timeout);
  }
}
