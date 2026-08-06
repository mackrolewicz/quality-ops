# Architecture

This document describes the system design of QualityOps Lab, the key
decisions, and the reasoning behind them. Keep it updated as the project evolves.

## System overview

```
                          ┌─────────────┐
                          │   Browser    │
                          └──────┬──────┘
                                 │
                          ┌──────▼──────┐
                          │   React     │
                          │   Frontend  │
                          │  (Vite/TS)  │
                          └──────┬──────┘
                                 │ HTTP/WS
                          ┌──────▼──────┐
                          │   Spring    │
                          │   Cloud     │
                          │   Gateway   │
                          └──────┬──────┘
                                 │ routes to
              ┌──────────────────┼──────────────────┐
              │                  │                   │
       ┌──────▼──────┐   ┌──────▼──────┐    ┌──────▼──────┐
       │  API Server  │   │  API Server  │    │   Static    │
       │  (Spring     │   │  (replica)   │    │   Assets    │
       │   Boot)      │   │              │    │             │
       └──────┬──────┘   └──────┬──────┘    └─────────────┘
              │                  │
              ├──────────────────┘
              │
    ┌─────────┼──────────┬──────────────┐
    │         │          │              │
┌───▼───┐ ┌──▼───┐ ┌───▼────┐  ┌─────▼─────┐
│Postgres│ │Redis │ │ Kafka  │  │ Worker(s) │
│  (DB)  │ │(cache│ │(events)│  │ (Kafka    │
│        │ │ +pub) │ │        │  │  consumer)│
└────────┘ └──────┘ └───┬────┘  └─────┬─────┘
                         │             │
                         └──────┬──────┘
                                │ consumes events
                         ┌──────▼──────┐
                         │  Test       │
                         │  Runners    │
                         │  (Playwright│
                         │   /API/Perf)│
                         └─────────────┘
```

## Architectural style: Hexagonal (Ports and Adapters)

The backend follows **hexagonal architecture** (ports and adapters). Business
logic lives at the center and has no dependency on frameworks, databases, or
messaging. External concerns plug in through interfaces (ports) and their
implementations (adapters).

```
                    ┌─────────────────────────────────┐
                    │         Driving Adapters         │
                    │  (things that call our code)     │
                    │                                  │
                    │  REST Controllers                │
                    │  Kafka Consumers                 │
                    │  Scheduled Jobs                  │
                    │  CLI / Test Harness              │
                    └──────────┬──────────────────────┘
                               │ calls
                    ┌──────────▼──────────────────────┐
                    │       Input Ports                │
                    │  (use case interfaces)           │
                    │                                  │
                    │  TriggerRunUseCase               │
                    │  CreateProjectUseCase            │
                    │  GetResultsUseCase               │
                    └──────────┬──────────────────────┘
                               │ implements
                    ┌──────────▼──────────────────────┐
                    │     Domain / Business Logic      │
                    │  (pure Java, no framework deps)  │
                    │                                  │
                    │  Domain entities                 │
                    │  Domain services                 │
                    │  Domain events                   │
                    │  Validation rules                │
                    └──────────┬──────────────────────┘
                               │ depends on
                    ┌──────────▼──────────────────────┐
                    │       Output Ports               │
                    │  (repository / gateway intf.)    │
                    │                                  │
                    │  ProjectRepository (interface)   │
                    │  EventPublisher (interface)      │
                    │  RunStatusCache (interface)      │
                    │  NotificationGateway (interface) │
                    └──────────┬──────────────────────┘
                               │ implemented by
                    ┌──────────▼──────────────────────┐
                    │       Driven Adapters            │
                    │  (infrastructure implementations)│
                    │                                  │
                    │  JPA Repository (Postgres)       │
                    │  Kafka Producer                  │
                    │  Redis Cache                     │
                    │  REST Client (external APIs)     │
                    │  SMTP (email notifications)      │
                    └─────────────────────────────────┘
```

### Why hexagonal?

- **Testability** — Business logic can be tested without Spring, Kafka, or Postgres.
  Inject mock adapters and test pure domain behavior.
