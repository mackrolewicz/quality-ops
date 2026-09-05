import { clsx } from "clsx";

import { Card } from "../../components/Card";
import { EmptyState } from "../../components/EmptyState";
import type { EnvironmentResponse } from "../../api/types";

interface ActiveEnvironmentsGridProps {
  environments: EnvironmentResponse[];
}

export function ActiveEnvironmentsGrid({
  environments,
}: ActiveEnvironmentsGridProps) {
  const shown = environments.slice(0, 6);

  return (
    <Card title="Active environments">
      {shown.length === 0 ? (
        <EmptyState title="No environments" />
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {shown.map((env) => (
            <div
              key={env.id}
              className="rounded-lg border border-line bg-surface-raised p-4"
            >
              <p className="text-sm font-medium text-primary">{env.name}</p>
              <p className="mt-1 truncate font-mono text-xs text-muted">
                {env.baseUrl}
              </p>
              <p className="mt-2 flex items-center gap-1.5 text-xs text-subtle">
                <span
                  className={clsx(
                    "h-1.5 w-1.5 rounded-full",
                    env.status === "ACTIVE"
                      ? "bg-status-passed"
                      : "bg-status-pending",
                  )}
                />
                {env.type}
              </p>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}
