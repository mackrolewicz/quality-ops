import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import {
  AuthContext,
  type AuthContextValue,
} from "../src/features/auth/AuthContext";
import type { AuthUser, Role } from "../src/types/auth";

export function makeQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

export function makeUser(role: Role = "OWNER"): AuthUser {
  return {
    userId: "11111111-1111-1111-1111-111111111111",
    orgId: "22222222-2222-2222-2222-222222222222",
    role,
    email: "user@demo.com",
  };
}

interface MockAuthProviderProps {
  children: ReactNode;
  user?: AuthUser | null;
  value?: Partial<AuthContextValue>;
}

export function MockAuthProvider({
  children,
  user = makeUser(),
  value,
}: MockAuthProviderProps) {
  const ctx: AuthContextValue = {
    user,
    isAuthenticated: user !== null,
    login: async () => {},
    logout: async () => {},
    ...value,
  };
  return <AuthContext.Provider value={ctx}>{children}</AuthContext.Provider>;
}

interface WrapperProps {
  children: ReactNode;
  queryClient?: QueryClient;
  user?: AuthUser | null;
  authValue?: Partial<AuthContextValue>;
}

export function AppProviders({
  children,
  queryClient,
  user,
  authValue,
}: WrapperProps) {
  const client = queryClient ?? makeQueryClient();
  return (
    <QueryClientProvider client={client}>
      <MockAuthProvider user={user} value={authValue}>
        {children}
      </MockAuthProvider>
    </QueryClientProvider>
  );
}