- **Flexibility** — Swap Postgres for DynamoDB, Kafka for RabbitMQ, or Redis for
  Memcached without touching business logic.
- **Clarity** — Forces you to think about what is domain logic vs. what is
  infrastructure glue. Controllers and repositories are thin adapters.
- **Learning** — This is a lab project. Hexagonal architecture is one of the
  most interview-relevant patterns for senior engineers.

### Practical application (not academic purity)

We're pragmatic, not dogmatic:
- **Start simple.** In Phase 1, services can call repositories directly.
  Extract ports/adapters when a module is complex enough to justify it.
- **Spring is allowed.** The domain layer can use Spring annotations like
  `@Service` and `@Transactional`. Pure hexagonal says no framework in the
  domain, but for a lab project the overhead of a pure approach isn't worth it.
- **Extract when it hurts.** If you find yourself mocking 5 things to test
  one service, that's a sign to extract a port.

## Domain modules

The API is a **modular monolith** — each domain is a separate Java package
with clear boundaries. Modules communicate through internal service calls
now, and can be extracted to microservices later if needed.

```
com.qualityops.api
├── identity/        ← auth, users, roles, orgs, API tokens
├── project/         ← projects, workspaces
├── environment/     ← environment registry, health tracking
├── testsuite/       ← test catalog: suites, cases, tags, ownership
├── execution/       ← run orchestration, scheduling, retry logic
├── result/          ← results, analytics, flakiness scoring
├── testdata/        ← test data management, seed sets, generators
├── mock/            ← dependency virtualization, response replay
├── ai/              ← AI assistant: failure analysis, test generation
└── config/          ← Spring config, security, Kafka, Redis, Flyway
```

### Module structure — hexagonal layout

Each module follows the ports-and-adapters layout:

```
execution/
├── adapter/
│   ├── in/
│   │   ├── web/
│   │   │   └── RunController.java          # REST adapter (driving)
│   │   └── messaging/
│   │       └── RunCompletedConsumer.java    # Kafka adapter (driving)
│   └── out/
│       ├── persistence/
│       │   ├── RunJpaRepository.java        # JPA adapter (driven)
│       │   └── RunEntity.java               # JPA entity (infra, not domain)
│       ├── messaging/
│       │   └── RunKafkaPublisher.java       # Kafka adapter (driven)
│       └── cache/
│           └── RunRedisCache.java           # Redis adapter (driven)
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   └── TriggerRunUseCase.java       # input port (interface)
│   │   └── out/
│   │       ├── RunRepository.java           # output port (interface)
│   │       ├── RunEventPublisher.java       # output port (interface)
│   │       └── RunStatusCache.java          # output port (interface)
│   └── service/
│       └── RunService.java                  # implements use cases
├── domain/
│   ├── TestRun.java                         # domain entity (pure Java)
│   ├── RunStatus.java                       # value object / enum
│   └── RunPolicy.java                       # domain rules
├── dto/
│   ├── CreateRunRequest.java                # API request record
│   └── RunResponse.java                     # API response record
└── exception/
    └── RunNotFoundException.java
```

**Simplified structure for smaller modules** (Phase 1):

Not every module needs the full hexagonal layout. For simple CRUD modules
(project, environment), a simpler structure is fine:

```
project/
├── ProjectController.java      # REST endpoints
├── ProjectService.java         # business logic
├── ProjectRepository.java      # Spring Data JPA interface
├── dto/
│   ├── CreateProjectRequest.java
│   └── ProjectResponse.java
├── model/
│   └── Project.java            # JPA entity
└── exception/
    └── ProjectNotFoundException.java
```

**Rule:** Start simple. Upgrade to full hexagonal when the module has
multiple adapters (Kafka + REST + cache) or complex domain logic.

