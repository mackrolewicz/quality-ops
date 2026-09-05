import { useMemo } from "react";

import { useQueries } from "@tanstack/react-query";

import { api } from "../../api/client";
import { unwrapList } from "../../api/envelope";
import { useProjects } from "../../api/projects";
import { useRuns } from "../../api/runs";
import type {
  Envelope,
  EnvironmentResponse,
  ProjectResponse,
  RunResponse,
} from "../../api/types";

const RECENT_DAYS = 7;
const ENV_PROJECT_LIMIT = 5;

export interface DashboardData {
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  totalRuns7d: number;
  passRatePercent: number;
  failedRuns7d: number;
  activeEnvironments: number;
  recentRuns: RunResponse[];
  runsForSparkline: RunResponse[];
  environments: EnvironmentResponse[];
  projectName: Map<string, string>;
  hasAnyRuns: boolean;
}

function withinDays(iso: string, days: number): boolean {
  return Date.now() - new Date(iso).getTime() <= days * 24 * 60 * 60 * 1000;
}

export function useDashboardData(): DashboardData {
  const runs = useRuns({ size: 100 });
  const projects = useProjects(1, 50);

  const envProjects = (projects.data?.items ?? []).slice(0, ENV_PROJECT_LIMIT);

  const envQueries = useQueries({
    queries: envProjects.map((project: ProjectResponse) => ({
      queryKey: ["projects", project.id, "environments", { page: 1, size: 20 }],
      queryFn: async () => {
        const res = await api.get<Envelope<EnvironmentResponse[]>>(
          `/api/v1/projects/${project.id}/environments`,
          { params: { page: 1, size: 20 } },
        );
        return unwrapList(res).items;
      },
    })),
  });

  return useMemo(() => {
    const allRuns = runs.data?.items ?? [];
    const recent = allRuns.filter((r) => withinDays(r.createdAt, RECENT_DAYS));
    const passed = recent.filter((r) => r.status === "PASSED").length;
    const failed = recent.filter((r) => r.status === "FAILED").length;
    const decided = passed + failed;

    const environments = envQueries.flatMap((q) => q.data ?? []);
    const projectName = new Map<string, string>();
    for (const p of projects.data?.items ?? []) projectName.set(p.id, p.name);

    const recentRuns = [...allRuns]
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .slice(0, 8);

    return {
      isLoading: runs.isLoading || projects.isLoading,
      isError: runs.isError || projects.isError,
      error: runs.error ?? projects.error,
      totalRuns7d: recent.length,
      passRatePercent: decided === 0 ? 0 : Math.round((passed / decided) * 100),
      failedRuns7d: failed,
      activeEnvironments: environments.filter((e) => e.status === "ACTIVE")
        .length,
      recentRuns,
      runsForSparkline: allRuns,
      environments,
      projectName,
      hasAnyRuns: allRuns.length > 0,
    };
  }, [
    runs.data,
    runs.isLoading,
    runs.isError,
    runs.error,
    projects.data,
    projects.isLoading,
    projects.isError,
    projects.error,
    envQueries,
  ]);
}
