import { useState } from "react";

import { useNavigate } from "react-router-dom";

import { useCases } from "../../../api/cases";
import { useDeleteSuite, useSuites } from "../../../api/suites";
import { Button } from "../../../components/Button";
import { ConfirmDialog } from "../../../components/ConfirmDialog";
import { DataTable, type Column } from "../../../components/DataTable";
import { EmptyState } from "../../../components/EmptyState";
import { ErrorState } from "../../../components/ErrorState";
import { Modal } from "../../../components/Modal";
import type { TestSuiteResponse } from "../../../api/types";
import { Can } from "../../auth/Can";
import { SuiteForm } from "../SuiteForm";

interface SuitesTabProps {
  projectId: string;
}

function CasesCount({ suiteId }: { suiteId: string }) {
  const cases = useCases(suiteId);
  if (cases.isLoading) return <span className="text-subtle">…</span>;
  return <span>{cases.data?.meta.total ?? cases.data?.items.length ?? 0}</span>;
}

export function SuitesTab({ projectId }: SuitesTabProps) {
  const navigate = useNavigate();
  const suites = useSuites(projectId);
  const deleteSuite = useDeleteSuite(projectId);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<TestSuiteResponse | null>(null);
  const [deleting, setDeleting] = useState<TestSuiteResponse | null>(null);

  const columns: Column<TestSuiteResponse>[] = [
    { key: "name", header: "Suite", render: (s) => s.name },
    { key: "type", header: "Type", render: (s) => s.type },
    {
      key: "cases",
      header: "Cases",
      render: (s) => <CasesCount suiteId={s.id} />,
    },
    {
      key: "actions",
      header: "Actions",
      render: (s) => (
        <Can roles={["OWNER", "ADMIN", "MEMBER"]}>
          <div
            className="flex gap-2"
            onClick={(e) => e.stopPropagation()}
          >
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setEditing(s)}
            >
              Edit
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setDeleting(s)}
            >
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
          <Button data-testid="add-suite" onClick={() => setCreating(true)}>
            + Add Suite
          </Button>
        </Can>
      </div>

      {suites.isError ? (
        <ErrorState error={suites.error} onRetry={() => suites.refetch()} />
      ) : (
        <DataTable
          columns={columns}
          rows={suites.data?.items ?? []}
          getRowId={(s) => s.id}
          rowTestId="suite-row"
          loading={suites.isLoading}
          onRowClick={(s) => navigate(`/suites/${s.id}`)}
          emptyState={
            <EmptyState
              title="No test suites yet"
              description="Add a suite to organize your test cases."
            />
          }
        />
      )}

      <Modal
        open={creating}
        onClose={() => setCreating(false)}
        title="Add test suite"
      >
        <SuiteForm
          projectId={projectId}
          onCancel={() => setCreating(false)}
          onDone={() => setCreating(false)}
        />
      </Modal>

      <Modal
        open={editing !== null}
        onClose={() => setEditing(null)}
        title="Edit test suite"
      >
        {editing && (
          <SuiteForm
            projectId={projectId}
            suite={editing}
            onCancel={() => setEditing(null)}
            onDone={() => setEditing(null)}
          />
        )}
      </Modal>

      <ConfirmDialog
        open={deleting !== null}
        title="Delete test suite"
        body={`Delete "${deleting?.name}"? This cannot be undone.`}
        isLoading={deleteSuite.isPending}
        confirmTestId="confirm-delete"
        onCancel={() => setDeleting(null)}
        onConfirm={() => {
          if (!deleting) return;
          deleteSuite.mutate(deleting.id, {
            onSettled: () => setDeleting(null),
          });
        }}
      />
    </div>
  );
}
