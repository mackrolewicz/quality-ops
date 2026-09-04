import { useMemo, useState } from "react";

import { useNavigate } from "react-router-dom";

import { useEnvironments } from "../../../api/environments";
import { useRuns } from "../../../api/runs";
import { useSuites } from "../../../api/suites";
import { formatRelativeTime } from "../../../lib/time";
import { shortId } from "../../../lib/shortId";
import { Button } from "../../../components/Button";
import { DataTable, type Column } from "../../../components/DataTable";
import { EmptyState } from "../../../components/EmptyState";
import { ErrorState } from "../../../components/ErrorState";
import { StatusBadge } from "../../../components/StatusBadge";
import type { RunResponse } from "../../../api/types";
import { Can } from "../../auth/Can";
import { TriggerRunModal } from "../../runs/TriggerRunModal";

interface RecentRunsTabProps {
  projectId: string;
}

export function RecentRunsTab({ projectId }: RecentRunsTabProps) {
  const navigate = useNavigate();
  const runs = useRuns({ projectId, size: 20 });
  const suites = useSuites(projectId);
  const environments = useEnvironments(projectId);
  const [triggering, setTriggering] = useState(false);

  const suiteName = useMemo(() => {
    const map = new Map<string, string>();
    for (const s of suites.data?.items ?? []) map.set(s.id, s.name);
    return map;
  }, [suites.data]);

  const envName = useMemo(() => {
    const map = new Map<string, string>();
    for (const e of environments.data?.items ?? []) map.set(e.id, e.name);
    return map;
  }, [environments.data]);

  const columns: Column<RunResponse>[] = [
    {
      key: "id",
      header: "Run",
      render: (r) => (
        <span className="font-mono text-xs text-muted">{shortId(r.id)}</span>
      ),
    },
    {
      key: "suite",
      header: "Suite",
      render: (r) => suiteName.get(r.suiteId) ?? shortId(r.suiteId),
    },
    {
      key: "env",
      header: "Environment",
      render: (r) => envName.get(r.environmentId) ?? shortId(r.environmentId),
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
  ];

  return (
    <div className="space-y-4">
      <div className="flex justify-end">
        <Can roles={["OWNER", "ADMIN", "MEMBER"]}>
          <Button
            data-testid="trigger-run"
            onClick={() => setTriggering(true)}
          >
            Trigger new run
          </Button>
        </Can>
      </div>

      {runs.isError ? (
        <ErrorState error={runs.error} onRetry={() => runs.refetch()} />
      ) : (
        <DataTable
          columns={columns}
          rows={runs.data?.items ?? []}
          getRowId={(r) => r.id}
          rowTestId="run-row"
          loading={runs.isLoading}
          onRowClick={(r) => navigate(`/runs/${r.id}`)}
          emptyState={
            <EmptyState
              title="No runs yet"
              description="Trigger a run to see results here."
            />
          }
        />
      )}

      <TriggerRunModal
        projectId={projectId}
        open={triggering}
        onClose={() => setTriggering(false)}
      />
    </div>
  );
}
