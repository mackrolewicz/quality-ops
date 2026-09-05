import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";

import { api } from "./client";
import { unwrap, unwrapList, type ListResult } from "./envelope";
import type {
  CreateSuiteRequest,
  Envelope,
  TestSuiteResponse,
  UpdateSuiteRequest,
} from "./types";

const DEFAULT_SIZE = 20;

export function useSuites(
  projectId: string | undefined,
  page = 1,
  size = DEFAULT_SIZE,
) {
  return useQuery<ListResult<TestSuiteResponse>>({
    queryKey: ["projects", projectId, "suites", { page, size }],
    enabled: Boolean(projectId),
    queryFn: async () => {
      const res = await api.get<Envelope<TestSuiteResponse[]>>(
        `/api/v1/projects/${projectId}/suites`,
        { params: { page, size } },
      );
      return unwrapList(res);
    },
    placeholderData: keepPreviousData,
  });
}

export function useSuite(id: string | undefined) {
  return useQuery<TestSuiteResponse>({
    queryKey: ["suites", id],
    enabled: Boolean(id),
    queryFn: async () => {
      const res = await api.get<Envelope<TestSuiteResponse>>(
        `/api/v1/suites/${id}`,
      );
      return unwrap(res);
    },
  });
}

export function useCreateSuite(projectId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateSuiteRequest) => {
      const res = await api.post<Envelope<TestSuiteResponse>>(
        `/api/v1/projects/${projectId}/suites`,
        body,
      );
      return unwrap(res);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["projects", projectId, "suites"] });
    },
  });
}

export function useUpdateSuite(projectId: string, id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: UpdateSuiteRequest) => {
      const res = await api.put<Envelope<TestSuiteResponse>>(
        `/api/v1/suites/${id}`,
        body,
      );
      return unwrap(res);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["projects", projectId, "suites"] });
      qc.invalidateQueries({ queryKey: ["suites", id] });
    },
  });
}

export function useDeleteSuite(projectId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await api.delete(`/api/v1/suites/${id}`);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["projects", projectId, "suites"] });
    },
  });
}
