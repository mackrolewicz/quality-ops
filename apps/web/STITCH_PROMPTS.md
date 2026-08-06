# Google Stitch UI Prompts — QualityOps Lab

Use these prompts at https://stitch.withgoogle.com to generate UI designs.
After designing, export DESIGN.md and save it to `apps/web/DESIGN.md`.
Then tell Claude Code: "DESIGN.md is ready. Implement the React frontend shell."

## Design brief (give this first in Stitch for consistent style)

```
Design system for QualityOps — a B2B QA SaaS platform for engineering teams.

Style:
- Dark theme: background #0F1117, card surface #1A1D27, border #2A2D3A
- Accent: blue-purple gradient (#6366F1 to #8B5CF6)
- Font: Inter or Geist, clean and modern
- Tailwind CSS utility-class compatible
- Data-dense but not cluttered
- Status colors: green=#22C55E (PASSED), red=#EF4444 (FAILED),
  amber=#F59E0B (RUNNING), grey=#6B7280 (PENDING), blue=#3B82F6 (SKIPPED)
- Border radius: rounded-lg (8px) for cards, rounded-md for buttons
- No decorative illustrations — professional SaaS aesthetic
```

---

## Phase 1 screens (build these first)

### 1. Login page

```
Design a login page for a B2B SaaS platform called QualityOps.
Professional dark theme with subtle gradients. Clean, minimal.

Elements:
- Logo top center: "QualityOps" with a small checkmark/shield icon
- Card centered on page
- Email and password fields
- "Sign in" primary button (full width, solid blue-purple)
- "Forgot password?" link below button
- Divider: "or continue with"
- GitHub and Google SSO buttons (outline style)
- Footer: "Don't have an account? Contact your admin"

Style: Dark background (#0F1117), card slightly lighter (#1A1D27),
accent color blue-purple gradient, Tailwind-compatible spacing,
Inter or Geist font. No illustrations.
```

### 2. Dashboard (main home after login)

```
Design a dashboard home page for QualityOps, a QA platform for engineering teams.

Layout: Left sidebar navigation (240px) + main content area.

Sidebar:
- Logo + "QualityOps" top left
- Nav items with icons: Dashboard, Projects, Test Suites, Runs, Results, Settings
- User avatar + org name at bottom with chevron for org switcher

Top bar:
- Page title "Dashboard"
- Notification bell icon
- User avatar menu (top right)

Main content — 4 stat cards in a row:
- Total Runs (last 7 days) — number + trend arrow
- Pass Rate % — percentage + colored indicator
- Failed Tests — number in red
- Flaky Tests — number in amber

Below cards — two columns:
- Left (60%): "Recent Runs" table — run name, project, status badge, triggered by, time ago
- Right (40%): "Pass rate trend" line chart (last 14 days, green line)

Bottom row: "Active environments" — 3 cards with name, URL, status dot (green/amber/red)

Style: Dark theme, Tailwind CSS, clean and data-dense.
Status badges: green=PASSED, red=FAILED, amber=RUNNING, grey=PENDING.
```

### 3. Projects list

```
Design a projects list page for QualityOps QA platform.
Same sidebar as dashboard.

Page header:
- Title "Projects"
- Search input with icon (left, takes 300px)
- "+ New Project" button (right, primary gradient)

Content: Grid of project cards (3 columns, responsive to 2 then 1).
Each card:
- Project name (bold, large)
- Description (2 lines, muted text)
- Row of 3 stats: test suite count, last run status badge, last run time ago
- Subtle "View project →" link bottom right

Empty state: centered icon + "No projects yet" + "Create your first project" button

Style: Card hover lifts slightly, consistent dark theme.
```

### 4. Project detail

```
Design a project detail page for QualityOps.
Same sidebar. Breadcrumb: Projects > checkout-service

Header:
- Project name (h1) + description
- Edit button (top right)
- 3 summary stats: test suites, total runs, last run status

Two tabs: "Test Suites" | "Recent Runs"

Test Suites tab (active):
Table — Suite name | Type (API/UI/PERFORMANCE) | Cases count | Last run | Status | Actions
"+ Add Suite" button above table

Recent Runs tab:
Table — Run ID | Suite | Environment | Status badge | Triggered by | Duration | Started at
"Trigger new run" button above table

Style: Tab underline active state, consistent dark theme.
```

---

## Later phases (design when backend is ready)

### 5. Test run detail / results (Phase 1 — results session)

```
Design a test run results page for QualityOps.

Header:
- Run name and ID badge
- Status badge (large): COMPLETED / FAILED / RUNNING / PENDING
- Meta row: triggered by, environment name, started at, duration
- Action buttons: "Re-run", "Download report"

Summary bar (4 colored stat boxes):
- Passed (green number), Failed (red), Skipped (grey), Flaky (amber)

Main table: test case results
Columns: Test name | Status badge | Duration | Error message (truncated) | Retry count
Red tint on FAILED rows
Expandable row → full error + stack trace in monospace

Right collapsible panel: run configuration snapshot
(suite name, environment URL, tags, triggered by user)

Style: Dark theme, monospace for error text, status consistent with dashboard.
```

### 6. Billing page (Phase 4B)

```
Design a billing and subscription page for QualityOps SaaS.
Settings section, same sidebar.

Current plan card:
- Plan name with badge (Free / Pro / Enterprise)
- Usage bar: "847 / 1,000 runs used this month"
- Billing period and next renewal date
- "Upgrade plan" primary button

Plan comparison (3 cards in a row):
- Free: $0/mo — 100 runs/month, 1 project, basic analytics
- Pro: $49/mo — 10,000 runs/month, unlimited projects, flaky detection, priority support
- Enterprise: Custom pricing — unlimited, SSO, SLA, dedicated support
Pro card has gradient border to stand out. Checkmark feature lists.

Invoice history table:
Date | Plan | Amount | Status (Paid badge / Failed badge) | Download PDF icon

Style: Dark theme, Pro card highlighted. Payment badges: green=Paid, red=Failed.
```

### 7. Team / members (Phase 4)

```
Design a team settings page for QualityOps.
Settings section. Tabs: General | Members | API Tokens | Security

Active tab: Members

"Team Members" section header + "Invite member" button (top right)

Members table:
Avatar | Full name | Email | Role dropdown (Owner/Admin/Member/Viewer) | Joined date | Remove icon

Pending invitations section below:
Email | Role | Invited by | "Resend" link | "Cancel" link

2FA policy section:
Toggle: "Require two-factor authentication for all members"
Description: "Members who haven't enrolled will be prompted on next login."

Role badge colors: Owner=purple, Admin=blue, Member=green, Viewer=grey.
Style: Dark theme, consistent with rest of app.
```

---

## How to use

1. Open https://stitch.withgoogle.com
2. Paste the design brief first (for consistent style across all screens)
3. Then paste one screen prompt at a time
4. Iterate: "make it darker", "change accent to teal", "add mobile breakpoint"
5. Export DESIGN.md → save to `apps/web/DESIGN.md`
6. Tell Claude Code:
   ```
   DESIGN.md is ready at apps/web/DESIGN.md.
   Implement Phase 1 React frontend: Vite + React 18 + Tailwind + TanStack Query.
   Build: routing, layout shell, login page, dashboard, projects list.
   Read DESIGN.md for color tokens and component styles.
   ```
