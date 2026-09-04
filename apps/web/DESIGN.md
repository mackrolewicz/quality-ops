# DESIGN.md — QualityOps Web

Design system for **QualityOps**, a B2B QA SaaS platform for engineering teams.
Derived from `apps/web/STITCH_PROMPTS.md`. This file is the source of truth for
visual tokens; `tailwind.config.js` mirrors it and React components consume the
semantic Tailwind classes below — **never** raw hex values in components.

Professional dark-theme SaaS aesthetic. Data-dense but not cluttered. No
decorative illustrations.

---

## 1. Color tokens

### Surfaces & structure

| Token | Hex | Tailwind key | Usage |
|---|---|---|---|
| `bg` | `#0F1117` | `bg-canvas` | App background |
| `surface` | `#1A1D27` | `bg-surface` | Cards, sidebar, modals, table headers |
| `surface-raised` | `#22252F` | `bg-surface-raised` | Hover states, nested panels, inputs |
| `border` | `#2A2D3A` | `border-line` | All borders, dividers |
| `border-strong` | `#3A3E4C` | `border-line-strong` | Input focus ring base, emphasized dividers |

### Text

| Token | Hex | Tailwind key | Usage |
|---|---|---|---|
| `text-primary` | `#F3F4F6` | `text-primary` | Headings, key values |
| `text-secondary` | `#C7CAD1` | `text-secondary` | Body text |
| `text-muted` | `#9CA3AF` | `text-muted` | Descriptions, secondary labels |
| `text-subtle` | `#6B7280` | `text-subtle` | Timestamps, placeholder, disabled |

### Accent (blue-purple)

| Token | Hex | Tailwind key | Usage |
|---|---|---|---|
| `accent` | `#6366F1` | `bg-accent` / `text-accent` | Primary buttons, links, active nav |
| `accent-hover` | `#7C7FF2` | `bg-accent-hover` | Primary button hover |
| `accent-from` | `#6366F1` | gradient start | `bg-gradient-accent` |
| `accent-to` | `#8B5CF6` | gradient end | `bg-gradient-accent` |
| `accent-subtle` | `#6366F11A` | `bg-accent-subtle` | Active nav background, selected rows |

Gradient utility: `bg-gradient-accent` = `linear-gradient(135deg, #6366F1, #8B5CF6)`.

### Status colors (runs, results, environments)

| Status | Hex | Tailwind key | Applies to |
|---|---|---|---|
| `PASSED` | `#22C55E` | `status-passed` | run + result status, "Paid" |
| `FAILED` | `#EF4444` | `status-failed` | run + result status |
| `RUNNING` | `#F59E0B` | `status-running` | run status |
| `PENDING` | `#6B7280` | `status-pending` | run status |
| `SKIPPED` | `#3B82F6` | `status-skipped` | result status |
| `FLAKY` | `#F59E0B` | `status-flaky` | result status (amber, same as running) |
| `CANCELLED` | `#6B7280` | `status-cancelled` | run status (grey, same as pending) |

Each status has a `-bg` variant at ~12% opacity for badge backgrounds
(e.g. `bg-status-passed/15 text-status-passed`).

### Environment health dot

| Environment `status` + `type` | Dot color |
|---|---|
| `ACTIVE` | `status-passed` (green) |
| `INACTIVE` | `status-pending` (grey) |

### Role badge colors

| Role | Hex | Tailwind key |
|---|---|---|
| `OWNER` | `#8B5CF6` | `role-owner` (purple) |
| `ADMIN` | `#3B82F6` | `role-admin` (blue) |
| `MEMBER` | `#22C55E` | `role-member` (green) |
| `VIEWER` | `#6B7280` | `role-viewer` (grey) |

---

## 2. Typography

- **Font family:** `Inter, "Geist", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif`.
  Loaded via `@fontsource`/CSS `@font-face` or a `<link>` — no runtime JS font loader.
- **Monospace:** `"JetBrains Mono", "Fira Code", ui-monospace, SFMono-Regular, Menlo, monospace`
  — used only for error messages / stack traces and ID badges.

