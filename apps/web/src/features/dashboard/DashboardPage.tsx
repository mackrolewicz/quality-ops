import { Link } from "react-router-dom";

import { EmptyState } from "../../components/EmptyState";
import { ErrorState } from "../../components/ErrorState";
import { PageSkeleton } from "../../components/PageSkeleton";
import { useDashboardData } from "./useDashboardData";
import { ActiveEnvironmentsGrid } from "./ActiveEnvironmentsGrid";
import { PassRateSparkline } from "./PassRateSparkline";
import { RecentRunsCard } from "./RecentRunsCard";
import { StatCards } from "./StatCards";

export function DashboardPage() {
  const data = useDashboardData();

  if (data.isLoading) return <PageSkeleton />;
  if (data.isError) return <ErrorState error={data.error} />;

  if (!data.hasAnyRuns) {
    return (
      <div className="space-y-6">
        <StatCards
          totalRuns7d={0}
          passRatePercent={0}
          failedRuns7d={0}
          activeEnvironments={data.activeEnvironments}
        />
        <EmptyState
          title="No runs yet"
          description="Create a project and trigger your first run."
          action={
            <Link
              to="/projects"
              className="text-sm text-accent hover:underline"
            >
              Go to Projects
            </Link>
          }
        />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <StatCards
        totalRuns7d={data.totalRuns7d}
        passRatePercent={data.passRatePercent}
        failedRuns7d={data.failedRuns7d}
        activeEnvironments={data.activeEnvironments}
      />

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-5">
        <div className="lg:col-span-3">
          <RecentRunsCard
            runs={data.recentRuns}
            projectName={data.projectName}
          />
        </div>
        <div className="lg:col-span-2">
          <PassRateSparkline runs={data.runsForSparkline} />
        </div>
      </div>

      <ActiveEnvironmentsGrid environments={data.environments} />
    </div>
  );
}