## Data model (core entities)

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│ Organization │────<│   Project    │────<│ Environment  │
│              │     │              │     │              │
│ id           │     │ id           │     │ id           │
│ name         │     │ org_id (FK)  │     │ project_id   │
│ slug         │     │ name         │     │ name         │
│ created_at   │     │ description  │     │ url          │
└──────┬───────┘     └──────┬───────┘     │ status       │
       │                    │              │ version      │
       │             ┌──────▼───────┐     └──────────────┘
       │             │  TestSuite   │
       │             │              │
       │             │ id           │
       │             │ project_id   │
       │             │ name         │
       │             │ type (API/   │
       │             │   UI/PERF)   │
       │             │ tags[]       │
       │             └──────┬───────┘
       │                    │
┌──────▼───────┐     ┌──────▼───────┐     ┌──────────────┐
│    User      │     │  TestCase    │     │   TestRun    │
│              │     │              │     │              │
│ id           │     │ id           │     │ id           │
│ org_id (FK)  │     │ suite_id     │     │ project_id   │
│ email        │     │ name         │     │ suite_id     │
│ role         │     │ description  │     │ env_id       │
│ api_token    │     │ priority     │     │ status       │
└──────────────┘     │ automated    │     │ triggered_by │
                     └──────────────┘     │ started_at   │
                                          │ finished_at  │
                                          └──────┬───────┘
                                                 │
                                          ┌──────▼───────┐
                                          │ TestResult   │
                                          │              │
                                          │ id           │
                                          │ run_id       │
                                          │ case_id      │
                                          │ status       │
                                          │ duration_ms  │
                                          │ error_msg    │
                                          │ screenshot   │
                                          │ retry_count  │
                                          │ flaky_score  │
                                          └──────────────┘

┌──────────────┐     ┌──────────────┐
│ Subscription │     │   Invoice    │
│              │     │              │
│ id           │     │ id           │
│ org_id (FK)  │     │ sub_id (FK)  │
│ stripe_cust  │     │ stripe_inv   │
│ stripe_sub   │     │ amount       │
│ plan (FREE/  │     │ currency     │
│  PRO/ENTERP) │     │ status       │
│ status       │     │ period_start │
│ current_per  │     │ period_end   │
│ runs_used    │     │ pdf_url      │
│ runs_limit   │     │ created_at   │
└──────────────┘     └──────────────┘
```

## Key design decisions

### 1. Modular monolith over microservices

**Decision:** Start as a single Spring Boot application with well-defined
module boundaries. Each domain (identity, testsuite, execution, etc.) is
a separate package with its own controller, service, repository, and DTOs.

**Why:** Microservices add operational complexity (service discovery, distributed
tracing, network failures) without proportional benefit at this scale. Module
boundaries give us the same logical separation with simpler deployment.

**Progression:**
```
Phase 1-2: One Spring Boot app (API + Kafka consumers together)
           └── com.qualityops.api
               ├── project/        ← REST controllers
               ├── testsuite/      ← REST controllers
               ├── execution/      ← REST controllers + Kafka consumers (same app)
               └── config/         ← Kafka config, security, etc.

Phase 2+:  Split into two apps when you feel the pain
           ├── apps/api/          ← REST controllers only
           └── apps/worker/       ← Kafka consumers only (extracted)
