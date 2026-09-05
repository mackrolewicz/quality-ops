import { clsx } from "clsx";

type Accent = "default" | "passed" | "failed" | "running";

const ACCENT_CLASSES: Record<Accent, string> = {
  default: "text-primary",
  passed: "text-status-passed",
  failed: "text-status-failed",
  running: "text-status-running",
};

interface StatCardProps {
  label: string;
  value: string | number;
  accent?: Accent;
  delta?: { direction: "up" | "down"; text: string };
  "data-testid"?: string;
}

export function StatCard({
  label,
  value,
  accent = "default",
  delta,
  "data-testid": testId,
}: StatCardProps) {
  return (
    <div
      className="rounded-lg border border-line bg-surface p-4"
      data-testid={testId}
    >
      <p className="text-xs font-medium uppercase tracking-wide text-subtle">
        {label}
      </p>
      <p
        className={clsx(
          "mt-1 text-3xl font-semibold tabular-nums",
          ACCENT_CLASSES[accent],
        )}
      >
        {value}
      </p>
      {delta && (
        <p
          className={clsx(
            "mt-1 text-xs",
            delta.direction === "up"
              ? "text-status-passed"
              : "text-status-failed",
          )}
        >
          {delta.direction === "up" ? "▲" : "▼"} {delta.text}
        </p>
      )}
    </div>
  );
}
