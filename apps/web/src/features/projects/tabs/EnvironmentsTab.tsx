import { useState } from "react";

import { clsx } from "clsx";

import {
  useDeleteEnvironment,
  useEnvironments,
} from "../../../api/environments";
import { Button } from "../../../components/Button";
import { ConfirmDialog } from "../../../components/ConfirmDialog";
import { DataTable, type Column } from "../../../components/DataTable";
import { EmptyState } from "../../../components/EmptyState";
import { ErrorState } from "../../../components/ErrorState";
import { Modal } from "../../../components/Modal";
import type { EnvironmentResponse } from "../../../api/types";
import { Can } from "../../auth/Can";
import { EnvironmentForm } from "../EnvironmentForm";

interface EnvironmentsTabProps {
  projectId: string;
}

export function EnvironmentsTab({ projectId }: EnvironmentsTabProps) {
  const environments = useEnvironments(projectId);
  const deleteEnv = useDeleteEnvironment(projectId);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<EnvironmentResponse | null>(null);
  const [deleting, setDeleting] = useState<EnvironmentResponse | null>(null);

  const columns: Column<EnvironmentResponse>[] = [
    { key: "name", header: "Name", render: (e) => e.name },
    {
      key: "baseUrl",
      header: "Base URL",
      render: (e) => (
        <span className="font-mono text-xs text-muted">{e.baseUrl}</span>
      ),
    },
    { key: "type", header: "Type", render: (e) => e.type },
    {
      key: "status",
      header: "Status",
      render: (e) => (
        <span className="inline-flex items-center gap-1.5 text-xs">
          <span
            className={clsx(
              "h-1.5 w-1.5 rounded-full",
              e.status === "ACTIVE"
                ? "bg-status-passed"
                : "bg-status-pending",
            )}
          />
          {e.status}
        </span>
      ),
    },
    {
      key: "actions",
      header: "Actions",
      render: (e) => (
        <Can roles={["OWNER", "ADMIN", "MEMBER"]}>
          <div className="flex gap-2">
            <Button variant="ghost" size="sm" onClick={() => setEditing(e)}>
              Edit
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setDeleting(e)}>
              Delete
            </Button>
          </div>
        </Can>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <div className="flex justify-end">
        <Can roles={["OWNER", "ADMIN", "MEMBER"]}>
          <Button
            data-testid="add-environment"
            onClick={() => setCreating(true)}
          >
            + Add Environment
          </Button>
        </Can>
      </div>

      {environments.isError ? (
        <ErrorState
          error={environments.error}
          onRetry={() => environments.refetch()}
        />
      ) : (
        <DataTable
          columns={columns}
          rows={environments.data?.items ?? []}
          getRowId={(e) => e.id}
          rowTestId="env-row"
          loading={environments.isLoading}
          emptyState={
            <EmptyState
              title="No environments yet"
              description="Register an environment so runs know where to execute."
            />
          }
        />
      )}

      <Modal
        open={creating}
        onClose={() => setCreating(false)}
        title="Add environment"
      >
        <EnvironmentForm
          projectId={projectId}
          onCancel={() => setCreating(false)}
          onDone={() => setCreating(false)}
        />
      </Modal>

      <Modal
        open={editing !== null}
        onClose={() => setEditing(null)}
        title="Edit environment"
      >
        {editing && (
          <EnvironmentForm
            projectId={projectId}
            environment={editing}
            onCancel={() => setEditing(null)}
            onDone={() => setEditing(null)}
          />
        )}
      </Modal>

      <ConfirmDialog
        open={deleting !== null}
        title="Delete environment"
        body={`Delete "${deleting?.name}"? This cannot be undone.`}
        isLoading={deleteEnv.isPending}
        confirmTestId="confirm-delete"
        onCancel={() => setDeleting(null)}
        onConfirm={() => {
          if (!deleting) return;
          deleteEnv.mutate(deleting.id, { onSettled: () => setDeleting(null) });
        }}
      />
    </div>
  );
}
