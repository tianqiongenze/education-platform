import { useMutation } from '@tanstack/react-query';
import type { ApiClient } from '@ep/api-client';

export interface LoginInput {
  username: string;
  password: string;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
}

export function useLogin(client: ApiClient) {
  return useMutation({
    mutationFn: async (input: LoginInput): Promise<TokenResponse> => {
      return client.auth.login(input.username, input.password);
    },
  });
}
