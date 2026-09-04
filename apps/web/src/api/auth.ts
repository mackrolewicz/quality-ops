import { useMutation } from "@tanstack/react-query";

import { queryClient } from "../lib/queryClient";
import { useAuth } from "../features/auth/useAuth";
import type { LoginInput } from "../features/auth/AuthContext";

export function useLogin() {
  const { login } = useAuth();
  return useMutation({
    mutationFn: (input: LoginInput) => login(input),
  });
}

export function useLogout() {
  const { logout } = useAuth();
  return useMutation({
    mutationFn: () => logout(),
    onSuccess: () => {
      queryClient.clear();
    },
  });
}
