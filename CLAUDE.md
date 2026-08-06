# Project: QualityOps Lab

An AI-native QA Platform Engineering sandbox that grows into a multi-tenant
SaaS. Teams can onboard applications, manage test environments, orchestrate
test runs, analyze results, detect flaky tests, and get AI-powered failure
analysis — all from a single dashboard.

## ⚠ CURRENT PHASE: 1 — Foundation
**Only implement Phase 1 deliverables unless explicitly told otherwise.**
- There is NO separate Worker app yet. Kafka consumers live inside `apps/api/`.
- Do NOT create `apps/worker/src/` or `WorkerApplication.java`.
- Do NOT implement OAuth/SSO (Phase 4), Stripe (Phase 4B), Terraform (Phase 5),
  or AI agent features (Phase 6).
- See `docs/product/ROADMAP.md` for what Phase 1 includes.
- **When the user says to move to the next phase, update this section.**

## Stack

| Layer | Technology | Notes |
|---|---|---|
| Frontend | React 18 + TypeScript + Vite | TanStack Query, Tailwind CSS |
| Backend API | Java 21 + Spring Boot 3 | Modular monolith, API + Kafka consumers start together |
| Worker | (extracted later) | Kafka consumers split into own app when ready (Phase 2+) |
| Gateway | Spring Cloud Gateway | Routing, rate limiting, auth |
| Database | PostgreSQL 16 | Primary data store |
| Cache | Redis 7 | Sessions, rate limits, run state |
| Messaging | Apache Kafka | Event-driven run orchestration |
| E2E Testing | Playwright | Via MCP + direct runner |
| CI/CD | GitHub Actions | Lint → test → build → deploy |
| Containers | Docker Compose (local) | AKS + Helm later |
| Observability | OpenTelemetry + Prometheus + Grafana | Traces, metrics, logs |

## Project layout

