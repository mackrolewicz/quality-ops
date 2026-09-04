import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";

import { api } from "./client";
import { unwrap, unwrapList, type ListResult } from "./envelope";
import type {
  CreateEnvironmentRequest,
  Envelope,
  EnvironmentResponse,
  UpdateEnvironmentRequest,
} from "./types";

const DEFAULT_SIZE = 20;

export function useEnvironments(
  projectId: string | undefined,
  page = 1,
  size = DEFAULT_SIZE,
) {
  return useQuery<ListResult<EnvironmentResponse>>({
    queryKey: ["projects", projectId, "environments", { page, size }],
    enabled: Boolean(projectId),
    queryFn: async () => {
      const res = await api.get<Envelope<EnvironmentResponse[]>>(
        `/api/v1/projects/${projectId}/environments`,
        { params: { page, size } },
      );
      return unwrapList(res);
    },
    placeholderData: keepPreviousData,
  });
}

export function useEnvironment(id: string | undefined) {
  return useQuery<EnvironmentResponse>({
    queryKey: ["environments", id],
    enabled: Boolean(id),
    queryFn: async () => {
      const res = await api.get<Envelope<EnvironmentResponse>>(
        `/api/v1/environments/${id}`,
      );
      return unwrap(res);
    },
  });
}

export function useCreateEnvironment(projectId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateEnvironmentRequest) => {
      const res = await api.post<Envelope<EnvironmentResponse>>(
        `/api/v1/projects/${projectId}/environments`,
        body,
      );
      return unwrap(res);
    },
    onSuccess: () => {
      qc.invalidateQueries({
        queryKey: ["projects", projectId, "environments"],
      });
    },
  });
}

export function useUpdateEnvironment(projectId: string, id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: UpdateEnvironmentRequest) => {
      const res = await api.put<Envelope<EnvironmentResponse>>(
        `/api/v1/environments/${id}`,
        body,
      );
      return unwrap(res);
    },
    onSuccess: () => {
      qc.invalidateQueries({
        queryKey: ["projects", projectId, "environments"],
      });
      qc.invalidateQueries({ queryKey: ["environments", id] });
    },
  });
}

export function useDeleteEnvironment(projectId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await api.delete(`/api/v1/environments/${id}`);
    },
    onSuccess: () => {
      qc.invalidateQueries({
        queryKey: ["projects", projectId, "environments"],
      });
    },
  });
}
