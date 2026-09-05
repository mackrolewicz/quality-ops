import { useNavigate } from "react-router-dom";

import { formatRelativeTime } from "../../lib/time";
import { shortId } from "../../lib/shortId";
import { Card } from "../../components/Card";
import { DataTable, type Column } from "../../components/DataTable";
import { EmptyState } from "../../components/EmptyState";
import { StatusBadge } from "../../components/StatusBadge";
import type { RunResponse } from "../../api/types";

interface RecentRunsCardProps {
  runs: RunResponse[];
  projectName: Map<string, string>;
}

export function RecentRunsCard({ runs, projectName }: RecentRunsCardProps) {
  const navigate = useNavigate();

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
    <Card title="Recent Runs">
      <DataTable
        columns={columns}
        rows={runs}
        getRowId={(r) => r.id}
        rowTestId="run-row"
        onRowClick={(r) => navigate(`/runs/${r.id}`)}
        emptyState={<EmptyState title="No runs yet" />}
      />
    </Card>
  );
}
