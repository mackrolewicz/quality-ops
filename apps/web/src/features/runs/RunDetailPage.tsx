import { useEffect, useMemo, useRef } from "react";

import { useNavigate, useParams } from "react-router-dom";

import { useEnvironment } from "../../api/environments";
import { useRun, useTriggerRun } from "../../api/runs";
import { useRunRepositoryItems, useRunResults } from "../../api/results";
import { useSuite } from "../../api/suites";
import { formatDateTime, formatDuration } from "../../lib/time";
import { shortId } from "../../lib/shortId";
import { Button } from "../../components/Button";
import { EmptyState } from "../../components/EmptyState";
import { ErrorState } from "../../components/ErrorState";
import { PageSkeleton } from "../../components/PageSkeleton";
import { Spinner } from "../../components/Spinner";
import { StatusBadge } from "../../components/StatusBadge";
import type { ResultStatus } from "../../api/types";
import { Can } from "../auth/Can";
import { RepositoryExecutionPanel } from "./RepositoryExecutionPanel";
import { RepositoryTestItemsTable } from "./RepositoryTestItemsTable";
import { RunConfigPanel } from "./RunConfigPanel";
import { RunResultsTable } from "./RunResultsTable";
import { isActiveStatus } from "./runStatus";

const SUMMARY: { key: ResultStatus; label: string; testId: string; className: string }[] = [
  {
    key: "PASSED",
    label: "Passed",
    testId: "result-summary-passed",
    className: "text-status-passed",
  },
  {
    key: "FAILED",
    label: "Failed",
    testId: "result-summary-failed",
    className: "text-status-failed",
  },
  {
    key: "SKIPPED",
    label: "Skipped",
    testId: "result-summary-skipped",
    className: "text-status-skipped",
  },
  {
    key: "FLAKY",
    label: "Flaky",
    testId: "result-summary-flaky",
    className: "text-status-flaky",
  },
];

export function RunDetailPage() {
  const { runId = "" } = useParams();
  const navigate = useNavigate();
  const run = useRun(runId);
  const results = useRunResults(runId);
  const repositoryItems = useRunRepositoryItems(runId);
  const suite = useSuite(run.data?.suiteId);
  const environment = useEnvironment(run.data?.environmentId);
  const trigger = useTriggerRun();

  const counts = useMemo(() => {
    const base: Record<ResultStatus, number> = {
      PASSED: 0,
      FAILED: 0,
      SKIPPED: 0,
      FLAKY: 0,
    };
    for (const r of results.data ?? []) base[r.status] += 1;
    return base;
  }, [results.data]);

  const wasActive = useRef(false);
  const status = run.data?.status;
  const refetchResults = results.refetch;
  useEffect(() => {
    if (!status) return;
    if (isActiveStatus(status)) {
      wasActive.current = true;
    } else if (wasActive.current) {
      wasActive.current = false;
      void refetchResults();
    }
  }, [status, refetchResults]);

  if (run.isLoading) return <PageSkeleton />;
  if (run.isError || !run.data) {
    return <ErrorState error={run.error} onRetry={() => run.refetch()} />;
  }

  // Capture once so TS keeps the non-undefined narrowing inside JSX callbacks.
  const runData = run.data;
  const active = isActiveStatus(runData.status);
  const duration =
    runData.startedAt && runData.completedAt
      ? new Date(runData.completedAt).getTime() -
        new Date(runData.startedAt).getTime()
      : null;

  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
      <div className="space-y-6 lg:col-span-2">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-semibold text-primary">
              Run {shortId(runData.id)}
            </h1>
            <StatusBadge status={runData.status} dot data-testid="run-status" />
          </div>
          <Can roles={["OWNER", "ADMIN", "MEMBER"]}>
            <div className="flex gap-2">
              <Button
                variant="secondary"
                data-testid="run-rerun"
                isLoading={trigger.isPending}
                onClick={() =>
                  trigger.mutate(
                    {
                      projectId: runData.projectId,
                      suiteId: runData.suiteId,
                      environmentId: runData.environmentId,
                    },
                    { onSuccess: (next) => navigate(`/runs/${next.id}`) },
                  )
                }
              >
                Re-run
              </Button>
              <Button variant="ghost" disabled title="Coming soon">
                Download report
              </Button>
            </div>
          </Can>
        </div>

        <div className="flex flex-wrap gap-x-6 gap-y-1 text-sm text-muted">
          <span>Triggered by {shortId(runData.triggeredBy)}</span>
          <span>Environment {environment.data?.name ?? shortId(runData.environmentId)}</span>
          <span>Started {formatDateTime(runData.startedAt)}</span>
          <span>Duration {formatDuration(duration)}</span>
        </div>

        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          {SUMMARY.map((item) => (
            <div
              key={item.key}
              className="rounded-lg border border-line bg-surface p-4"
            >
              <p className="text-xs font-medium uppercase tracking-wide text-subtle">
                {item.label}
              </p>
              <p
                data-testid={item.testId}
                className={`mt-1 text-3xl font-semibold tabular-nums ${item.className}`}
              >
                {counts[item.key]}
              </p>
            </div>
          ))}
        </div>

        {active ? (
          <EmptyState
            title="Run in progress…"
            description="Results will appear as the run completes."
            icon={<Spinner />}
          />
        ) : results.isError ? (
          <ErrorState
            error={results.error}
            onRetry={() => results.refetch()}
          />
        ) : (results.data?.length ?? 0) === 0 ? (
          <EmptyState
            title="No results (suite had no test cases)"
            description="Add test cases to the suite and trigger a new run."
          />
        ) : (
          <RunResultsTable results={results.data ?? []} />
        )}

        {(repositoryItems.data?.length ?? 0) > 0 && (
          <div className="space-y-2">
            <h2 className="text-sm font-medium text-primary">Test items</h2>
            <RepositoryTestItemsTable items={repositoryItems.data ?? []} />
          </div>
        )}
      </div>

      <div className="space-y-6 lg:col-span-1">
        <RunConfigPanel
          run={runData}
          suiteName={suite.data?.name}
          environmentName={environment.data?.name}
          environmentUrl={environment.data?.baseUrl}
        />
        {runData.repositoryRun && (
          <RepositoryExecutionPanel repositoryRun={runData.repositoryRun} />
        )}
      </div>
    </div>
  );
}
