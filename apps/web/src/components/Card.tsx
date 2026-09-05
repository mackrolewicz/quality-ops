import type { ReactNode } from "react";

import { clsx } from "clsx";

interface CardProps {
  title?: ReactNode;
  actions?: ReactNode;
  className?: string;
  children: ReactNode;
}

export function Card({ title, actions, className, children }: CardProps) {
  return (
    <div
      className={clsx(
        "rounded-lg border border-line bg-surface p-5",
        className,
      )}
    >
      {(title || actions) && (
        <div className="mb-4 flex items-center justify-between">
          {title && (
            <h3 className="text-base font-semibold text-primary">{title}</h3>
          )}
          {actions}
        </div>
      )}
      {children}
    </div>
  );
}
