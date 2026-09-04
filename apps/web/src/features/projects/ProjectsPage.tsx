import { useMemo, useState } from "react";

import { useProjects } from "../../api/projects";
import { useRuns } from "../../api/runs";
import { useDebounce } from "../../hooks/useDebounce";
import { Button } from "../../components/Button";
import { EmptyState } from "../../components/EmptyState";
import { ErrorState } from "../../components/ErrorState";
import { Modal } from "../../components/Modal";
import { Skeleton } from "../../components/Skeleton";
import { TextInput } from "../../components/TextInput";
import type { RunResponse } from "../../api/types";
import { useHasRole } from "../auth/useRole";
import { CreateProjectForm } from "./CreateProjectForm";
import { ProjectCard } from "./ProjectCard";

export function ProjectsPage() {
  const canWrite = useHasRole(["OWNER", "ADMIN"]);
  const [search, setSearch] = useState("");
  const [creating, setCreating] = useState(false);
  const debouncedSearch = useDebounce(search, 250);

  const projects = useProjects(1, 50);
  const runs = useRuns({ size: 100 });

  const lastRunByProject = useMemo(() => {
    const map = new Map<string, RunResponse>();
    for (const run of runs.data?.items ?? []) {
      const current = map.get(run.projectId);
      if (!current || run.createdAt > current.createdAt) {
        map.set(run.projectId, run);
      }
    }
    return map;
  }, [runs.data]);

  const filtered = useMemo(() => {
    const term = debouncedSearch.trim().toLowerCase();
    const items = projects.data?.items ?? [];
    if (!term) return items;
    return items.filter(
      (p) =>
        p.name.toLowerCase().includes(term) ||
        (p.description ?? "").toLowerCase().includes(term),
    );
  }, [projects.data, debouncedSearch]);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold text-primary">Projects</h1>
        <div className="flex items-center gap-3">
          <TextInput
            className="w-64"
            placeholder="Search projects"
            data-testid="project-search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <Button
            data-testid="new-project"
            disabled={!canWrite}
            title={canWrite ? undefined : "Requires Owner or Admin role"}
            onClick={() => setCreating(true)}
          >
            + New Project
          </Button>
        </div>
      </div>

      {projects.isLoading ? (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-40 w-full" />
          ))}
        </div>
      ) : projects.isError ? (
        <ErrorState error={projects.error} onRetry={() => projects.refetch()} />
      ) : filtered.length === 0 ? (
        <EmptyState
          title={debouncedSearch ? "No matching projects" : "No projects yet"}
          description={
            debouncedSearch
              ? "Try a different search term."
              : "Create your first project to get started."
          }
          action={
            canWrite && !debouncedSearch ? (
              <Button onClick={() => setCreating(true)}>
                Create your first project
              </Button>
            ) : undefined
          }
        />
      ) : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {filtered.map((project) => (
            <ProjectCard
              key={project.id}
              project={project}
              lastRun={lastRunByProject.get(project.id)}
            />
          ))}
        </div>
      )}

      <Modal
        open={creating}
        onClose={() => setCreating(false)}
        title="New project"
      >
        <CreateProjectForm
          onCancel={() => setCreating(false)}
          onCreated={() => setCreating(false)}
        />
      </Modal>
    </div>
  );
}