| Role | Classes |
|---|---|
| Page title (h1) | `text-2xl font-semibold text-primary` |
| Section heading (h2) | `text-lg font-semibold text-primary` |
| Card title (h3) | `text-base font-semibold text-primary` |
| Body | `text-sm text-secondary` |
| Muted / description | `text-sm text-muted` |
| Label (form, table header) | `text-xs font-medium uppercase tracking-wide text-subtle` |
| Stat value | `text-3xl font-semibold text-primary tabular-nums` |
| Timestamp / meta | `text-xs text-subtle` |
| Code / error | `font-mono text-xs text-secondary` |

---

## 3. Spacing & layout

- Base unit: Tailwind default 4px scale.
- **Page padding:** `px-8 py-6` on the main content region.
- **Card padding:** `p-5` (stat cards `p-4`).
- **Sidebar width:** `w-60` (240px), fixed, full height.
- **Top bar height:** `h-14` (56px), sticky.
- **Content max width:** `max-w-7xl` centered for wide pages; tables/full-bleed
  sections may use full width.
- **Grid gaps:** `gap-4` for card grids, `gap-6` between major sections.
- **Stat card row:** `grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4`.
- **Project card grid:** `grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4`.
- **Dashboard two-column split:** `grid grid-cols-1 lg:grid-cols-5 gap-6`
  (`lg:col-span-3` left / `lg:col-span-2` right).

---

## 4. Radius, borders, shadows

| Token | Value | Tailwind |
|---|---|---|
| Card radius | 8px | `rounded-lg` |
| Button / input / badge radius | 6px | `rounded-md` |
| Pill / status badge | full | `rounded-full` |
| Card border | 1px `border` | `border border-line` |
| Card hover (interactive) | lift | `hover:border-line-strong hover:bg-surface-raised transition-colors` |
| Focus ring | `ring-2 ring-accent/60 ring-offset-2 ring-offset-canvas` |
| Modal shadow | `shadow-2xl shadow-black/40` |

No large decorative shadows on cards — separation is via border + surface color.

---

## 5. Components

### Button (`components/Button.tsx`)

Variants — prop `variant`:

| Variant | Classes |
|---|---|
| `primary` | `bg-gradient-accent text-white hover:opacity-95` |
| `secondary` | `bg-surface-raised text-secondary border border-line hover:border-line-strong` |
| `ghost` | `text-muted hover:text-primary hover:bg-surface-raised` |
| `danger` | `bg-status-failed/15 text-status-failed border border-status-failed/30 hover:bg-status-failed/25` |

Sizes — prop `size`: `sm` = `h-8 px-3 text-xs`, `md` = `h-9 px-4 text-sm` (default),
`lg` = `h-11 px-5 text-sm`.
Base: `inline-flex items-center justify-center gap-2 rounded-md font-medium
disabled:opacity-50 disabled:pointer-events-none focus-visible:outline-none
focus-visible:ring-2 focus-visible:ring-accent/60`.
When `isLoading`, show a spinner and set `disabled`.

### Input / Field (`components/Field.tsx`, `components/TextInput.tsx`)

- Input: `h-9 w-full rounded-md bg-surface-raised border border-line px-3 text-sm
  text-primary placeholder:text-subtle focus:border-accent focus:ring-2
  focus:ring-accent/40 outline-none`.
- Invalid: `border-status-failed focus:border-status-failed focus:ring-status-failed/30`.
- Label: label classes from §2. Error text: `text-xs text-status-failed mt-1`.
- Help text: `text-xs text-subtle mt-1`.

### StatusBadge (`components/StatusBadge.tsx`)

`inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium`
plus per-status `bg-status-*/15 text-status-*`. Optional leading dot
(`h-1.5 w-1.5 rounded-full bg-current`). Accepts `RunStatus | ResultStatus`.

### RoleBadge (`components/RoleBadge.tsx`)

Same shape as StatusBadge using `role-*` colors.

### Card (`components/Card.tsx`)

`rounded-lg border border-line bg-surface p-5`. Optional `title` / `actions`
header row (`flex items-center justify-between mb-4`).

### StatCard (`components/StatCard.tsx`)

Card with `p-4`. Layout: label (§2 label style) → value (stat value style) →
optional delta row (`text-xs` with `text-status-passed` / `text-status-failed`
and ▲/▼). Optional `accent` prop tints the value color (e.g. failed → red).

### DataTable (`components/DataTable.tsx`)

