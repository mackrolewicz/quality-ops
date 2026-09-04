import { useState } from "react";

import { useNavigate, useParams } from "react-router-dom";

import { useProjectAnalytics } from "../../api/analytics";
import { useDeleteProject, useProject } from "../../api/projects";
import { useRuns } from "../../api/runs";
import { useSuites } from "../../api/suites";
import { Breadcrumb } from "../../components/Breadcrumb";
import { Button } from "../../components/Button";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { ErrorState } from "../../components/ErrorState";
import { Modal } from "../../components/Modal";
import { PageSkeleton } from "../../components/PageSkeleton";
import { StatCard } from "../../components/StatCard";
import { StatusBadge } from "../../components/StatusBadge";
import { Tabs, type TabItem } from "../../components/Tabs";
import { Can } from "../auth/Can";
import { EditProjectForm } from "./EditProjectForm";
import { EnvironmentsTab } from "./tabs/EnvironmentsTab";
import { RecentRunsTab } from "./tabs/RecentRunsTab";
import { RepositoryConnectionsTab } from "./tabs/RepositoryConnectionsTab";
import { SuitesTab } from "./tabs/SuitesTab";

const TABS: TabItem[] = [
  { id: "suites", label: "Test Suites", testId: "tab-suites" },
  { id: "environments", label: "Environments", testId: "tab-environments" },
  {
    id: "repositories",
    label: "Repositories",
    testId: "tab-repositories",
  },
  { id: "runs", label: "Recent Runs", testId: "tab-runs" },
];

export function ProjectDetailPage() {
  const { projectId = "" } = useParams();
  const navigate = useNavigate();
  const project = useProject(projectId);
  const suites = useSuites(projectId);
  const runs = useRuns({ projectId, size: 20 });
  const analytics = useProjectAnalytics(projectId);
  const deleteProject = useDeleteProject();

  const [tab, setTab] = useState("suites");
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);

  if (project.isLoading) return <PageSkeleton />;
  if (project.isError || !project.data) {
    return (
      <ErrorState error={project.error} onRetry={() => project.refetch()} />
    );
  }

  const lastRun = runs.data?.items[0];

  return (
    <div className="space-y-6">
      <Breadcrumb
        items={[
          { label: "Projects", to: "/projects" },
          { label: project.data.name },
        ]}
      />

      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-primary">
            {project.data.name}
          </h1>
          <p className="mt-1 text-sm text-muted">
            {project.data.description || "No description"}
          </p>
        </div>
        <Can roles={["OWNER", "ADMIN"]}>
          <div className="flex gap-2">
            <Button
              variant="secondary"
              data-testid="project-edit"
              onClick={() => setEditing(true)}
            >
              Edit
            </Button>
            <Button
              variant="danger"
              data-testid="project-delete"
              onClick={() => setDeleting(true)}
            >
              Delete
            </Button>
          </div>
        </Can>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <StatCard
          label="Test suites"
          value={suites.data?.meta.total ?? suites.data?.items.length ?? 0}
        />
        <StatCard
          label="Total runs"
          value={analytics.data?.totalRuns ?? runs.data?.meta.total ?? 0}
        />
        <div className="rounded-lg border border-line bg-surface p-4">
          <p className="text-xs font-medium uppercase tracking-wide text-subtle">
            Last run
          </p>
          <div className="mt-2">
            {lastRun ? (
              <StatusBadge status={lastRun.status} />
            ) : (
              <span className="text-sm text-muted">No runs yet</span>
            )}
          </div>
        </div>
      </div>

      <Tabs tabs={TABS} active={tab} onChange={setTab} />

      {tab === "suites" && <SuitesTab projectId={projectId} />}
      {tab === "environments" && <EnvironmentsTab projectId={projectId} />}
      {tab === "repositories" && (
        <RepositoryConnectionsTab projectId={projectId} />
      )}
      {tab === "runs" && <RecentRunsTab projectId={projectId} />}

      <Modal
        open={editing}
        onClose={() => setEditing(false)}
        title="Edit project"
      >
        <EditProjectForm
          project={project.data}
          onCancel={() => setEditing(false)}
          onSaved={() => setEditing(false)}
        />
      </Modal>

      <ConfirmDialog
        open={deleting}
        title="Delete project"
        body={`Delete "${project.data.name}"? This permanently removes its suites, environments and runs.`}
        isLoading={deleteProject.isPending}
        confirmTestId="confirm-delete"
        onCancel={() => setDeleting(false)}
        onConfirm={() =>
          deleteProject.mutate(projectId, {
            onSuccess: () => navigate("/projects"),
            onSettled: () => setDeleting(false),
          })
        }
      />
    </div>
  );
}
