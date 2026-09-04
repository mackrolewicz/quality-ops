import { useState } from "react";

import {
  useDeleteRepositoryConnection,
  useRepositoryConnections,
  useTestRepositoryConnection,
} from "../../../api/repositories";
import { Button } from "../../../components/Button";
import { ConfirmDialog } from "../../../components/ConfirmDialog";
import { DataTable, type Column } from "../../../components/DataTable";
import { EmptyState } from "../../../components/EmptyState";
import { ErrorState } from "../../../components/ErrorState";
import { Modal } from "../../../components/Modal";
import type {
  RepositoryConnectionResponse,
  TestConnectionResponse,
} from "../../../api/types";
import { Can } from "../../auth/Can";
import { RepositoryConnectionForm } from "../RepositoryConnectionForm";

interface RepositoryConnectionsTabProps {
  projectId: string;
}

export function RepositoryConnectionsTab({
  projectId,
}: RepositoryConnectionsTabProps) {
  const connections = useRepositoryConnections(projectId);
  const deleteConnection = useDeleteRepositoryConnection(projectId);
  const testConnection = useTestRepositoryConnection();
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<RepositoryConnectionResponse | null>(
    null,
  );
  const [deleting, setDeleting] = useState<RepositoryConnectionResponse | null>(
    null,
  );
  const [testResults, setTestResults] = useState<
    Record<string, TestConnectionResponse>
  >({});

  const columns: Column<RepositoryConnectionResponse>[] = [
    { key: "provider", header: "Provider", render: (c) => c.provider },
    {
      key: "repo",
      header: "Repository",
      render: (c) => (
        <span className="font-mono text-xs text-muted">
          {c.host}/{c.ownerPath}/{c.repoName}
        </span>
      ),
    },
    { key: "defaultRef", header: "Default ref", render: (c) => c.defaultRef },
    {
      key: "test",
      header: "Last test",
      render: (c) => {
        const result = testResults[c.id];
        if (!result) return <span className="text-subtle">—</span>;
        return (
          <span
            data-testid="repo-test-result"
            className={result.ok ? "text-status-passed" : "text-status-failed"}
          >
            {result.ok ? `OK (${result.defaultBranch ?? "?"})` : result.error}
          </span>
        );
      },
    },
    {
      key: "actions",
      header: "Actions",
      render: (c) => (
        <Can roles={["OWNER", "ADMIN", "MEMBER"]}>
          <div className="flex gap-2">
            <Button
              variant="ghost"
              size="sm"
              data-testid="repo-test-connection"
              isLoading={testConnection.isPending}
              onClick={() =>
                testConnection.mutate(c.id, {
                  onSuccess: (result) =>
                    setTestResults((prev) => ({ ...prev, [c.id]: result })),
                })
              }
            >
              Test
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setEditing(c)}>
              Edit
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setDeleting(c)}>
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
        <Can roles={["OWNER", "ADMIN"]}>
          <Button
            data-testid="add-repository-connection"
            onClick={() => setCreating(true)}
          >
            + Connect repository
          </Button>
        </Can>
      </div>

      {connections.isError ? (
        <ErrorState
          error={connections.error}
          onRetry={() => connections.refetch()}
        />
      ) : (
        <DataTable
          columns={columns}
          rows={connections.data?.items ?? []}
          getRowId={(c) => c.id}
          rowTestId="repo-connection-row"
          loading={connections.isLoading}
          emptyState={
            <EmptyState
              title="No repository connections yet"
              description="Connect a GitHub or GitLab repository to run its own Playwright/JUnit/pytest/Cypress/k6 project."
            />
          }
        />
      )}

      <Modal
        open={creating}
        onClose={() => setCreating(false)}
        title="Connect repository"
      >
        <RepositoryConnectionForm
          projectId={projectId}
          onCancel={() => setCreating(false)}
          onDone={() => setCreating(false)}
        />
      </Modal>

      <Modal
        open={editing !== null}
        onClose={() => setEditing(null)}
        title="Edit repository connection"
      >
        {editing && (
          <RepositoryConnectionForm
            projectId={projectId}
            connection={editing}
            onCancel={() => setEditing(null)}
            onDone={() => setEditing(null)}
          />
        )}
      </Modal>

      <ConfirmDialog
        open={deleting !== null}
        title="Delete repository connection"
        body={`Delete "${deleting?.ownerPath}/${deleting?.repoName}"? This cannot be undone.`}
        isLoading={deleteConnection.isPending}
        confirmTestId="confirm-delete"
        onCancel={() => setDeleting(null)}
        onConfirm={() => {
          if (!deleting) return;
          deleteConnection.mutate(deleting.id, {
            onSettled: () => setDeleting(null),
          });
        }}
      />
    </div>
  );
}