Generic, typed `DataTable<Row>`:
- Wrapper: `overflow-x-auto rounded-lg border border-line`.
- `table`: `w-full text-sm`.
- `thead`: `bg-surface text-subtle`; `th`: `px-4 py-2 text-left font-medium
  uppercase tracking-wide text-xs`.
- `tbody tr`: `border-t border-line hover:bg-surface-raised`; `td`: `px-4 py-3
  text-secondary`.
- Failed result rows: add `bg-status-failed/5`.
- Props: `columns: { key, header, render?, className? }[]`, `rows`, `getRowId`,
  `onRowClick?`, `emptyState?`.

### EmptyState (`components/EmptyState.tsx`)

Centered: icon in a `h-12 w-12 rounded-full bg-surface-raised` circle →
`text-base font-medium text-primary` title → `text-sm text-muted` description →
optional action `Button`. Container `py-16 text-center`.

### Skeleton / Loading (`components/Skeleton.tsx`, `components/Spinner.tsx`)

- Skeleton block: `animate-pulse rounded-md bg-surface-raised`.
- Table/list loading = 4–6 stacked skeleton rows matching row height.
- Spinner: `animate-spin` ring using `border-2 border-line border-t-accent`.

### ErrorState (`components/ErrorState.tsx`)

`rounded-lg border border-status-failed/30 bg-status-failed/5 p-4 text-sm`
with `text-status-failed` title (from API `error.code` / `error.message`) and a
`Retry` `Button variant="secondary" size="sm"`.

### Modal / Dialog (`components/Modal.tsx`)

Overlay `fixed inset-0 bg-black/60 backdrop-blur-sm`; panel `w-full max-w-lg
rounded-lg border border-line bg-surface p-6 shadow-2xl`. Close on overlay click
+ Esc. Used for create/edit forms and delete confirmation.

### ConfirmDialog (`components/ConfirmDialog.tsx`)

Modal preset for destructive actions: title, body, `Cancel` (ghost) +
`danger` confirm button. Used for all delete actions.

### Tabs (`components/Tabs.tsx`)

Underline style: container `border-b border-line`; tab `px-1 py-3 text-sm
font-medium text-muted`; active `text-primary border-b-2 border-accent -mb-px`.

### Breadcrumb (`components/Breadcrumb.tsx`)

`flex items-center gap-2 text-sm text-muted`; separator `/`; last crumb
`text-primary`.

### Pagination (`components/Pagination.tsx`)

`flex items-center justify-between text-sm text-muted`; Prev/Next
`Button variant="secondary" size="sm"`; shows `page` / derived total pages from
`meta.total` + `meta.pageSize`.

---

## 6. App shell

### Sidebar (`layouts/Sidebar.tsx`)

- `w-60 shrink-0 h-screen sticky top-0 border-r border-line bg-surface flex flex-col`.
- Top: logo lockup — shield/checkmark icon in `bg-gradient-accent` rounded square
  + `QualityOps` wordmark (`text-primary font-semibold`).
- Nav items: `Dashboard` (`/`), `Projects` (`/projects`), `Test Suites`
  (`/suites` — Phase 1: redirect/inform via project), `Runs` (`/runs`),
  `Results` (`/results` or nested), `Settings` (`/settings`, minimal).
  Each: `flex items-center gap-3 rounded-md px-3 py-2 text-sm text-muted
  hover:bg-surface-raised hover:text-primary`; active (NavLink) `bg-accent-subtle
  text-primary`.
- Bottom: user block — avatar circle (initials) + email + org name +
  chevron; opens a small menu with `Sign out`.

### TopBar (`layouts/TopBar.tsx`)

- `h-14 sticky top-0 z-10 border-b border-line bg-canvas/80 backdrop-blur
  flex items-center justify-between px-8`.
- Left: current page title (from route handle) or breadcrumb.
- Right: notification bell (`ghost` icon button, non-functional placeholder,
  `title="Notifications"`), user avatar menu (initials → `Sign out`).

### AppLayout (`layouts/AppLayout.tsx`)

`flex min-h-screen bg-canvas text-secondary`; `<Sidebar />` + `<div class="flex-1
min-w-0"><TopBar /><main class="px-8 py-6"><Outlet /></main></div>`.

---

## 7. Screen specs (Phase 1)

### Login (`/login`) — `features/auth/LoginPage.tsx`