```
.
├── CLAUDE.md                         # this file — always in context
├── ARCHITECTURE.md                   # system design, decisions, diagrams
├── .mcp.json                         # MCP server config (Playwright, etc.)
├── .gitignore
│
├── apps/
│   ├── web/                          # React frontend
│   │   ├── package.json
│   │   ├── tsconfig.json
│   │   ├── vite.config.ts
│   │   ├── src/
│   │   │   ├── main.tsx
│   │   │   ├── App.tsx
│   │   │   ├── api/                  # API client layer (TanStack Query)
│   │   │   ├── components/           # shared UI components
│   │   │   ├── features/             # feature modules (projects, runs, etc.)
│   │   │   ├── hooks/                # custom React hooks
│   │   │   ├── layouts/              # page layouts
│   │   │   ├── pages/                # route pages
│   │   │   └── types/                # shared TypeScript types
│   │   └── tests/                    # Vitest unit + component tests
│   │
│   ├── api/                          # Spring Boot main backend
│   │   ├── pom.xml
│   │   └── src/
│   │       ├── main/java/com/qualityops/api/
│   │       │   ├── QualityOpsApplication.java
│   │       │   ├── config/           # Spring config, security, Kafka
│   │       │   ├── identity/         # auth, users, roles, tenants
│   │       │   ├── project/          # projects, workspaces
│   │       │   ├── environment/      # environment registry
│   │       │   ├── testsuite/        # test catalog: suites, cases, tags
│   │       │   ├── execution/        # run orchestration + Kafka consumers (split later)
│   │       │   ├── result/           # results, analytics, flakiness
│   │       │   ├── testdata/         # test data management
│   │       │   ├── mock/             # dependency virtualization
│   │       │   └── ai/              # AI assistant integration
│   │       └── test/                 # JUnit 5 + Testcontainers
│   │
│   ├── worker/                       # async job runner (EMPTY until extracted)
│   │   └── README.md                # will hold Kafka consumers after split from api/
│   │
│   ├── ai-agent/                     # Python AI service (Phase 6)
│   │   ├── pyproject.toml
│   │   └── app/
│   │       ├── main.py               # FastAPI entry point
│   │       ├── agents/               # LangChain tool-use agents
│   │       ├── chains/               # RAG + analysis chains
│   │       ├── tools/                # agent tools (API, Git, Playwright)
│   │       ├── embeddings/           # vector store + indexing
│   │       └── prompts/              # prompt templates
│   │
│   └── gateway/                      # API gateway / reverse proxy
│       ├── pom.xml
│       └── src/main/java/com/qualityops/gateway/
│           ├── GatewayApplication.java
│           ├── config/               # routes, filters, rate limiting
│           └── filter/               # custom gateway filters
│
├── packages/
│   └── shared-types/                 # shared DTOs / API contracts
│       └── README.md
│
├── infra/
│   ├── docker/
│   │   ├── Dockerfile.api
│   │   ├── Dockerfile.worker
│   │   ├── Dockerfile.gateway
│   │   └── Dockerfile.web
│   ├── compose/
│   │   ├── docker-compose.yml        # full local stack
│   │   └── docker-compose.dev.yml    # dev overrides
│   ├── terraform/                    # IaC: Azure resources (Phase 5)
│   │   ├── modules/                  # reusable: aks, database, redis, etc.
│   │   └── environments/             # staging/ and production/ configs
│   ├── k8s/                          # raw manifests (learning)
│   ├── helm/                         # Helm charts (production)
│   └── scripts/
│       ├── init-db.sql
│       └── seed-data.sql
│
├── docs/
│   ├── product/
│   │   ├── MVP.md                    # MVP scope and acceptance criteria
│   │   └── ROADMAP.md                # phase plan: lab → platform → SaaS
│   ├── architecture/
│   │   └── decisions/                # ADRs (Architecture Decision Records)
│   │       └── 001-template.md
│   └── runbooks/
│       └── local-dev-setup.md
│
├── .github/
│   ├── workflows/
│   │   ├── ci.yml                    # lint + test + build
│   │   └── deploy.yml                # deploy to Azure (later)
│   ├── PULL_REQUEST_TEMPLATE.md
│   └── CODEOWNERS
│
└── .claude/
    ├── settings.json                 # permissions, hooks, env
    ├── rules/                        # auto-loaded guardrails (path-scoped)
    │   ├── general.md                # always loaded — universal rules
    │   ├── java-backend.md           # loaded when editing *.java
    │   ├── react-frontend.md         # loaded when editing *.ts, *.tsx
    │   ├── database-migrations.md    # loaded when editing *.sql migrations
    │   ├── docker-infra.md           # loaded when editing Dockerfiles, k8s, helm
    │   ├── ci-cd.md                  # loaded when editing .github/workflows
    │   ├── tests.md                  # loaded when editing test files
    │   ├── security.md               # loaded when editing auth/security code
    │   ├── kafka-events.md           # loaded when editing events/consumers
    │   ├── api-design.md             # loaded when editing controllers/API client
    │   └── terraform-iac.md          # loaded when editing infra/terraform/**
    ├── agents/
    │   ├── planner.md                # designs implementation plans
    │   ├── implementer.md            # writes code from plans
    │   ├── reviewer.md               # reviews code quality
    │   ├── debugger.md               # diagnoses bugs
    │   ├── architect.md              # system design decisions
    │   └── devops.md                 # CI/CD and infra concerns
    └── skills/
        ├── java-spring/
        │   └── SKILL.md              # Spring Boot patterns, modules
        ├── react-typescript/
        │   └── SKILL.md              # React + TS conventions
        ├── kafka-redis/
        │   └── SKILL.md              # event-driven + caching patterns
        ├── docker-k8s/
        │   └── SKILL.md              # containers + orchestration
        ├── api-testing/
        │   └── SKILL.md              # API + E2E test automation
        ├── system-design/
        │   └── SKILL.md              # architecture patterns, ADRs
        ├── security/
        │   └── SKILL.md              # OAuth, SSO, JWT, TLS, rate limiting, OWASP
        ├── ai-engineering/
        │   └── SKILL.md              # RAG, LangChain, agents, embeddings, vector DB
        ├── infrastructure-as-code/
        │   └── SKILL.md              # Terraform, Azure, IaC, remote state, modules
        ├── ci-cd/
        │   └── SKILL.md              # GitHub Actions, pipelines
        ├── testing/
        │   └── SKILL.md              # JUnit, Vitest, Playwright, Testcontainers
        ├── code-review/
        │   └── SKILL.md              # review guide for Java + React
        └── git-workflow/
            └── SKILL.md              # branching, commits, PRs
```

## How to run (local development)

