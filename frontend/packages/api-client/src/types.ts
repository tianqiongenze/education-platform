export type Fetcher = (
  url: string,
  init: RequestInit
) => Promise<unknown>;

export interface TokenStorage {
  getAccessToken(): string | null;
  setAccessToken(token: string): void;
  getRefreshToken(): string | null;
  setRefreshToken(token: string): void;
  clear(): void;
}

/** Per-request options shared by all HTTP verb helpers. */
export interface RequestOptions {
  /** External abort signal; linked with the per-request timeout controller. */
  signal?: AbortSignal | undefined;
  /** Per-request timeout in ms (overrides `ClientConfig.timeoutMs`; 0 disables). */
  timeoutMs?: number | undefined;
  /**
   * Override the client-level GET auto-retry for this request.
   * Idempotent GETs are retried once on network-level failures by default.
   */
  retry?: boolean | undefined;
}

export interface ClientConfig {
  apiBaseUrl: string;
  fetcher: Fetcher;
  tokenStorage: TokenStorage;
  getCellId?: () => string | undefined;
  refreshUrl?: string;
  zodValidate?: boolean;
  /**
   * Default per-request timeout in ms. 0 (default) disables the timeout —
   * requests then only abort via an explicit `RequestOptions.signal`.
   */
  timeoutMs?: number;
  /**
   * Automatically retry idempotent GET requests once on network-level
   * failures (fetch rejects without an HTTP status). Defaults to true.
   */
  getAutoRetry?: boolean;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
}

export interface LoginRequest {
  username: string;
  password: string;
}