Centered card (`max-w-sm`) on `bg-canvas`:
- Logo lockup top center.
- `TextInput` email + password (React Hook Form).
- Primary full-width `Sign in` button (`bg-gradient-accent`), `isLoading` while
  the mutation is pending.
- `Forgot password?` — `text-xs text-subtle` link, `href="#"`,
  `aria-disabled`, no-op (Phase 4).
- Divider `or continue with`.
- **GitHub** + **Google** buttons: `secondary` variant, `disabled`, with
  `title="SSO is coming in Phase 4"`. Visual placeholders only.
- Footer: `Don't have an account? Contact your admin` (`text-xs text-subtle`).
- API error (401) → inline `ErrorState` above the form: "Invalid email or password."
- Field validation errors inline (email format, password min 8).

### Dashboard (`/`) — `features/dashboard/DashboardPage.tsx`

- 4 `StatCard`s (computed client-side from the runs list within the last 7 days,
  and project analytics):
  - **Total Runs (7d)** — count.
  - **Pass Rate %** — `passed / (passed+failed)` across recent runs; accent color
    green ≥ 80, amber 50–79, red < 50.
  - **Failed Runs** — count, red accent.
  - **Active Environments** — count across projects, or **Flaky Results** count
    (from results) — label "Flaky Tests", amber. Prefer Active Environments if
    results aggregation is too heavy; label accordingly.
- Two-column split (§3):
  - Left: **Recent Runs** `DataTable` — columns: Run (short id, mono badge),
    Project (name), Status (`StatusBadge`), Triggered by (short id / "you"),
    Started (relative time). Row click → `/runs/:id`.
  - Right: **Pass rate (14d)** — lightweight inline SVG/bar sparkline built from
    grouped run outcomes per day (no charting lib required; a simple vertical
    bar list is acceptable). Green bars.
- Bottom: **Active environments** — up to 6 cards: name, `baseUrl` (truncated,
  mono), health dot + `type`. Sourced by iterating the project list and fetching
  each project's environments (bounded to first ~5 projects).
- Loading: skeleton stat row + skeleton table. Empty (no runs): `EmptyState`
  "No runs yet" + link to Projects.

### Projects list (`/projects`) — `features/projects/ProjectsPage.tsx`

- Header: title + search `TextInput` (client-side filter on name/description,
  debounced) + `+ New Project` `primary` button (visible only to OWNER/ADMIN;
  others see it `disabled` with tooltip).
- Responsive card grid (§3). `ProjectCard`: name (h3), description (2-line
  clamp, muted), stat row (suite count, last run `StatusBadge`, last run relative
  time — derived from a runs query filtered by `projectId`), `View project →`
  link bottom-right. Whole card is a link to `/projects/:id`.
- `+ New Project` opens `Modal` with `CreateProjectForm` (name, description,
  slug — slug auto-suggested from name as lowercase kebab-case, editable,
  pattern `^[a-z0-9]+(-[a-z0-9]+)*$`).
- Empty: `EmptyState` icon + "No projects yet" + "Create your first project".
- Loading: 6 skeleton cards. Error: `ErrorState` with retry.

### Project detail (`/projects/:id`) — `features/projects/ProjectDetailPage.tsx`

- `Breadcrumb`: Projects / {name}.
- Header: name (h1) + description; `Edit` (OWNER/ADMIN) + `Delete` (OWNER/ADMIN,
  `ConfirmDialog`) top-right. 3 summary stats: test suites count, total runs
  count, last run `StatusBadge`.
- `Tabs`: **Test Suites** | **Environments** | **Recent Runs**.
  - **Test Suites tab:** `+ Add Suite` (OWNER/ADMIN/MEMBER) above a `DataTable`
    — Suite name, Type (`API`/`UI`/`PERFORMANCE`), Cases count (from a per-suite
    cases query), Actions (Edit / Delete / "Manage cases" → expands or routes to
    `/suites/:id`). Row click → suite detail.
  - **Environments tab:** `+ Add Environment` above a `DataTable` — Name,
    Base URL (mono, truncated), Type, Status (`StatusBadge`-style dot), Actions
    (Edit / Delete).
  - **Recent Runs tab:** `Trigger new run` button (opens `TriggerRunModal`)
    above a `DataTable` — Run id (mono), Suite, Environment, Status, Triggered
    by, Started at. Row click → `/runs/:id`.
