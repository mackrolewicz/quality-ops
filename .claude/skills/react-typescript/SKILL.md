---
name: react-typescript
description: Use this skill when writing or reviewing React + TypeScript frontend code. Covers component patterns, TanStack Query, state management, Tailwind CSS, routing, and project-specific conventions.
---

# React + TypeScript patterns

This skill is the source of truth for how frontend code is written in this repo.

## 1. Project structure

```
apps/web/src/
├── main.tsx                 # entry point — mounts App
├── App.tsx                  # router + providers
├── api/                     # API client layer
│   ├── client.ts            # axios/fetch instance with auth
│   ├── projects.ts          # project API hooks (TanStack Query)
│   ├── runs.ts              # run API hooks
│   └── types.ts             # shared API response types
├── components/              # shared UI components
│   ├── Button.tsx
│   ├── DataTable.tsx
│   ├── StatusBadge.tsx
│   └── Layout.tsx
├── features/                # feature modules (self-contained)
│   ├── projects/
│   │   ├── ProjectList.tsx
│   │   ├── ProjectDetail.tsx
│   │   └── CreateProjectForm.tsx
│   ├── runs/
│   ├── results/
│   └── environments/
├── hooks/                   # shared custom hooks
│   ├── useAuth.ts
│   └── useDebounce.ts
├── pages/                   # route-level components
│   ├── DashboardPage.tsx
│   ├── ProjectsPage.tsx
│   └── RunDetailPage.tsx
├── layouts/                 # page layouts (sidebar, header, etc.)
│   └── AppLayout.tsx
└── types/                   # shared TypeScript types
    ├── project.ts
    ├── run.ts
    └── user.ts
```

## 2. Component patterns

### Functional components only, named exports

```typescript
export function ProjectCard({ project }: ProjectCardProps) {
  return (
    <div className="rounded-lg border p-4">
      <h3 className="text-lg font-semibold">{project.name}</h3>
      <p className="text-sm text-gray-500">{project.description}</p>
    </div>
  );
}
```

**Never:** default exports, class components, `React.FC` type.

### Props as explicit interfaces

```typescript
interface ProjectCardProps {
  project: Project;
  onSelect?: (id: string) => void;
}
```

### Small components

If a component file exceeds ~100 lines, extract sub-components or hooks.

## 3. TanStack Query for all server state

**Every API call goes through TanStack Query.** No manual `useEffect` + `fetch`.

```typescript
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";

export function useProjects() {
  return useQuery({
    queryKey: ["projects"],
    queryFn: () => api.get<Project[]>("/api/v1/projects").then(r => r.data),
  });
}

export function useCreateProject() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (data: CreateProjectRequest) =>
      api.post<Project>("/api/v1/projects", data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["projects"] });
    },
  });
}
```

**Query key conventions:**
- `["projects"]` — list all projects
- `["projects", id]` — single project
- `["projects", id, "runs"]` — runs for a project

### Using queries in components

```typescript
export function ProjectList() {
  const { data: projects, isLoading, error } = useProjects();

  if (isLoading) return <Skeleton />;
  if (error) return <ErrorMessage error={error} />;

  return (
    <div className="grid gap-4">
      {projects.map(p => <ProjectCard key={p.id} project={p} />)}
    </div>
  );
}
```

## 4. TypeScript conventions

### Strict mode

`tsconfig.json` has `strict: true`. No exceptions.

### No `any`

If you must use `any`, add a comment explaining why. Prefer `unknown` and
narrow with type guards.

```typescript
// Bad
const data: any = response.data;

// Good
const data: unknown = response.data;
if (isProject(data)) { ... }
```

### Types in dedicated files

Shared types go in `src/types/`. API response types go in `src/api/types.ts`.

```typescript
export interface Project {
  id: string;
  name: string;
  description: string;
  createdAt: string;
}

export interface TestRun {
  id: string;
  projectId: string;
  status: "PENDING" | "RUNNING" | "PASSED" | "FAILED";
  startedAt: string | null;
  finishedAt: string | null;
}
```

Use string unions for enums, not TypeScript `enum`.

## 5. Styling with Tailwind CSS

All styling uses Tailwind utility classes. No CSS modules, styled-components,
or inline `style` props.

```typescript
<button className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700">
  Run Tests
</button>
```

For conditional classes, use `clsx`:

```typescript
import { clsx } from "clsx";

<span className={clsx(
  "rounded-full px-2 py-1 text-xs font-medium",
  status === "PASSED" && "bg-green-100 text-green-800",
  status === "FAILED" && "bg-red-100 text-red-800",
  status === "RUNNING" && "bg-blue-100 text-blue-800",
)}>
  {status}
</span>
```

## 6. Routing

Use React Router v6 with lazy-loaded routes:

```typescript
import { createBrowserRouter, RouterProvider } from "react-router-dom";
import { lazy, Suspense } from "react";

const DashboardPage = lazy(() => import("./pages/DashboardPage"));
const ProjectsPage = lazy(() => import("./pages/ProjectsPage"));

const router = createBrowserRouter([
  {
    path: "/",
    element: <AppLayout />,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: "projects", element: <ProjectsPage /> },
      { path: "projects/:id", element: <ProjectDetailPage /> },
    ],
  },
]);
```

## 7. Forms

Use React Hook Form for forms with validation:

```typescript
import { useForm } from "react-hook-form";

export function CreateProjectForm() {
  const { register, handleSubmit, formState: { errors } } = useForm<CreateProjectInput>();
  const createProject = useCreateProject();

  return (
    <form onSubmit={handleSubmit(data => createProject.mutate(data))}>
      <input {...register("name", { required: "Name is required" })} />
      {errors.name && <span className="text-red-500">{errors.name.message}</span>}
      <button type="submit" disabled={createProject.isPending}>Create</button>
    </form>
  );
}
```

## 8. Error handling

Use error boundaries for unexpected errors, inline error states for expected ones.

```typescript
// Expected errors — handle inline
if (error) return <ErrorMessage error={error} retry={refetch} />;

// Unexpected errors — error boundary catches these
// Wrap route-level components with an ErrorBoundary
```

## 9. Testing

- **Unit/component tests:** Vitest + React Testing Library.
- **E2E tests:** Playwright (see `api-testing` skill).
- Test file location: `apps/web/tests/` or co-located `__tests__/` folders.
- Test what the user sees, not implementation details.

```typescript
import { render, screen } from "@testing-library/react";
import { ProjectCard } from "./ProjectCard";

test("displays project name", () => {
  render(<ProjectCard project={{ id: "1", name: "My Project", description: "" }} />);
  expect(screen.getByText("My Project")).toBeInTheDocument();
});
```

## 10. Import ordering

```typescript
// 1. React
import { useState, useEffect } from "react";

// 2. Third-party
import { useQuery } from "@tanstack/react-query";
import { clsx } from "clsx";

// 3. Local — API / hooks / utils
import { useProjects } from "../api/projects";
import { useDebounce } from "../hooks/useDebounce";

// 4. Local — components
import { DataTable } from "../components/DataTable";
import { StatusBadge } from "../components/StatusBadge";

// 5. Local — types
import type { Project } from "../types/project";
```
