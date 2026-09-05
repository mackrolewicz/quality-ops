import { useQuery, useQueryClient } from "@tanstack/react-query";

import { ACTIVE } from "../features/runs/runStatus";
import { api } from "./client";
import { unwrap } from "./envelope";
import type {
  Envelope,
  Meta,
  RepositoryTestItemResponse,
  RunResponse,
  TestResultResponse,
} from "./types";

export function useRunResults(runId: string | undefined) {
  const qc = useQueryClient();
  return useQuery<TestResultResponse[]>({
    queryKey: ["runs", runId, "results"],
    enabled: Boolean(runId),
    queryFn: async () => {
      const res = await api.get<Envelope<TestResultResponse[]>>(
        `/api/v1/runs/${runId}/results`,
      );
      return unwrap(res);
    },
    refetchInterval: () => {
      const run = qc.getQueryData<RunResponse>(["runs", runId]);
      return run && ACTIVE.has(run.status) ? 2000 : false;
    },
    refetchIntervalInBackground: false,
  });
}

/** ADR-009 §11 — the same `.../results` endpoint additionally carries a
 *  `meta.repositoryItems` array for a repository run (empty/absent otherwise).
 *  A separate query rather than widening `useRunResults`'s return shape. */
export function useRunRepositoryItems(runId: string | undefined) {
  return useQuery<RepositoryTestItemResponse[]>({
    queryKey: ["runs", runId, "results", "repositoryItems"],
    enabled: Boolean(runId),
    queryFn: async () => {
      const res = await api.get<{
        data: TestResultResponse[];
        meta: (Meta & { repositoryItems?: RepositoryTestItemResponse[] }) | null;
      }>(`/api/v1/runs/${runId}/results`);
      return res.data.meta?.repositoryItems ?? [];
    },
  });
}
