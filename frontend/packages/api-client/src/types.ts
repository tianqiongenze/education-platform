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

export interface ClientConfig {
  apiBaseUrl: string;
  fetcher: Fetcher;
  tokenStorage: TokenStorage;
  getCellId?: () => string | undefined;
  refreshUrl?: string;
  zodValidate?: boolean;
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