- Edit opens `Modal` with `EditProjectForm` (name, description).

### Suite detail (`/suites/:id`) — `features/suites/SuiteDetailPage.tsx`

- Breadcrumb: Projects / {project} / {suite}.
- Header: suite name + type badge; `Edit` / `Delete` (RBAC as above).
- **Test cases** `DataTable`: Order, Name, Description (truncated), Actions
  (Edit / Delete). `+ Add Case` button (OWNER/ADMIN/MEMBER). Create form: name,
  description, orderIndex (number, optional on create; defaults to next).
- Empty: `EmptyState` "No test cases yet — add one so runs produce results."

### Trigger run (`TriggerRunModal` in `features/runs/`)

- Fields: **Suite** select (suites for the project), **Environment** select
  (environments for the project). Both required. React Hook Form.
- Warn (non-blocking) if the chosen suite has 0 cases: "This suite has no test
  cases; the run will complete with no results."
- Submit → `POST /api/v1/runs { projectId, suiteId, environmentId }` → on success
  close modal and navigate to `/runs/:id`.
- Visible to OWNER/ADMIN/MEMBER; VIEWER sees the button `disabled`.

### Runs list (`/runs`) — `features/runs/RunsPage.tsx`

- Filters row: Project select, Status select (`PENDING/RUNNING/PASSED/FAILED/
  CANCELLED`), both optional → passed as query params.
- `DataTable`: Run id (mono), Project, Suite, Environment, Status (`StatusBadge`),
  Triggered by, Started, Duration (`completedAt - startedAt` or `—`). Row click →
  `/runs/:id`. `Pagination` from `meta`.
- Auto-refresh: while any row is `PENDING`/`RUNNING`, set the query
  `refetchInterval` to 2000ms; stop when none are active.

### Run detail (`/runs/:id`) — `features/runs/RunDetailPage.tsx`

- Header: `Run {shortId}` + mono id badge; large `StatusBadge`. Meta row:
  triggered by, environment name, started at, duration. Actions: `Re-run`
  (re-triggers same suite+env, RBAC MEMBER+), `Download report` (disabled
  placeholder).
- **Polling:** `useQuery(['runs', id])` with `refetchInterval: (q) =>
  ['PENDING','RUNNING'].includes(q.state.data?.status) ? 2000 : false`. Also
  invalidate/refetch the results query on transition to a terminal state.
- Summary bar: 4 colored stat boxes — Passed / Failed / Skipped / Flaky
  (counts from results).
- **Results** `DataTable`: Test case id (mono), Status (`StatusBadge`), Duration
  (ms), Error message (truncated, mono), Retry count. Failed rows tinted
  (`bg-status-failed/5`). Expandable row → full `errorMessage` in a
  `font-mono text-xs whitespace-pre-wrap` block.
- Right collapsible panel: **Run configuration snapshot** — suite name,
  environment name + URL, triggered-by, created at. (Snapshot test-case list from
  `configSnapshot` is not exposed by the API response; show suite/env/user/time.)
- States: PENDING/RUNNING → results table shows `EmptyState` "Run in progress…"
  with a `Spinner`; terminal + 0 results → `EmptyState` "No results (suite had no
  test cases)".

### Settings (`/settings`) — minimal `features/settings/SettingsPage.tsx`

Phase 1 stub: read-only card showing current user email, org name, role
(`RoleBadge`), and a note that team management / API tokens / SSO arrive in
Phase 4. `Sign out` button.

---

## 8. Auth & RBAC (frontend)

- **Access token in memory only.** Held in a module-level variable inside
  `api/tokenStore.ts` + React context (`features/auth/AuthProvider.tsx`).
  **Never** `localStorage` / `sessionStorage` / cookies written by JS.
- On `POST /auth/login` success: store `accessToken` + `refreshToken` in memory,
  decode the JWT payload (`atob` of the middle segment — no external lib needed,
  or `jwt-decode`) to read `sub` (userId), `org_id`, `roles[0]` (role) and `exp`.
  Email comes from the submitted login form.
- **Refresh:** an Axios response interceptor catches `401`, calls
  `POST /auth/refresh { refreshToken }` once, updates the in-memory tokens, and
  replays the original request. On refresh failure → clear memory, redirect to
  `/login`. A proactive timer may refresh ~1 min before `exp`.
