import { StatCard } from "../../components/StatCard";

interface StatCardsProps {
  totalRuns7d: number;
  passRatePercent: number;
  failedRuns7d: number;
  activeEnvironments: number;
}

export function StatCards({
  totalRuns7d,
  passRatePercent,
  failedRuns7d,
  activeEnvironments,
}: StatCardsProps) {
  const passAccent =
    passRatePercent >= 80
      ? "passed"
      : passRatePercent >= 50
        ? "running"
        : "failed";

  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
      <StatCard label="Total Runs (7d)" value={totalRuns7d} />
      <StatCard
        label="Pass Rate %"
        value={`${passRatePercent}%`}
        accent={passAccent}
      />
      <StatCard label="Failed Runs" value={failedRuns7d} accent="failed" />
      <StatCard label="Active Environments" value={activeEnvironments} />
    </div>
  );
}