```

**When to split:** When you notice that long-running test execution blocks
API responsiveness, or when you want to scale consumers independently, or
simply when you're ready to learn the extraction process. The split is
mechanical — move `@KafkaListener` classes to a new Spring Boot app, point
them at the same Kafka and database.

**Revisit when:** A module needs independent scaling (e.g., the worker needs
10x instances while the API stays at 2), or teams form around specific modules.

### 2. Kafka for execution orchestration

**Decision:** Test runs flow through Kafka events, not direct API-to-worker calls.
Even in Phase 1, Kafka consumers live inside the API app — the separation
is logical (different packages), not physical (different apps) yet.

**Why:**
- Decouples the API from the worker — even in the same app, they communicate via events, not method calls.
- Natural retry semantics with dead-letter topics.
- Event history for auditing and replay.
- Prepares for future event-sourcing of execution state.
- When you split the worker out later, **zero code changes** to producers — they already publish to Kafka, not call methods directly.

**Event flow (target, Phase 2+ with real execution):**
```
API publishes → runs.requested
Worker consumes → starts execution
Worker publishes → run.started
Worker publishes → result.chunk (per test case)
Worker publishes → runs.completed | run.failed
API consumes → updates DB, notifies frontend via WebSocket
```

**As implemented in Phase 1 (simplified — no real execution yet):** the
`execution` module's in-process consumer listens on `runs.requested`,
flips `PENDING → RUNNING` via an idempotent conditional UPDATE, sleeps
briefly to simulate work, resolves `PASSED`/`FAILED` at random, and
publishes `runs.completed`. The `result` module's consumer listens on
`runs.completed` and generates one `TestResult` row per test case in the
suite in a single batch (no `result.chunk` streaming yet — that requires
real per-case execution). Both consumers use an idempotency check
(existence/status check before writing) plus a DB unique constraint as a
second line of defense against Kafka's at-least-once delivery.

### 3. Redis for ephemeral state

**Decision:** Use Redis for session cache, rate limiting, real-time run status,
and WebSocket pub/sub. NOT as a primary data store.

**Why:** Run status changes frequently (every few seconds during execution).
Hitting Postgres for each status update is wasteful. Redis provides sub-ms reads
for the dashboard and natural TTL-based expiry for sessions.

**What goes in Redis:**
- Current run status + progress percentage
- Rate limit counters per API token
- Session/auth cache
- Dashboard widget caches (TTL: 30s)

**What stays in Postgres:**
- Everything else. Redis is ephemeral; if it dies, the app recovers.

### 4. React + TanStack Query for frontend

**Decision:** React 18, TypeScript strict mode, Vite, TanStack Query, Tailwind CSS.

**Why:**
- TanStack Query eliminates the manual `useEffect` + loading state boilerplate
  and gives us caching, polling, and background refresh for free.
- Vite is the fastest dev server for React.
- Tailwind avoids CSS architecture debates and keeps styling co-located.
- TypeScript strict mode catches bugs before they reach the backend.

### 5. Spring Cloud Gateway as the entry point

**Decision:** All frontend requests go through Spring Cloud Gateway, which
routes to the API, worker health endpoints, and static assets.

**Why:**
- Single entry point simplifies CORS, auth, and rate limiting.
- Gateway can add request tracing headers (OpenTelemetry).
- Easy to add canary routing, A/B testing later.
- Learning opportunity for proxy/gateway patterns.

### 6. Flyway for database migrations

**Decision:** All schema changes go through Flyway versioned migrations.
Never modify a migration that has been applied.

**Why:** Reproducible schema across local, staging, and production.
Version-controlled migrations are auditable and rollback-friendly.

### 7. Testcontainers for integration tests

**Decision:** Integration tests use Testcontainers to spin up real Postgres,
Redis, and Kafka instances in Docker containers.

**Why:** Mocking databases leads to false confidence. Real containers catch
SQL syntax issues, constraint violations, and Kafka serialization problems
that mocks would miss.

### 8. Hexagonal architecture for the API

**Decision:** Modules follow hexagonal (ports-and-adapters) architecture.
Business logic depends on interfaces (ports), not on frameworks or infrastructure.

**Why:**
- Testability: domain logic is unit-testable without Spring context.
- Swappability: can replace Postgres with another DB, Kafka with RabbitMQ,
  without touching business logic.
- Clean dependencies: code depends inward (adapters → ports → domain),
  never outward.
- Interview-relevant: one of the most discussed patterns in system design.

**Practical rule:** Start simple (controller → service → repo). Extract ports
and adapters when a module has multiple infrastructure concerns (Kafka +
Redis + JPA) or complex domain logic.

### 9. Event-driven architecture for execution

**Decision:** The execution flow is fully event-driven. The API never calls
the worker directly. All communication goes through Kafka events.

**Why:**
- Loose coupling: API and Worker deploy independently.
- Scalability: add more Worker instances without API changes.
- Resilience: if the Worker is down, events queue in Kafka.
- Auditability: event log is a natural audit trail.
- Replayability: can re-process events for debugging or recovery.

**Event choreography (not orchestration):**

Services react to events autonomously. There is no central orchestrator
telling each service what to do. Each service publishes facts about what
happened, and other services decide how to react.

```
run.requested → Worker starts execution
run.started   → API updates status, notifies frontend
result.chunk  → API persists result, updates progress
run.completed → API triggers analytics, AI analysis
run.failed    → API triggers failure notification
```

### 10. Rate limiting at gateway and application level

**Decision:** Two-tier rate limiting — gateway-level per-client limits and
application-level per-operation limits.

**Why:**
- Gateway-level prevents abuse before requests hit the API (DDoS, scraping).
- Application-level prevents expensive operations (test runs, AI calls) from
  exhausting shared resources, even from legitimate users.
- Redis-backed counters are fast and consistent across API replicas.

### 11. Security-first design

**Decision:** Authentication and authorization are non-negotiable from Phase 1.
TLS in production. OWASP Top 10 compliance as a review checklist.

**Why:**
- Retrofitting security is harder and riskier than building it in.
- As a QA platform, this project handles sensitive data (test results, API keys,
  environment URLs, source code references).
- Practicing security patterns is a core goal of this lab.

**Security progression:**
- Phase 1: JWT + local users + RBAC + HTTPS headers
- Phase 4: OAuth 2.0 + SSO + API tokens + audit logging
- Phase 5: TLS termination at ingress + cert-manager + mTLS (later)

### 12. Multi-tenancy from day one

**Decision:** Every table includes `org_id` or inherits it through a parent
entity. Even in Phase 1 (single-tenant), the column exists and is enforced.

**Why:** Retrofitting multi-tenancy is one of the hardest rewrites. Adding
the column from the start costs almost nothing but saves months later.

### 13. Stripe for payments (no raw card handling)

**Decision:** Use Stripe Checkout (hosted payment page) and Stripe Customer
Portal for all payment flows. Never handle raw card numbers on our servers.

**Why:**
- PCI compliance is extremely expensive and complex to achieve yourself.
- Stripe Checkout is PCI DSS Level 1 compliant out of the box.
- Webhooks give us async subscription lifecycle events — fits the
  event-driven architecture (webhook → API → Kafka event → state update).
- Stripe SDK handles retries, idempotency keys, and error recovery.
- Test mode with Stripe CLI lets you simulate every scenario locally.

**Pattern:** Stripe is the source of truth for payment state. Our database
stores a synchronized copy via webhooks. If they ever disagree, Stripe wins.

## Security architecture

### Authentication flow

```
                                   ┌──────────────┐
                                   │   Identity    │
                                   │   Provider    │
                                   │ (GitHub/Azure │
                                   │   AD/Google)  │
                                   └──────┬───────┘
                                          │ OAuth 2.0 + PKCE
