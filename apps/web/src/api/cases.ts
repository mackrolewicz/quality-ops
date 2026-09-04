import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";

import { api } from "./client";
import { unwrap, unwrapList, type ListResult } from "./envelope";
import type {
  CreateCaseRequest,
  Envelope,
  TestCaseResponse,
  UpdateCaseRequest,
} from "./types";

const DEFAULT_SIZE = 100;

export function useCases(
  suiteId: string | undefined,
  page = 1,
  size = DEFAULT_SIZE,
) {
  return useQuery<ListResult<TestCaseResponse>>({
    queryKey: ["suites", suiteId, "cases", { page, size }],
    enabled: Boolean(suiteId),
    queryFn: async () => {
      const res = await api.get<Envelope<TestCaseResponse[]>>(
        `/api/v1/suites/${suiteId}/cases`,
        { params: { page, size } },
      );
      return unwrapList(res);
    },
    placeholderData: keepPreviousData,
  });
}

export function useCase(id: string | undefined) {
  return useQuery<TestCaseResponse>({
    queryKey: ["cases", id],
    enabled: Boolean(id),
    queryFn: async () => {
      const res = await api.get<Envelope<TestCaseResponse>>(
        `/api/v1/cases/${id}`,
      );
      return unwrap(res);
    },
  });
}

export function useCreateCase(suiteId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateCaseRequest) => {
      const res = await api.post<Envelope<TestCaseResponse>>(
        `/api/v1/suites/${suiteId}/cases`,
        body,
      );
      return unwrap(res);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["suites", suiteId, "cases"] });
    },
  });
}

export function useUpdateCase(suiteId: string, id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: UpdateCaseRequest) => {
      const res = await api.put<Envelope<TestCaseResponse>>(
        `/api/v1/cases/${id}`,
        body,
      );
      return unwrap(res);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["suites", suiteId, "cases"] });
      qc.invalidateQueries({ queryKey: ["cases", id] });
    },
  });
}

export function useDeleteCase(suiteId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await api.delete(`/api/v1/cases/${id}`);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["suites", suiteId, "cases"] });
    },
  });
}
