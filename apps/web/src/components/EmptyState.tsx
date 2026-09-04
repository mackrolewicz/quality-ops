import type { ReactNode } from "react";

import { InboxIcon } from "./icons";

interface EmptyStateProps {
  title: string;
  description?: string;
  icon?: ReactNode;
  action?: ReactNode;
}

export function EmptyState({
  title,
  description,
  icon,
  action,
}: EmptyStateProps) {
  return (
    <div className="py-16 text-center">
      <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-surface-raised text-muted">
        {icon ?? <InboxIcon />}
      </div>
      <p className="mt-4 text-base font-medium text-primary">{title}</p>
      {description && (
        <p className="mt-1 text-sm text-muted">{description}</p>
      )}
      {action && <div className="mt-4 flex justify-center">{action}</div>}
    </div>
  );
}
