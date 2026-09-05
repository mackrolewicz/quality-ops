import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";

import { api } from "./client";
import { unwrap, unwrapList, type ListResult } from "./envelope";
import type {
  CreateProjectRequest,
  Envelope,
  ProjectResponse,
  UpdateProjectRequest,
} from "./types";

const DEFAULT_SIZE = 20;

export function useProjects(page = 1, size = DEFAULT_SIZE) {
  return useQuery<ListResult<ProjectResponse>>({
    queryKey: ["projects", { page, size }],
    queryFn: async () => {
      const res = await api.get<Envelope<ProjectResponse[]>>(
        "/api/v1/projects",
        { params: { page, size } },
      );
      return unwrapList(res);
    },
    placeholderData: keepPreviousData,
  });
}

export function useProject(id: string | undefined) {
  return useQuery<ProjectResponse>({
    queryKey: ["projects", id],
    enabled: Boolean(id),
    queryFn: async () => {
      const res = await api.get<Envelope<ProjectResponse>>(
        `/api/v1/projects/${id}`,
      );
      return unwrap(res);
    },
  });
}

export function useCreateProject() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateProjectRequest) => {
      const res = await api.post<Envelope<ProjectResponse>>(
        "/api/v1/projects",
        body,
      );
      return unwrap(res);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["projects"] });
    },
  });
}

export function useUpdateProject(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: UpdateProjectRequest) => {
      const res = await api.put<Envelope<ProjectResponse>>(
        `/api/v1/projects/${id}`,
        body,
      );
      return unwrap(res);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["projects"] });
    },
  });
}

export function useDeleteProject() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await api.delete(`/api/v1/projects/${id}`);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["projects"] });
    },
  });
}