┌─────────┐    HTTPS    ┌─────────┐      │         ┌─────────┐
│ Browser  │───────────►│ Gateway │◄─────┘         │  Redis  │
│ (React)  │◄───────────│ (TLS   │                 │ (session│
│          │  JWT cookie │ termin.)│                 │  cache) │
└─────────┘             └────┬────┘                 └────┬────┘
                             │                           │
                        ┌────▼────┐    JWT validate  ┌───▼────┐
                        │   API   │◄────────────────►│Postgres│
                        │ Server  │   user + roles   │ (users,│
                        │         │                  │  audit) │
                        └─────────┘                  └────────┘
```

### Authentication phases

| Phase | Strategy | Details |
|---|---|---|
| Phase 1 | JWT + local users | Spring Security, bcrypt passwords, hardcoded seed users |
| Phase 4 | OAuth 2.0 / OIDC (SSO) | GitHub, Google, Azure AD via Spring Security OAuth2 Client |
| Phase 4 | MFA / 2FA | Email OTP, SMS OTP (Twilio); optional TOTP authenticator app |
| Phase 4+ | SAML 2.0 | Enterprise SSO for large orgs |

### Authorization model (RBAC)

| Role | Projects | Environments | Suites | Runs | Users | Org settings |
|---|---|---|---|---|---|---|
| OWNER | CRUD | CRUD | CRUD | CRUD (trigger + read; runs are immutable, no update/cancel yet) | CRUD | CRUD |
| ADMIN | CRUD | CRUD | CRUD | CRUD (trigger + read) | Read + Invite | Read |
| MEMBER | Read | CRUD | CRUD | CRUD (trigger + read) | Read | — |
| VIEWER | Read | Read | Read | Read | — | — |

Every request carries `orgId` from the JWT. Every query filters by `orgId`.
No cross-tenant data access is possible at the query level.

### TLS / HTTPS strategy

| Environment | TLS Termination | Certificate |
|---|---|---|
| Local dev | No TLS (localhost HTTP) | — |
| Staging | Azure Load Balancer / Ingress | Let's Encrypt via cert-manager |
| Production | Azure Load Balancer / Ingress | Let's Encrypt or enterprise CA |

- Minimum TLS 1.2, prefer TLS 1.3.
- HSTS enabled with preload.
- Internal cluster traffic: plain HTTP (later mTLS via service mesh).

### Security headers (set at Gateway)

```
Strict-Transport-Security: max-age=31536000; includeSubDomains; preload
Content-Security-Policy: default-src 'self'; img-src 'self' data:
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: camera=(), microphone=(), geolocation=()
```

## Rate limiting

### Strategy

Rate limiting happens at two levels:

```
┌─────────────────────────────────────────────┐
│ Gateway (Spring Cloud Gateway + Redis)      │  ← global rate limit
│ Token bucket per API key / IP               │     per-client
│ Headers: X-RateLimit-Limit, Remaining, Reset│
└──────────────────────┬──────────────────────┘
                       │
