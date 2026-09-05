import type { ReactNode } from "react";

import type { Role } from "../../types/auth";
import { useHasRole } from "./useRole";

interface CanProps {
  roles: Role[];
  fallback?: ReactNode;
  children: ReactNode;
}

export function Can({ roles, fallback = null, children }: CanProps) {
  const allowed = useHasRole(roles);
  return <>{allowed ? children : fallback}</>;
}
