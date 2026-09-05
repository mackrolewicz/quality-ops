import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { api } from "../../api/client";
import { authBridge } from "../../api/authBridge";
import { tokenStore } from "../../api/tokenStore";
import { unwrap } from "../../api/envelope";
import type { AuthTokenResponse, Envelope } from "../../api/types";
import type { AuthUser } from "../../types/auth";
import { AuthContext, type LoginInput } from "./AuthContext";
import { decodeJwt, extractRole, isExpired } from "./jwt";

interface AuthProviderProps {
  children: React.ReactNode;
}

const REFRESH_SKEW_MS = 60_000;

function toAuthUser(accessToken: string, email: string): AuthUser {
  const payload = decodeJwt(accessToken);
  if (!payload) {
    throw new Error("Received an invalid access token");
  }
  return {
    userId: payload.sub,
    orgId: payload.org_id,
    role: extractRole(payload),
    email,
  };
}

export function AuthProvider({ children }: AuthProviderProps) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const refreshTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearTimer = useCallback(() => {
    if (refreshTimer.current) {
      clearTimeout(refreshTimer.current);
      refreshTimer.current = null;
    }
  }, []);

  const applyTokens = useCallback(
    (tokens: AuthTokenResponse, email: string) => {
      const nextUser = toAuthUser(tokens.accessToken, email);
      tokenStore.set({
        accessToken: tokens.accessToken,
        refreshToken: tokens.refreshToken,
      });
      setUser(nextUser);

      clearTimer();
      const payload = decodeJwt(tokens.accessToken);
      const msUntilRefresh = payload
        ? payload.exp * 1000 - Date.now() - REFRESH_SKEW_MS
        : 0;
      if (msUntilRefresh > 0) {
        refreshTimer.current = setTimeout(() => {
          const refreshToken = tokenStore.getRefreshToken();
          if (!refreshToken) return;
          api
            .post<Envelope<AuthTokenResponse>>("/auth/refresh", { refreshToken })
            .then((res) => {
              const next = unwrap(res);
              tokenStore.set({
                accessToken: next.accessToken,
                refreshToken: next.refreshToken,
              });
              setUser(toAuthUser(next.accessToken, email));
            })
            .catch(() => {
              tokenStore.clear();
              setUser(null);
            });
        }, msUntilRefresh);
      }
    },
    [clearTimer],
  );

  const login = useCallback(
    async ({ email, password }: LoginInput) => {
      const res = await api.post<Envelope<AuthTokenResponse>>("/auth/login", {
        email,
        password,
      });
      applyTokens(unwrap(res), email);
    },
    [applyTokens],
  );

  const logout = useCallback(async () => {
    const refreshToken = tokenStore.getRefreshToken();
    clearTimer();
    try {
      if (refreshToken) {
        await api.post("/auth/logout", { refreshToken });
      }
    } catch {
      // logout is best-effort; local state is cleared regardless
    } finally {
      tokenStore.clear();
      setUser(null);
    }
  }, [clearTimer]);

  useEffect(() => {
    return authBridge.onUnauthenticated(() => {
      clearTimer();
      tokenStore.clear();
      setUser(null);
    });
  }, [clearTimer]);

  useEffect(() => clearTimer, [clearTimer]);

  const isAuthenticated = useMemo(() => {
    if (!user) return false;
    const token = tokenStore.getAccessToken();
    if (!token) return false;
    const payload = decodeJwt(token);
    return payload !== null && !isExpired(payload);
  }, [user]);

  const value = useMemo(
    () => ({ user, isAuthenticated, login, logout }),
    [user, isAuthenticated, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