┌──────────────────────▼──────────────────────┐
│ Application layer (per-operation limits)    │  ← business rate limit
│ e.g., max 100 runs/hour/org                 │     per-operation
│ e.g., max 10 AI analyses/hour/org           │
└─────────────────────────────────────────────┘
```

### Rate limit tiers

| Tier | Requests/min | Run triggers/hour | AI requests/hour |
|---|---|---|---|
| Free | 60 | 50 | 10 |
| Pro | 600 | 500 | 100 |
| API token | 300 | 200 | 50 |

### Implementation

Gateway-level: Spring Cloud Gateway `RequestRateLimiter` filter backed by Redis.
Application-level: Redis counters with TTL per org per operation.

When rate limited, return `429 Too Many Requests` with `Retry-After` header.

## API design

### Design principles
- **RESTful** — resources, not actions. Use HTTP verbs correctly.
- **Versioned** — all endpoints under `/api/v1/`. Breaking changes get `/api/v2/`.
- **Consistent** — same envelope, same error format, same pagination everywhere.
- **Secure by default** — every endpoint authenticated except health + login.
- **Idempotent** — PUT and DELETE are idempotent. POST returns 409 on duplicates.
- **Discoverable** — OpenAPI spec auto-generated from annotations.

### Endpoints

```
# Auth
POST   /auth/login                         # local login → JWT
POST   /auth/refresh                       # refresh access token
POST   /auth/logout                        # revoke refresh token
GET    /auth/oauth2/authorize/{provider}   # OAuth redirect (Phase 4)
GET    /auth/oauth2/callback/{provider}    # OAuth callback (Phase 4)

# Projects
GET    /api/v1/projects                    # list projects (filtered by org)
POST   /api/v1/projects                    # create project
GET    /api/v1/projects/{id}               # get project
PUT    /api/v1/projects/{id}               # update project
DELETE /api/v1/projects/{id}               # soft delete project

# Environments — list/create are nested under project (ownership check needs
# project context); get/update/delete are flat since {id} is already globally
# unique, matching the /api/v1/projects/{id} precedent.
GET    /api/v1/projects/{projectId}/environments  # list environments
POST   /api/v1/projects/{projectId}/environments  # register environment
GET    /api/v1/environments/{id}                  # get environment
PUT    /api/v1/environments/{id}                  # update environment
DELETE /api/v1/environments/{id}                  # soft delete environment

# Test suites
GET    /api/v1/projects/{projectId}/suites  # list suites
POST   /api/v1/projects/{projectId}/suites  # create suite
GET    /api/v1/suites/{id}                  # get suite
PUT    /api/v1/suites/{id}                  # update suite
DELETE /api/v1/suites/{id}                  # soft delete suite

