import { useState } from "react";

import { useNavigate, useParams } from "react-router-dom";

import {
  useCases,
  useDeleteCase,
} from "../../api/cases";
import { useProject } from "../../api/projects";
import { useDeleteSuite, useSuite } from "../../api/suites";
import { Breadcrumb } from "../../components/Breadcrumb";
import { Button } from "../../components/Button";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { DataTable, type Column } from "../../components/DataTable";
import { EmptyState } from "../../components/EmptyState";
import { ErrorState } from "../../components/ErrorState";
import { Modal } from "../../components/Modal";
import { PageSkeleton } from "../../components/PageSkeleton";
import type { TestCaseResponse } from "../../api/types";
import { Can } from "../auth/Can";
import { SuiteForm } from "../projects/SuiteForm";
import { TestCaseForm } from "./TestCaseForm";

export function SuiteDetailPage() {
  const { suiteId = "" } = useParams();
  const navigate = useNavigate();
  const suite = useSuite(suiteId);
  const project = useProject(suite.data?.projectId);
  const cases = useCases(suiteId);
  const deleteCase = useDeleteCase(suiteId);
  const deleteSuite = useDeleteSuite(suite.data?.projectId ?? "");

  const [creatingCase, setCreatingCase] = useState(false);
  const [editingCase, setEditingCase] = useState<TestCaseResponse | null>(null);
  const [deletingCase, setDeletingCase] = useState<TestCaseResponse | null>(
    null,
  );
  const [editingSuite, setEditingSuite] = useState(false);
  const [deletingSuite, setDeletingSuite] = useState(false);

  if (suite.isLoading) return <PageSkeleton />;
  if (suite.isError || !suite.data) {
    return <ErrorState error={suite.error} onRetry={() => suite.refetch()} />;
  }

  const items = [...(cases.data?.items ?? [])].sort(
    (a, b) => a.orderIndex - b.orderIndex,
  );
  const nextOrderIndex =
    items.length > 0 ? items[items.length - 1].orderIndex + 1 : 0;

  const columns: Column<TestCaseResponse>[] = [
    { key: "order", header: "Order", render: (c) => c.orderIndex },
    { key: "name", header: "Name", render: (c) => c.name },
    {
      key: "description",
      header: "Description",
      render: (c) => (
        <span className="line-clamp-1 text-muted">{c.description || "—"}</span>
      ),
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
              onClick={() => setEditingCase(c)}
            >
              Edit
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setDeletingCase(c)}
            >
              Delete
            </Button>
          </div>
        </Can>
      ),
    },
  ];

  return (
    <div className="space-y-6">
      <Breadcrumb
        items={[
          { label: "Projects", to: "/projects" },
          {
            label: project.data?.name ?? "Project",
            to: `/projects/${suite.data.projectId}`,
          },
          { label: suite.data.name },
        ]}
      />

      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-center gap-3">
          <h1 className="text-2xl font-semibold text-primary">
            {suite.data.name}
          </h1>
          <span className="rounded-md bg-surface-raised px-2 py-0.5 text-xs text-muted">
            {suite.data.type}
          </span>
        </div>
        <Can roles={["OWNER", "ADMIN", "MEMBER"]}>
          <div className="flex gap-2">
            <Button
              variant="secondary"
              data-testid="suite-edit"
              onClick={() => setEditingSuite(true)}
            >
              Edit
            </Button>
            <Button
              variant="danger"
              data-testid="suite-delete"
              onClick={() => setDeletingSuite(true)}
            >
              Delete
            </Button>
          </div>
        </Can>
      </div>

      <div className="flex justify-end">
        <Can roles={["OWNER", "ADMIN", "MEMBER"]}>
          <Button data-testid="add-case" onClick={() => setCreatingCase(true)}>
            + Add Case
          </Button>
        </Can>
      </div>

      {cases.isError ? (
        <ErrorState error={cases.error} onRetry={() => cases.refetch()} />
      ) : (
        <DataTable
          columns={columns}
          rows={items}
          getRowId={(c) => c.id}
          rowTestId="case-row"
          loading={cases.isLoading}
          emptyState={
            <EmptyState
              title="No test cases yet"
              description="Add one so runs produce results."
            />
          }
        />
      )}

      <Modal
        open={creatingCase}
        onClose={() => setCreatingCase(false)}
        title="Add test case"
      >
        <TestCaseForm
          projectId={suite.data.projectId}
          suiteId={suiteId}
          nextOrderIndex={nextOrderIndex}
          onCancel={() => setCreatingCase(false)}
          onDone={() => setCreatingCase(false)}
        />
      </Modal>

      <Modal
        open={editingCase !== null}
        onClose={() => setEditingCase(null)}
        title="Edit test case"
      >
        {editingCase && (
          <TestCaseForm
            projectId={suite.data.projectId}
            suiteId={suiteId}
            testCase={editingCase}
            nextOrderIndex={nextOrderIndex}
            onCancel={() => setEditingCase(null)}
            onDone={() => setEditingCase(null)}
          />
        )}
      </Modal>

      <Modal
        open={editingSuite}
        onClose={() => setEditingSuite(false)}
        title="Edit test suite"
      >
        <SuiteForm
          projectId={suite.data.projectId}
          suite={suite.data}
          onCancel={() => setEditingSuite(false)}
          onDone={() => setEditingSuite(false)}
        />
      </Modal>

      <ConfirmDialog
        open={deletingCase !== null}
        title="Delete test case"
        body={`Delete "${deletingCase?.name}"? This cannot be undone.`}
        isLoading={deleteCase.isPending}
        confirmTestId="confirm-delete"
        onCancel={() => setDeletingCase(null)}
        onConfirm={() => {
          if (!deletingCase) return;
          deleteCase.mutate(deletingCase.id, {
            onSettled: () => setDeletingCase(null),
          });
        }}
      />

      <ConfirmDialog
        open={deletingSuite}
        title="Delete test suite"
        body={`Delete "${suite.data.name}"? This cannot be undone.`}
        isLoading={deleteSuite.isPending}
        confirmTestId="confirm-delete"
        onCancel={() => setDeletingSuite(false)}
        onConfirm={() =>
          deleteSuite.mutate(suite.data.id, {
            onSuccess: () => navigate(`/projects/${suite.data.projectId}`),
            onSettled: () => setDeletingSuite(false),
          })
        }
      />
    </div>
  );
}