```bash
# Prerequisites: Java 21, Node 20+, Docker Desktop

# Start infrastructure (Postgres, Redis, Kafka)
docker compose -f infra/compose/docker-compose.yml up -d

# Start backend API
cd apps/api && ./mvnw spring-boot:run

# Start worker
cd apps/worker && ./mvnw spring-boot:run

# Start gateway
cd apps/gateway && ./mvnw spring-boot:run

# Start frontend
cd apps/web && npm install && npm run dev
```

## Coding standards (apply to ALL code in this repo)

### Java (backend, worker, gateway)
- **Java 21** features: records, sealed interfaces, pattern matching, virtual threads.
- **Type safety first** — no raw types, no `Object` where a generic fits.
- **Constructor injection** only — never field injection with `@Autowired`.
- **Records for DTOs** — mutable classes only when state genuinely changes.
- **Small methods** — if a method exceeds ~30 lines, split it.
- **No `@SuppressWarnings`** without a comment explaining why.
- **Narrow exceptions** — catch the most specific exception. Never `catch (Exception e)` in business logic.
- **Logging** — use SLF4J. No `System.out.println` in production code.

### TypeScript / React (frontend)
- **Strict TypeScript** — `strict: true` in tsconfig, no `any` without justification.
- **Functional components only** — no class components.
- **Named exports** — avoid default exports (better refactoring support).
- **TanStack Query** for all server state — no manual `useEffect` + `fetch`.
- **Tailwind CSS** for styling — no CSS modules or styled-components.
- **Small components** — if a component file exceeds ~100 lines, split it.
- **Custom hooks** for reusable logic — extract early, not late.

### General (all code)
- **No secrets in code** — use environment variables, never hardcode credentials.
- **Tests alongside code** — every feature ships with tests.
- **Imports ordered** — standard lib → framework → third-party → local.
- **No dead code** — delete it, don't comment it out.
- **No premature optimization** — make it correct, then make it fast.

## Domain rules (NON-NEGOTIABLE)

1. **Multi-tenancy aware from day one.** Every entity belongs to an org/project.
   Even in single-tenant mode, include `tenant_id` / `project_id` on all tables.
2. **Test runs are immutable.** Once a run starts, its configuration is snapshotted.
   Editing a test suite does not retroactively change historical runs.
3. **Kafka events are the source of truth for execution flow.** The API publishes
   "run requested" events; workers consume them. The API does not directly invoke
   the worker.
4. **Every API endpoint is authenticated and authorized.** No public endpoints
   except health checks and login.
5. **Database migrations are versioned.** Use Flyway. Never modify a migration
   that has already been applied.
6. **Hexagonal architecture for complex modules.** Business logic depends on
   interfaces (ports), not on frameworks. Adapters implement ports. Dependency
   direction is always inward: adapters → application → domain.
7. **Security is not optional.** JWT auth from Phase 1. RBAC enforced. OWASP
   Top 10 checklist on every review. Secrets never in code. TLS in production.
8. **Rate limiting on all public APIs.** Gateway-level per-client limits via
   Redis. Application-level per-operation limits for expensive operations
   (run triggers, AI requests).
9. **Event-driven, not request-driven, for execution.** Services publish facts
   (events); other services react. No synchronous orchestration between API
   and Worker.
10. **API design is RESTful and versioned.** All endpoints under `/api/v1/`.
11. **Never handle raw card data.** All payment flows go through Stripe Checkout
    or Stripe Customer Portal. Stripe is the source of truth for billing state;
    our DB stores a synced copy via webhooks. Verify webhook signatures always.
    Consistent envelope format. Standard HTTP status codes. OpenAPI documented.

## How to work with Claude in this repo

### Subagent workflow
- For **non-trivial changes** (new feature, new service, architecture change):
  invoke the **planner** first, then the **implementer**, then the **reviewer**.
- For **system design decisions** (new module, technology choice, API design):
  invoke the **architect** subagent before planning.
- For **infrastructure changes** (Docker, CI/CD, Kubernetes, cloud):
  invoke the **devops** subagent.
- When **something is broken** and the cause is unclear:
  invoke the **debugger** for root-cause analysis.
- For **trivial edits** (typo, rename, one-line fix): just edit directly.