# Test cases
GET    /api/v1/suites/{suiteId}/cases  # list cases in suite
POST   /api/v1/suites/{suiteId}/cases  # add case to suite
GET    /api/v1/cases/{id}              # get case
PUT    /api/v1/cases/{id}              # update case
DELETE /api/v1/cases/{id}              # soft delete case

# Test runs — flat, not nested under project, since a run always names its
# project/suite/environment explicitly in the request body; list supports
# optional ?projectId=&suiteId=&status= filters. Runs are immutable once
# triggered (domain rule #2) — no PUT/DELETE/cancel endpoint yet.
POST   /api/v1/runs                        # trigger a test run
GET    /api/v1/runs                        # list runs (optional filters)
GET    /api/v1/runs/{id}                   # get run details
GET    /api/v1/runs/{id}/results           # get run results

# Analytics
GET    /api/v1/projects/{projectId}/analytics  # pass rate + run count, last N days (Phase 1)
GET    /api/v1/analytics/flaky                 # flaky test report (Phase 3)
GET    /api/v1/analytics/trends                # pass/fail trends over time (Phase 3)
GET    /api/v1/analytics/slow                  # slowest tests (Phase 3)

# API tokens (Phase 4)
POST   /api/v1/tokens                      # create API token
GET    /api/v1/tokens                      # list tokens (masked)
DELETE /api/v1/tokens/{id}                 # revoke token

# Admin
GET    /api/v1/admin/users                 # list org users
POST   /api/v1/admin/users/invite          # invite user
PUT    /api/v1/admin/users/{id}/role       # change user role
GET    /api/v1/admin/audit-log             # view audit log

# Billing / Subscriptions (Phase 4B)
GET    /api/v1/billing/subscription          # current plan + usage
POST   /api/v1/billing/checkout              # create Stripe Checkout session → redirect URL
POST   /api/v1/billing/portal                # create Stripe Customer Portal session → redirect URL
GET    /api/v1/billing/invoices              # invoice history
GET    /api/v1/billing/plans                 # available plans + pricing
POST   /api/v1/billing/webhooks/stripe       # Stripe webhook receiver (public, signature-verified)
```

### Response envelope

**Success:**
```json
{
  "data": { ... },
  "meta": { "page": 1, "pageSize": 20, "total": 142 }
}
```

**Error:**
```json
{
  "error": {
    "code": "PROJECT_NOT_FOUND",
    "message": "Project with id 550e8400-... not found",
    "details": [
      { "field": "name", "message": "must not be blank" }
    ]
  }
}
```

### HTTP status codes used

| Code | Meaning | When |
|---|---|---|
| 200 | OK | Successful GET, PUT |
| 201 | Created | Successful POST |
| 204 | No Content | Successful DELETE |
| 400 | Bad Request | Validation failure |
| 401 | Unauthorized | Missing or invalid token |
| 403 | Forbidden | Valid token, insufficient permissions |
| 404 | Not Found | Resource doesn't exist (or wrong org) |
| 409 | Conflict | Duplicate resource |
| 429 | Too Many Requests | Rate limited |
| 500 | Internal Server Error | Unexpected failure |
```

## Execution flow (the core loop)

```
1. User clicks "Run Regression" in React UI
2. Frontend POST /api/v1/projects/{id}/runs { suite_id, env_id, tags }
3. Gateway validates auth, forwards to API
4. API creates TestRun record (status: PENDING) in Postgres
5. API publishes RunRequestedEvent to Kafka topic: runs.requested
6. Worker consumer picks up the event
7. Worker updates run status to RUNNING (via Kafka event → API updates DB)
8. Worker executes each test case:
   a. For API tests: HTTP client execution
   b. For UI tests: Playwright execution
   c. For perf tests: load generator
9. Worker publishes ResultChunkEvent per test case to: results.chunks
10. API consumer updates TestResult records in Postgres
11. API pushes updates to frontend via WebSocket (or polling)
12. Worker publishes RunCompletedEvent when done
13. API triggers post-run analysis: flakiness scoring, AI failure analysis
14. Dashboard updates with final results
```

