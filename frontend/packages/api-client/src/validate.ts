import type { ClientConfig } from './types';
import { ApiError } from './error';

const isProduction = (): boolean => {
  try {
    return process.env.NODE_ENV === 'production';
  } catch {
    return false;
  }
};

/**
 * Matches a URL to a generated Zod schema for response validation.
 *
 * M0: generated Zod schemas are not yet available, so this always returns
 * undefined and the validation interceptor is a pass-through.
 *
 * M1+: wire up generated Zod schemas (e.g. from ./generated/zod-schemas)
 * to enable real response-shape validation here.
 */
function matchSchema(_url: string): { safeParse: (data: unknown) => { success: boolean; data?: unknown; error?: { message: string; issues: unknown[] } } } | undefined {
  return undefined;
}

function reportValidationError(url: string, error: { message: string; issues: unknown[] }): void {
  // In a real implementation this would post to a monitoring endpoint.
  // For now we just log to console.warn to avoid breaking production.
  try {
    console.warn('[Zod] API response validation failed (prod, reported)', { url, message: error.message, issues: error.issues });
  } catch {
    // ignore logging failures
  }
}

export function createValidationInterceptor(config: ClientConfig) {
  const shouldValidate = config.zodValidate ?? true;
  const sampleRate = isProduction() ? 0.1 : 1.0;

  return function validate(url: string, data: unknown): unknown {
    if (!shouldValidate) return data;
    if (Math.random() > sampleRate) return data;

    const schema = matchSchema(url);
    if (!schema) return data;

    const result = schema.safeParse(data);
    if (!result.success) {
      const errorObj = { message: result.error?.message ?? 'validation failed', issues: result.error?.issues ?? [] };
      if (!isProduction()) {
        // Dev: throw detailed error so issues surface immediately.
        try {
          console.error('[Zod] API response validation failed:', { url, errors: errorObj.issues });
        } catch {
          // ignore logging failures
        }
        throw new ApiError(500, `Zod validation failed: ${errorObj.message}`);
      }
      // Prod: report but return original data to avoid white-screening users.
      reportValidationError(url, errorObj);
      return data;
    }
    return result.data;
  };
}
