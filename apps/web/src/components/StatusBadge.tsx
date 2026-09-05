import { clsx } from "clsx";

import type { ResultStatus, RunStatus } from "../api/types";

type AnyStatus = RunStatus | ResultStatus;

const CLASS_MAP: Record<AnyStatus, string> = {
  PENDING: "bg-status-pending/15 text-status-pending",
  RUNNING: "bg-status-running/15 text-status-running",
  PASSED: "bg-status-passed/15 text-status-passed",
  FAILED: "bg-status-failed/15 text-status-failed",
  CANCELLED: "bg-status-cancelled/15 text-status-cancelled",
  SKIPPED: "bg-status-skipped/15 text-status-skipped",
  FLAKY: "bg-status-flaky/15 text-status-flaky",
};

interface StatusBadgeProps {
  status: AnyStatus;
  dot?: boolean;
  className?: string;
  "data-testid"?: string;
}

export function StatusBadge({
  status,
  dot = false,
  className,
  "data-testid": testId,
}: StatusBadgeProps) {
  return (
    <span
      data-testid={testId}
      className={clsx(
        "inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium",
        CLASS_MAP[status],
        className,
      )}
    >
      {dot && <span className="h-1.5 w-1.5 rounded-full bg-current" />}
      {status}
    </span>
  );
}