## Technology decisions log

| Decision | Chosen | Alternatives considered | Why |
|---|---|---|---|
| Backend language | Java 21 | Kotlin, Go, Python | Industry standard for enterprise, virtual threads, strong ecosystem |
| Backend framework | Spring Boot 3 | Quarkus, Micronaut | Most mature, best documentation, widest community |
| Frontend | React 18 + TS | Next.js, Angular, Vue | Most hiring-relevant, flexible, great tooling |
| Build tool | Maven | Gradle | More predictable, XML is annoying but unambiguous |
| DB | PostgreSQL | MySQL, MongoDB | Best for relational data, JSONB for flexible fields |
| Cache | Redis | Memcached, Hazelcast | Versatile (cache + pub/sub + rate limit), industry standard |
| Messaging | Kafka | RabbitMQ, Redis Streams | Best for event-driven architecture learning, exactly-once semantics |
| Gateway | Spring Cloud Gateway | Traefik, Kong, NGINX | Stays in Java ecosystem, easy to customize |
| Migrations | Flyway | Liquibase | Simpler, SQL-native, widely adopted |
| Containers | Docker + Compose | Podman | Docker Desktop is ubiquitous, Compose is simple |
| CI/CD | GitHub Actions | Jenkins, GitLab CI | Free for public repos, native GitHub integration |
| E2E testing | Playwright | Cypress, Selenium | Fastest, best DX, MCP integration |
| IaC | Terraform | Bicep, Pulumi, CloudFormation | Multi-cloud transferable, industry standard, modular |
| Orchestration | AKS (Helm) | Azure App Service, ECS | Full K8s learning, portable skills |

## Extending this project

For lab/playground work — system design concepts map, **k6 load testing**,
and **Google Stitch + DESIGN.md** for the frontend — see Phase 7 in
`docs/product/ROADMAP.md`.

```
Adding a new domain module?
  → Create package under com.qualityops.api.<module>
  → Follow the standard structure: controller, service, repository, dto, model
  → Add Flyway migration for any new tables
  → Add module entry in ARCHITECTURE.md
  → Create tests with Testcontainers

Adding a new Kafka event?
  → Define the event record in the producing module's event/ package
  → Register the topic in config
  → Add consumer in the consuming module
  → Document the event in this file's execution flow section

Adding a new API endpoint?
  → Follow REST conventions above
  → Add OpenAPI annotation
  → Add integration test
  → Update Postman collection if it exists

Adding frontend pages?
  → Create feature module in src/features/<name>/
  → Add route in router config
  → Use TanStack Query for data fetching
  → Add Vitest tests for logic, Playwright test for critical paths

Changing infrastructure?
  → Update docker-compose.yml for local dev
  → Update Helm charts for Kubernetes deployments
  → Update Terraform modules for Azure resources
  → Create ADR in docs/architecture/decisions/
  → Update this file
```

## Dependencies

| Package | Layer | Why |
|---|---|---|
| Spring Boot 3 | Backend | Application framework |
| Spring Data JPA | Backend | Database access |
| Spring Security | Backend | Authentication + authorization |
| Spring Cloud Gateway | Gateway | Routing + filtering |
| Spring Kafka | Backend + Worker | Kafka producer/consumer |
| Spring Data Redis | Backend | Redis client |
| Flyway | Backend | Database migrations |
| Testcontainers | Backend (test) | Real containers in integration tests |
| JUnit 5 | Backend (test) | Test framework |
| React 18 | Frontend | UI library |
| TanStack Query | Frontend | Server state management |
| Vite | Frontend (build) | Dev server + bundler |
| Tailwind CSS | Frontend | Utility-first CSS |
| Vitest | Frontend (test) | Unit + component testing |
| Playwright | E2E (test) | Browser automation |
| PostgreSQL 16 | Infra | Primary database |
| Redis 7 | Infra | Cache + pub/sub |
| Apache Kafka | Infra | Event streaming |