### Skills — when to load which
- Writing or editing **Spring Boot** code → load **java-spring** skill.
- Writing or editing **React / TypeScript** code → load **react-typescript** skill.
- Working with **Kafka or Redis** → load **kafka-redis** skill.
- Working with **Docker, Kubernetes, or Helm** → load **docker-k8s** skill.
- Writing or editing **tests** (any layer) → load **testing** skill.
- Writing or editing **API or E2E tests** → load **api-testing** skill.
- Working on **CI/CD pipelines** → load **ci-cd** skill.
- Making **architecture decisions** → load **system-design** skill.
- Working on **auth, security, OAuth, TLS, rate limiting** → load **security** skill.
- Building the **AI agent, RAG, LangChain, embeddings** → load **ai-engineering** skill.
- Working on **Terraform, Azure provisioning, IaC** → load **infrastructure-as-code** skill.
- **Reviewing code** → load **code-review** skill.
- Making **commits or PRs** → follow **git-workflow** skill.

### Architecture
- Read `ARCHITECTURE.md` before making structural changes. Update it after.
- For significant decisions, create an ADR in `docs/architecture/decisions/`.
- When adding a new module to the API, follow the existing module structure
  (controller → service → repository → DTOs → events).

### MCP integrations

MCP servers are configured in `.mcp.json` at the project root. Claude Code
reads this file automatically and connects to the listed servers on startup.

**Configured now (in `.mcp.json`):**
- **Playwright MCP** (`@playwright/mcp`) — Browser automation for E2E testing.
  Uses Playwright's accessibility tree (not screenshots) for fast, deterministic
  browser interaction. Claude can navigate pages, click, fill forms, take
  screenshots, and run test scenarios.
- **Browser MCP** (`cursor-ide-browser`) — Cursor's built-in browser for live
  testing and visual verification (configured in Cursor, not `.mcp.json`).

**Add later (when ready):**

To add a new MCP server, either edit `.mcp.json` directly or use the CLI:
```bash
claude mcp add --scope project --transport stdio <name> -- <command> [args...]
```

- **Figma MCP** — Pull design tokens, component specs, and layouts from Figma
  into React code. Add when frontend design work begins.
  ```json
  "figma": {
    "command": "cmd",
    "args": ["/c", "npx", "-y", "figma-developer-mcp"],
    "env": { "FIGMA_API_KEY": "${FIGMA_API_KEY}" }
  }
  ```
  Requires: Figma personal access token → set as `FIGMA_API_KEY` environment
  variable (never hardcode in `.mcp.json`).

- **Google Stitch** — UI design canvas; export **DESIGN.md** (design tokens +
  rationale) for `apps/web/`. Install skills when doing frontend lab work:
  ```bash
  npx skills add google-labs-code/stitch-skills --global
  ```
  Spec: https://github.com/google-labs-code/design.md — keep `apps/web/DESIGN.md`
  in sync with Tailwind theme. See ROADMAP Phase 7 “UI design with Google Stitch”.

- **GitHub MCP** — PR management, issue tracking, CI status checks.
  ```bash
  claude mcp add --transport http github https://mcp.github.com
  ```

- **PostgreSQL MCP** — Direct database queries from Claude for debugging.
  Add when database is running.
  ```json
  "postgres": {
    "command": "cmd",
    "args": ["/c", "npx", "-y", "@modelcontextprotocol/server-postgres"],
    "env": { "DATABASE_URL": "${DATABASE_URL}" }
  }
  ```

## Development phases

| Phase | Focus | What ships |
|---|---|---|
| 1 — Foundation | Project skeleton, local dev, basic CRUD | API + DB + React shell |
| 2 — Core Platform | Test catalog, run orchestration, results | Kafka + worker + dashboard |
| 3 — Intelligence | Flaky detection, AI failure analysis | Analytics + AI integration |
| 4 — SaaS Ready | Multi-tenancy, auth, onboarding | SSO (OAuth/OIDC) + 2FA (email/SMS/TOTP) |
| 4B — Payments | Stripe, subscriptions, billing | Checkout, webhooks, plan enforcement |
| 5 — Cloud Native | AKS deployment, observability | Helm + Terraform + monitoring |
| 6 — AI Agent | RAG, LangChain, vector DB, tool-use agents | Python AI service + agent UI |
| 7 — Playground | Lab: patterns, k6 load tests, Stitch DESIGN.md | See ROADMAP Phase 7 |

See `docs/product/ROADMAP.md` for detailed phase plans.
