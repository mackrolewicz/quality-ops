import axios, {
  AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from "axios";

import { ApiError } from "./ApiError";
import { authBridge } from "./authBridge";
import { tokenStore } from "./tokenStore";
import type { AuthTokenResponse, Envelope } from "./types";

const baseURL = import.meta.env.VITE_API_URL ?? "http://localhost:8090";

interface RetriableConfig extends InternalAxiosRequestConfig {
  __isRetry?: boolean;
}

export const api = axios.create({ baseURL });

api.interceptors.request.use((config) => {
  const token = tokenStore.getAccessToken();
  if (token) {
    config.headers.set("Authorization", `Bearer ${token}`);
  }
  return config;
});

let refreshPromise: Promise<string> | null = null;

async function runRefresh(): Promise<string> {
  const refreshToken = tokenStore.getRefreshToken();
  if (!refreshToken) {
    throw new ApiError("UNAUTHORIZED", "No refresh token available");
  }
  const res = await axios.post<Envelope<AuthTokenResponse>>(
    `${baseURL}/auth/refresh`,
    { refreshToken },
  );
  const next = res.data.data;
  tokenStore.set({
    accessToken: next.accessToken,
    refreshToken: next.refreshToken,
  });
  return next.accessToken;
}

function isAuthUrl(url: string | undefined): boolean {
  return Boolean(url && url.includes("/auth/"));
}

// End the session once, even when several requests fail their 401 handling
// concurrently: only the first caller that still sees tokens clears them and
// notifies; the rest short-circuit so listeners get a single logout signal.
function endSession(error: AxiosError<Envelope<unknown>>): Promise<never> {
  if (tokenStore.getAccessToken() || tokenStore.getRefreshToken()) {
    tokenStore.clear();
    authBridge.emitUnauthenticated();
  }
  return Promise.reject(toApiError(error));
}

export function toApiError(error: AxiosError<Envelope<unknown>>): ApiError {
  const body = error.response?.data?.error;
  if (body) {
    return new ApiError(
      body.code,
      body.message,
      body.details ?? null,
      error.response?.status ?? null,
    );
  }
  return new ApiError(
    "NETWORK_ERROR",
    error.message || "Request failed",
    null,
    error.response?.status ?? null,
  );
}

api.interceptors.response.use(
  (res: AxiosResponse) => res,
  async (error: AxiosError<Envelope<unknown>>) => {
    const config = error.config as RetriableConfig | undefined;
    const status = error.response?.status;

    if (status === 401 && config && !isAuthUrl(config.url)) {
      if (config.__isRetry) {
        // Refresh succeeded but the replayed request still got 401 — the
        // session is no longer usable. Drop tokens and force re-auth.
        return endSession(error);
      }
      try {
        if (!refreshPromise) {
          refreshPromise = runRefresh().finally(() => {
            refreshPromise = null;
          });
        }
        const newToken = await refreshPromise;
        config.__isRetry = true;
        config.headers.set("Authorization", `Bearer ${newToken}`);
        return api.request(config);
      } catch {
        return endSession(error);
      }
    }

    return Promise.reject(toApiError(error));
  },
);
