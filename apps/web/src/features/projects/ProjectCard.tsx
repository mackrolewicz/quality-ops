import { Link } from "react-router-dom";

import { useSuites } from "../../api/suites";
import { formatRelativeTime } from "../../lib/time";
import { StatusBadge } from "../../components/StatusBadge";
import type { ProjectResponse, RunResponse } from "../../api/types";

interface ProjectCardProps {
  project: ProjectResponse;
  lastRun?: RunResponse;
}

export function ProjectCard({ project, lastRun }: ProjectCardProps) {
  const suites = useSuites(project.id);
  const suiteCount = suites.data?.meta.total ?? suites.data?.items.length ?? 0;

  return (
    <Link
      to={`/projects/${project.id}`}
      data-testid="project-card"
      className="flex flex-col rounded-lg border border-line bg-surface p-5 transition-colors hover:border-line-strong hover:bg-surface-raised"
    >
      <h3 className="line-clamp-1 text-base font-semibold text-primary">
        {project.name}
      </h3>
      <p className="mt-1 line-clamp-2 text-sm text-muted">
        {project.description || "No description"}
      </p>

      <div className="mt-4 flex items-center gap-4 text-xs text-subtle">
        <span>
          {suiteCount} {suiteCount === 1 ? "suite" : "suites"}
        </span>
        {lastRun ? (
          <>
            <StatusBadge status={lastRun.status} />
            <span>{formatRelativeTime(lastRun.createdAt)}</span>
          </>
        ) : (
          <span>No runs yet</span>
        )}
      </div>

      <span className="mt-4 self-end text-xs text-accent">View project →</span>
    </Link>
  );
}
