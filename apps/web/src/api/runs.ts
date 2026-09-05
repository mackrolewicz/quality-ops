import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";

import {
  runRefetchInterval,
  runsListActive,
} from "../features/runs/runStatus";
import { api } from "./client";
import { unwrap, unwrapList, type ListResult } from "./envelope";
import type {
  CreateRunRequest,
  Envelope,
  RunResponse,
  RunStatus,
} from "./types";

export interface RunFilters {
  projectId?: string;
  suiteId?: string;
  status?: RunStatus;
  page?: number;
  size?: number;
}

export function useRuns(filters: RunFilters = {}) {
  const { page = 1, size = 20, ...rest } = filters;
  return useQuery<ListResult<RunResponse>>({
    queryKey: ["runs", { ...rest, page, size }],
    queryFn: async () => {
      const res = await api.get<Envelope<RunResponse[]>>("/api/v1/runs", {
        params: { ...rest, page, size },
      });
      return unwrapList(res);
    },
    placeholderData: keepPreviousData,
    refetchInterval: (query) => runsListActive(query.state.data?.items),
    refetchIntervalInBackground: false,
  });
}

export function useRun(id: string | undefined) {
  return useQuery<RunResponse>({
    queryKey: ["runs", id],
    enabled: Boolean(id),
    queryFn: async () => {
      const res = await api.get<Envelope<RunResponse>>(`/api/v1/runs/${id}`);
      return unwrap(res);
    },
    refetchInterval: (query) => runRefetchInterval(query.state.data),
    refetchIntervalInBackground: false,
  });
}

export function useTriggerRun() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateRunRequest) => {
      const res = await api.post<Envelope<RunResponse>>("/api/v1/runs", body);
      return unwrap(res);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["runs"] });
    },
  });
}
