import { clsx } from "clsx";

import type { Role } from "../types/auth";

const CLASS_MAP: Record<Role, string> = {
  OWNER: "bg-role-owner/15 text-role-owner",
  ADMIN: "bg-role-admin/15 text-role-admin",
  MEMBER: "bg-role-member/15 text-role-member",
  VIEWER: "bg-role-viewer/15 text-role-viewer",
};

interface RoleBadgeProps {
  role: Role;
  className?: string;
}

export function RoleBadge({ role, className }: RoleBadgeProps) {
  return (
    <span
      className={clsx(
        "inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium",
        CLASS_MAP[role],
        className,
      )}
    >
      {role}
    </span>
  );
}
