import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";

import { api } from "./client";
import { unwrap, unwrapList, type ListResult } from "./envelope";
import type {
  Envelope,
  RegisterRepositoryConnectionRequest,
  RepositoryConnectionResponse,
  TestConnectionResponse,
  UpdateRepositoryConnectionRequest,
} from "./types";

const DEFAULT_SIZE = 50;

/** ADR-009 §11 — repository-connection CRUD + the outbound "test connection"
 *  action. Repo test specs are authored via the existing case editor
 *  (`useCreateCase` / `useUpdateCase`, `repositories.ts` only manages the
 *  connection resource itself — there is no "run now from a connection"). */
export function useRepositoryConnections(
  projectId: string | undefined,
  page = 1,
  size = DEFAULT_SIZE,
) {
  return useQuery<ListResult<RepositoryConnectionResponse>>({
    queryKey: ["projects", projectId, "repository-connections", { page, size }],
    enabled: Boolean(projectId),
    queryFn: async () => {
      const res = await api.get<Envelope<RepositoryConnectionResponse[]>>(
        `/api/v1/projects/${projectId}/repository-connections`,
        { params: { page, size } },
      );
      return unwrapList(res);
    },
    placeholderData: keepPreviousData,
  });
}

export function useRepositoryConnection(id: string | undefined) {
  return useQuery<RepositoryConnectionResponse>({
    queryKey: ["repository-connections", id],
    enabled: Boolean(id),
    queryFn: async () => {
      const res = await api.get<Envelope<RepositoryConnectionResponse>>(
        `/api/v1/repository-connections/${id}`,
      );
      return unwrap(res);
    },
  });
}

export function useCreateRepositoryConnection(projectId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: RegisterRepositoryConnectionRequest) => {
      const res = await api.post<Envelope<RepositoryConnectionResponse>>(
        `/api/v1/projects/${projectId}/repository-connections`,
        body,
      );
      return unwrap(res);
    },
    onSuccess: () => {
      qc.invalidateQueries({
        queryKey: ["projects", projectId, "repository-connections"],
      });
    },
  });
}

export function useUpdateRepositoryConnection(projectId: string, id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: UpdateRepositoryConnectionRequest) => {
      const res = await api.put<Envelope<RepositoryConnectionResponse>>(
        `/api/v1/repository-connections/${id}`,
        body,
      );
      return unwrap(res);
    },
    onSuccess: () => {
      qc.invalidateQueries({
        queryKey: ["projects", projectId, "repository-connections"],
      });
      qc.invalidateQueries({ queryKey: ["repository-connections", id] });
    },
  });
}

export function useDeleteRepositoryConnection(projectId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await api.delete(`/api/v1/repository-connections/${id}`);
    },
    onSuccess: () => {
      qc.invalidateQueries({
        queryKey: ["projects", projectId, "repository-connections"],
      });
    },
  });
}

export function useTestRepositoryConnection() {
  return useMutation({
    mutationFn: async (id: string) => {
      const res = await api.post<Envelope<TestConnectionResponse>>(
        `/api/v1/repository-connections/${id}/test`,
      );
      return unwrap(res);
    },
  });
}
