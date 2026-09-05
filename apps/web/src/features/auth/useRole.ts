import type { Role } from "../../types/auth";
import { useAuth } from "./useAuth";

export function useRole(): Role | null {
  const { user } = useAuth();
  return user?.role ?? null;
}

export function useHasRole(roles: Role[]): boolean {
  const role = useRole();
  return role !== null && roles.includes(role);
}
