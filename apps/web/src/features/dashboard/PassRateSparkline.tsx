import { useMemo } from "react";

import { Card } from "../../components/Card";
import type { RunResponse } from "../../api/types";

interface PassRateSparklineProps {
  runs: RunResponse[];
  days?: number;
}

interface DayBucket {
  label: string;
  passRate: number;
  total: number;
}

function buildBuckets(runs: RunResponse[], days: number): DayBucket[] {
  const buckets: DayBucket[] = [];
  const now = new Date();

  for (let i = days - 1; i >= 0; i -= 1) {
    const day = new Date(now);
    day.setDate(now.getDate() - i);
    const key = day.toISOString().slice(0, 10);
    const dayRuns = runs.filter((r) => r.createdAt.slice(0, 10) === key);
    const passed = dayRuns.filter((r) => r.status === "PASSED").length;
    const failed = dayRuns.filter((r) => r.status === "FAILED").length;
    const decided = passed + failed;
    buckets.push({
      label: key.slice(5),
      passRate: decided === 0 ? 0 : Math.round((passed / decided) * 100),
      total: dayRuns.length,
    });
  }

  return buckets;
}

export function PassRateSparkline({ runs, days = 14 }: PassRateSparklineProps) {
  const buckets = useMemo(() => buildBuckets(runs, days), [runs, days]);

  return (
    <Card title="Pass rate (14d)">
      <div className="flex h-40 items-end gap-1">
        {buckets.map((bucket) => (
          <div
            key={bucket.label}
            className="flex flex-1 flex-col items-center gap-1"
            title={`${bucket.label}: ${bucket.passRate}% (${bucket.total} runs)`}
          >
            {/* data-driven bar height cannot be expressed as a static Tailwind class */}
            <div
              className="w-full rounded-t bg-status-passed/70"
              style={{ height: `${Math.max(bucket.passRate, 2)}%` }}
            />
          </div>
        ))}
      </div>
      <div className="mt-2 flex justify-between text-xs text-subtle">
        <span>{buckets[0]?.label}</span>
        <span>{buckets[buckets.length - 1]?.label}</span>
      </div>
    </Card>
  );
}
