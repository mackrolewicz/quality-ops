import { useQuery } from "@tanstack/react-query";

import { api } from "./client";
import { unwrap } from "./envelope";
import type { Envelope, ProjectAnalyticsResponse } from "./types";

export function useProjectAnalytics(
  projectId: string | undefined,
  days = 7,
) {
  return useQuery<ProjectAnalyticsResponse>({
    queryKey: ["projects", projectId, "analytics", days],
    enabled: Boolean(projectId),
    queryFn: async () => {
      const res = await api.get<Envelope<ProjectAnalyticsResponse>>(
        `/api/v1/projects/${projectId}/analytics`,
        { params: { days } },
      );
      return unwrap(res);
    },
  });
}
