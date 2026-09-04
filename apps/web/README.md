# QualityOps Web

React 18 + TypeScript frontend for the QualityOps Lab platform.

## Stack

- Vite 5 (build + dev server, port 5173, `strictPort`)
- React 18 + React Router v6 (routing, lazy route modules)
- TanStack Query v5 (all server state)
- Axios (API client with in-memory JWT + single-flight refresh)
- React Hook Form (forms)
- Tailwind CSS (styling — tokens mirror `DESIGN.md`)
- Vitest + React Testing Library (unit / component tests)
- Playwright (E2E smoke test in `e2e/`)

## Run locally

```bash
npm install
npm run dev
```

Dev server runs on http://localhost:5173. The backend Gateway must be reachable
at `VITE_API_URL` (default `http://localhost:8090`). CORS on the backend only
allows `http://localhost:5173` and `http://localhost:8090`, so the dev server
port must stay 5173.

Start the backend stack first:

```bash
docker compose -f infra/compose/docker-compose.yml \
               -f infra/compose/docker-compose.dev.yml up -d
```

## Environment

`VITE_API_URL` is a **build-time** variable (Vite inlines `import.meta.env` at
build). Locally it comes from `.env.development`. In Docker it is passed as the
`VITE_API_URL` build ARG in `infra/docker/Dockerfile.web` (wired through the
`web` service in `docker-compose.dev.yml`). Changing it requires a rebuild.

Auth tokens live in memory only (never `localStorage` / `sessionStorage` /
cookies). A full page reload drops the session and returns you to `/login`.

## Available scripts

```bash
npm run dev          # start dev server
npm run build        # typecheck (tsc --noEmit) + production build to dist/
npm run preview      # preview the production build
npm run lint         # ESLint (zero warnings allowed)
npm run typecheck    # tsc --noEmit
npm test             # Vitest (CI runs: npm test -- --run)
npm run test:e2e     # Playwright smoke test (needs the full stack running)
```

## Docker

`infra/docker/Dockerfile.web` builds the static bundle and serves it with
`nginxinc/nginx-unprivileged` on container port 8080. The compose `web` service
maps host `5173 -> 8080` and waits for the gateway to be healthy.

## Layout

```
src/
├── api/            # axios client, envelope helpers, TanStack Query hooks per resource
├── components/     # shared UI primitives (Button, DataTable, StatusBadge, Modal, …)
├── features/
│   ├── auth/       # AuthProvider, RequireAuth, RBAC (useRole / <Can>), LoginPage
│   ├── dashboard/  # client-side aggregated dashboard
│   ├── projects/   # projects list + detail + tabs (suites / environments / runs)
│   ├── suites/     # suite detail + test cases
│   ├── runs/       # runs list + run detail + trigger modal + polling helpers
│   └── settings/   # read-only account/settings stub
├── hooks/          # useDebounce
├── layouts/        # AppLayout, Sidebar, TopBar, UserMenu
├── lib/            # queryClient, time/slug/id helpers
├── types/          # shared TS types (auth)
├── router.tsx      # createBrowserRouter config
├── App.tsx         # providers + RouterProvider
└── main.tsx        # entry point
```
