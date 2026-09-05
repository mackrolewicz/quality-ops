import { useMemo } from "react";

import { useNavigate, useSearchParams } from "react-router-dom";

import { useProjects } from "../../api/projects";
import { useRuns } from "../../api/runs";
import { formatDuration, formatRelativeTime } from "../../lib/time";
import { shortId } from "../../lib/shortId";
import { DataTable, type Column } from "../../components/DataTable";
import { EmptyState } from "../../components/EmptyState";
import { ErrorState } from "../../components/ErrorState";
import { Pagination } from "../../components/Pagination";
import { Select } from "../../components/Select";
import { StatusBadge } from "../../components/StatusBadge";
import type { RunResponse, RunStatus } from "../../api/types";

const STATUSES: RunStatus[] = [
  "PENDING",
  "RUNNING",
  "PASSED",
  "FAILED",
  "CANCELLED",
];

function runDuration(run: RunResponse): number | null {
  if (!run.startedAt || !run.completedAt) return null;
  return new Date(run.completedAt).getTime() - new Date(run.startedAt).getTime();
}

export function RunsPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();

  const projectId = params.get("projectId") ?? "";
  const status = (params.get("status") ?? "") as RunStatus | "";
  const page = Number.parseInt(params.get("page") ?? "1", 10) || 1;

  const projects = useProjects(1, 50);
  const runs = useRuns({
    projectId: projectId || undefined,
    status: status || undefined,
    page,
    size: 20,
  });

  const projectName = useMemo(() => {
    const map = new Map<string, string>();
    for (const p of projects.data?.items ?? []) map.set(p.id, p.name);
    return map;
  }, [projects.data]);

  const updateParam = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== "page") next.delete("page");
    setParams(next);
  };

  const columns: Column<RunResponse>[] = [
    {
      key: "id",
      header: "Run",
      render: (r) => (
        <span className="font-mono text-xs text-muted">{shortId(r.id)}</span>
      ),
    },
    {
      key: "project",
      header: "Project",
      render: (r) => projectName.get(r.projectId) ?? shortId(r.projectId),
    },
    {
      key: "suite",
      header: "Suite",
      render: (r) => (
        <span className="font-mono text-xs text-muted">
          {shortId(r.suiteId)}
        </span>
      ),
    },
    {
      key: "env",
      header: "Environment",
      render: (r) => (
        <span className="font-mono text-xs text-muted">
          {shortId(r.environmentId)}
        </span>
      ),
    },
    {
      key: "status",
      header: "Status",
      render: (r) => <StatusBadge status={r.status} />,
    },
    {
      key: "triggeredBy",
      header: "Triggered by",
      render: (r) => (
        <span className="font-mono text-xs text-muted">
          {shortId(r.triggeredBy)}
        </span>
      ),
    },
    {
      key: "started",
      header: "Started",
      render: (r) => formatRelativeTime(r.startedAt ?? r.createdAt),
    },
    {
      key: "duration",
      header: "Duration",
      render: (r) => formatDuration(runDuration(r)),
    },
  ];

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-primary">Runs</h1>

      <div className="flex flex-wrap gap-3">
        <Select
          className="w-56"
          data-testid="runs-project-filter"
          value={projectId}
          onChange={(e) => updateParam("projectId", e.target.value)}
        >
          <option value="">All projects</option>
          {(projects.data?.items ?? []).map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </Select>
        <Select
          className="w-48"
          data-testid="runs-status-filter"
          value={status}
          onChange={(e) => updateParam("status", e.target.value)}
        >
          <option value="">All statuses</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </Select>
      </div>

      {runs.isError ? (
        <ErrorState error={runs.error} onRetry={() => runs.refetch()} />
      ) : (
        <>
          <DataTable
            columns={columns}
            rows={runs.data?.items ?? []}
            getRowId={(r) => r.id}
            rowTestId="run-row"
            loading={runs.isLoading}
            onRowClick={(r) => navigate(`/runs/${r.id}`)}
            emptyState={
              <EmptyState
                title="No runs found"
                description="Trigger a run from a project to see it here."
              />
            }
          />
          {runs.data && runs.data.meta.total > runs.data.meta.pageSize && (
            <Pagination
              page={runs.data.meta.page}
              pageSize={runs.data.meta.pageSize}
              total={runs.data.meta.total}
              onPageChange={(p) => updateParam("page", String(p))}
            />
          )}
        </>
      )}
    </div>
  );
}
