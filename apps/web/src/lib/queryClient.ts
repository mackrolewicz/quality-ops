import { QueryClient } from "@tanstack/react-query";

import { isApiError } from "../api/ApiError";

export const retryOnceUnless4xx = (
  failureCount: number,
  error: unknown,
): boolean => {
  if (
    isApiError(error) &&
    error.status != null &&
    error.status >= 400 &&
    error.status < 500
  ) {
    return false;
  }
  return failureCount < 1;
};

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: retryOnceUnless4xx,
      refetchOnWindowFocus: false,
    },
    mutations: { retry: false },
  },
});