- **Logout:** `POST /auth/logout { refreshToken }` then clear memory + redirect.
- **Protected routes:** `features/auth/RequireAuth.tsx` wraps the `AppLayout`
  route; if no valid access token → `<Navigate to="/login" replace />` preserving
  `location` for post-login return.
- **RBAC-aware controls:** a `useRole()` hook + `<Can role={['OWNER','ADMIN']}>`
  helper toggles create/edit/delete/trigger affordances per the matrix below.
  Hidden or `disabled` — **the backend remains authoritative**; a 403 from the
  API surfaces as an `ErrorState`/toast, never a crash.
- Page refresh loses the in-memory token (expected, per spec) → user returns to
  `/login`. A refresh-token-in-memory bootstrap is out of scope (no persistence).

### RBAC matrix (mirrors `ARCHITECTURE.md` + controller `@PreAuthorize`)

| Action | OWNER | ADMIN | MEMBER | VIEWER |
|---|---|---|---|---|
| View everything | ✅ | ✅ | ✅ | ✅ |
| Create / edit / delete project | ✅ | ✅ | ❌ | ❌ |
| Create / edit / delete environment | ✅ | ✅ | ✅ | ❌ |
| Create / edit / delete suite & case | ✅ | ✅ | ✅ | ❌ |
| Trigger run | ✅ | ✅ | ✅ | ❌ |

---

## 9. API integration conventions

- **Base URL:** `import.meta.env.VITE_API_URL` (default `http://localhost:8090`,
  the Gateway). All calls are relative to it.
- **Envelope:** every success response is `{ data, meta, error: null }`; lists put
  pagination in `meta: { page, pageSize, total }`. A thin Axios response
  interceptor unwraps `data` and attaches `meta`; errors throw an
  `ApiError { code, message, details }` built from `error`.
- **Pagination:** query params `page` (1-indexed) + `size`; default `size=20`.
- **Query keys:** `['projects']`, `['projects', id]`,
  `['projects', id, 'environments']`, `['projects', id, 'suites']`,
  `['suites', id, 'cases']`, `['runs', { projectId, status, page }]`,
  `['runs', id]`, `['runs', id, 'results']`, `['projects', id, 'analytics', days]`.
- **Mutations** invalidate the narrowest relevant keys on success.
- All interactive elements carry `data-testid` for Playwright
  (e.g. `login-email`, `login-submit`, `project-card`, `new-project`,
  `trigger-run`, `run-status`, `result-row`).

---

## 10. Tailwind config mapping

`tailwind.config.js` `theme.extend`:

```js
colors: {
  canvas: '#0F1117',
  surface: { DEFAULT: '#1A1D27', raised: '#22252F' },
  line: { DEFAULT: '#2A2D3A', strong: '#3A3E4C' },
  primary: '#F3F4F6',
  secondary: '#C7CAD1',
  muted: '#9CA3AF',
  subtle: '#6B7280',
  accent: { DEFAULT: '#6366F1', hover: '#7C7FF2', subtle: 'rgba(99,102,241,0.10)' },
  'accent-from': '#6366F1',
  'accent-to': '#8B5CF6',
  status: {
    passed: '#22C55E', failed: '#EF4444', running: '#F59E0B',
    pending: '#6B7280', skipped: '#3B82F6', flaky: '#F59E0B', cancelled: '#6B7280',
  },
  role: { owner: '#8B5CF6', admin: '#3B82F6', member: '#22C55E', viewer: '#6B7280' },
},
borderRadius: { md: '6px', lg: '8px' },
fontFamily: {
  sans: ['Inter', 'Geist', 'system-ui', 'sans-serif'],
  mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'monospace'],
},
backgroundImage: {
  'gradient-accent': 'linear-gradient(135deg, #6366F1, #8B5CF6)',
},
```

`text-primary`, `text-secondary`, `text-muted`, `text-subtle`, `bg-canvas`,
`bg-surface`, `bg-surface-raised`, `border-line`, `bg-status-passed`,
`text-status-failed`, `bg-gradient-accent`, `text-role-owner`, … all derive from
the above. `darkMode` is irrelevant — the palette is dark by default; set
`<html class="dark">` and `color-scheme: dark` globally.
