interface StoredTokens {
  accessToken: string;
  refreshToken: string;
}

let tokens: StoredTokens | null = null;

export const tokenStore = {
  get(): StoredTokens | null {
    return tokens;
  },
  getAccessToken(): string | null {
    return tokens?.accessToken ?? null;
  },
  getRefreshToken(): string | null {
    return tokens?.refreshToken ?? null;
  },
  set(next: StoredTokens): void {
    tokens = next;
  },
  clear(): void {
    tokens = null;
  },
};
