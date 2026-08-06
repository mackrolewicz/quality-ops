 # Roadmap — QualityOps Lab

From lab project to AI-native QA SaaS.

## System design classroom lab

**QualityOps Lab is a system design classroom.** You are building a whole
system — backend, frontend, gateway, messaging, infra, payments, AI — step
by step, the same way a senior/principal engineer would at a real company.
Every phase teaches a new layer of the stack. Every concept in the concepts
map is something real you built or can build here.

```
You are the architect AND the student.
You design the system → Claude Code executes → you review, fix, learn.
```

| Principle | What it means here |
|---|---|
| **Build the whole system** | Not just CRUD — gateway, Kafka, Redis, IaC, payments, AI |
| **Step by step, phase by phase** | Finish Phase 1 before touching Phase 2 |
| **Design before code** | DESIGN.md before React pages; ADRs before new modules |
| **Measure, don't guess** | k6 load tests, Grafana, OpenTelemetry traces |
| **Document learnings** | ADR in docs/architecture/decisions/ when something clicks |
| **Safe to experiment** | Docker Compose locally; chaos only on staging/AKS later |
| **Reusable template** | When done, 80% of this repo is your SaaS starter for future products |

Everything lives in one monorepo — one docker compose up, one Claude Code
workspace, all layers visible and connected.

## Phase 1: Foundation (you are here)
**Goal:** Working skeleton with basic CRUD, simulated test execution, and
security foundations.

| Deliverable | Status |
|---|---|
| Project structure + Claude Code setup | Done |
| Spring Boot API skeleton (hexagonal modules) | Not started |
| React frontend shell with routing | Not started |
| **Google Stitch DESIGN.md** (design tokens + rationale) | Not started |
| PostgreSQL + Flyway migrations | Not started |
| Docker Compose (full local stack) | Not started |
| **Spring Security + JWT auth** | Not started |
| **RBAC (Owner/Admin/Member/Viewer)** | Not started |
| **Security headers at Gateway** | Not started |
| **CORS configuration** | Not started |
| **Gateway-level rate limiting (Redis)** | Not started |
| Project CRUD (API + UI) | Not started |
| Environment registry (API + UI) | Not started |
| Test suite + case catalog (API + UI) | Not started |
| Run orchestration via Kafka (event-driven) | Not started |
| Kafka consumers inside API app (same JVM) | Not started |
| Simulated test execution (in API, not a separate worker yet) | Not started |
| Results dashboard (API + UI) | Not started |
| GitHub Actions CI pipeline | Not started |
| Spring Cloud Gateway routing | Not started |
| **Input validation (@Valid on all endpoints)** | Not started |
| **OpenAPI spec generation** | Not started |

**Exit criteria:** `docker compose up` gives you a working platform. User
can log in → create project → add suite → trigger run → see results.
All endpoints authenticated. Rate limiting active.

---

## Phase 2: Core Platform
**Goal:** Real test execution, richer analytics, production-grade features.

| Deliverable | Notes |
|---|---|
| **Extract Worker from API** | Split Kafka consumers into separate Spring Boot app |
| Playwright test runner in Worker | Execute real browser tests |
| API test runner in Worker | HTTP-based test execution |
| Retry logic for failed tests | Configurable retry count |
| Run scheduling (cron-based) | Schedule recurring runs |
| Flaky test detection | Track test stability over N runs |
| Test duration trends | Chart slowest tests over time |
| Environment health monitoring | Periodic health checks |
| Redis caching for dashboard | Fast dashboard reads |
| WebSocket for real-time updates | Replace polling |
| **Application-level rate limiting** | Per-operation limits (runs/hour, AI calls) |
| **Spring AOP and pointcuts** | Custom `@Audited` and `@Timed` annotations; aspects for audit events and slow-operation metrics |
| **AOP proxy behavior tests** | Verify `@Around`/`@Before`, exception propagation, annotation pointcuts, and Spring self-invocation limitation |
| **HTTPS in staging environment** | TLS termination at load balancer |
| **Dependency vulnerability scanning** | `mvn dependency-check`, `npm audit` in CI |
| **Container image scanning** | Trivy in CI pipeline |

**Exit criteria:** Platform can execute real Playwright and API tests,
detect flaky tests, and show meaningful analytics. Cross-cutting audit and
timing concerns use tested Spring AOP aspects. HTTPS in staging.

---

## Phase 3: Intelligence
**Goal:** AI-powered failure analysis and test generation.

| Deliverable | Notes |
|---|---|
| AI failure analysis | Claude analyzes failed runs |
| Root cause clustering | Group similar failures |
| Test gap detection | Suggest missing test coverage |
| AI test generation from OpenAPI specs | Generate API tests |
| AI test generation from requirements | Generate E2E scenarios |
| Failure trend prediction | Flag tests likely to fail |

**Exit criteria:** AI agent can analyze a failed run and produce a useful
diagnosis. AI can generate a basic test suite from an API spec.

---

## Phase 4: SaaS Ready
**Goal:** Multi-tenant, production-secure, onboardable by external teams.

| Deliverable | Notes |
|---|---|
| Multi-tenancy enforcement | org_id on all queries (hooks exist from Phase 1) |
| Organization management | Create/manage orgs |
| User management (full RBAC) | Owner/Admin/Member/Viewer with invite flow |
| **OAuth 2.0 + OIDC (GitHub, Google)** | Social + developer SSO login |
| **Azure AD / Entra ID integration** | Enterprise SSO (OIDC) |
| **Two-factor authentication (2FA / MFA)** | Email OTP + SMS OTP after password or SSO |
| **Optional TOTP** | Authenticator app (Google Authenticator, etc.) |
| **API tokens for CI integration** | Scoped, hashed, revocable tokens |
| **Audit logging** | All security events, admin actions, data access |
| **Brute force protection** | Account lockout after N failed attempts |
| **Session management** | Refresh token rotation, revocation |
| **OWASP Top 10 security audit** | Full compliance review |
| Onboarding wizard | Connect repo → detect tests → first run |
| Usage metering | Track runs per org for billing |
| **SAML 2.0 support** (optional) | Enterprise SSO for large orgs |

**Exit criteria:** An external team can sign up via GitHub/Google SSO,
create an org, connect their repo, and start running tests. Users with 2FA
enabled must pass email or SMS verification (or TOTP) after login. Full audit
log. API tokens for CI/CD. OWASP Top 10 compliant.

### SSO and 2FA (Phase 4 detail)

**SSO (already in scope):** Users can sign in with GitHub, Google, or Azure AD
instead of a local password. Spring Security OAuth2 Client + OIDC. Enterprise
orgs can add SAML 2.0 later.

**2FA / MFA (new):** Second step after primary auth (password or SSO).

| Method | Provider (lab → prod) | Flow |
|---|---|---|
| Email OTP | Mailhog (local) → SendGrid / AWS SES | 6-digit code, 10 min TTL, rate-limited |
| SMS OTP | Twilio (test credentials) | Same code rules; E.164 phone on user profile |
| TOTP (optional) | `dev.samstevens.totp` or similar | QR enroll; backup codes stored hashed |

**Endpoints (planned):**

```
POST /api/v1/auth/mfa/enroll          # start enroll (email / sms / totp)
POST /api/v1/auth/mfa/verify-enroll   # confirm enroll with code
POST /api/v1/auth/mfa/challenge       # after login, send OTP if 2FA enabled
POST /api/v1/auth/mfa/verify          # submit code → full session JWT
DELETE /api/v1/auth/mfa/{method}      # disable (requires recent auth + password)
```

**Rules:** Hash OTPs at rest (or store only HMAC); max 5 verify attempts; lockout
aligns with brute-force protection; audit log every enroll/disable/verify failure.

---

## Phase 4B: Payments and Subscriptions
**Goal:** Integrate a payment gateway so organizations can subscribe to
plans, manage billing, and pay for the service. Practice real-world
external API integration, webhook handling, and subscription lifecycle.

### Stripe integration

| Deliverable | Notes |
|---|---|
| Stripe account + API keys | Test mode first, production later |
| Subscription plans (Free / Pro / Enterprise) | Define tiers with feature limits |
| Stripe Checkout integration | Hosted payment page (PCI-compliant, no card data on your server) |
| Stripe Customer Portal | Self-service billing management (upgrade, downgrade, cancel) |
| Webhook handler for Stripe events | `checkout.session.completed`, `invoice.paid`, `invoice.payment_failed`, `customer.subscription.updated`, `customer.subscription.deleted` |
| Subscription model in database | `subscriptions` table linked to org, synced via webhooks |
| Plan enforcement (feature gating) | Free = 100 runs/month, Pro = 10k, Enterprise = unlimited |
| Usage-based billing (metered) | Track test runs, report usage to Stripe |
| Invoice history page | Show past invoices, download PDF |
| Payment failure handling | Grace period, downgrade to free, email notifications |
| Stripe webhook signature verification | Prevent spoofed webhook attacks |

### Backend patterns you'll learn

| Pattern | How it applies |
|---|---|
| **External API integration** | Stripe SDK, API keys, error handling, retries |
| **Webhook handling** | Async events from external service, idempotency |
| **Idempotent event processing** | Same Stripe event delivered twice must not double-charge |
| **Subscription state machine** | `trialing → active → past_due → canceled → expired` |
| **Feature flags by plan** | Check org's plan tier before allowing actions |
| **PCI compliance** | Never touch raw card numbers — Stripe Checkout handles it |
| **Secrets management** | API keys in Key Vault / env vars, never in code |
| **Testing external APIs** | Mock Stripe in tests, use Stripe CLI for local webhooks |

### Frontend pages

| Page | What it does |
|---|---|
| `/billing` | Current plan, usage, next invoice date |
| `/billing/plans` | Compare plans, upgrade/downgrade buttons |
| `/billing/checkout` | Redirect to Stripe Checkout |
| `/billing/invoices` | Invoice history with PDF download |
| `/billing/portal` | Redirect to Stripe Customer Portal |

### Alternative payment APIs to explore later

| Provider | Why interesting |
|---|---|
| **Stripe** (primary) | Industry standard, best docs, webhooks, test mode |
| **Paddle** | Handles tax/VAT automatically (merchant of record) |
| **LemonSqueezy** | Simpler Stripe alternative for SaaS |
| **PayPal** | Practice a second integration for comparison |

**Exit criteria:** A user can subscribe to a plan via Stripe Checkout,
upgrade/downgrade from the billing page, and the system enforces plan
limits. Webhook events are processed idempotently. Stripe test mode
passes all subscription lifecycle scenarios.

---

## Phase 5: Cloud Native
**Goal:** Production deployment on Azure with full observability and hardened security.

| Deliverable | Notes |
|---|---|
| Helm charts for AKS | All services in Kubernetes |
| Azure Database for PostgreSQL | Managed DB |
| Azure Cache for Redis | Managed cache |
| Kafka decision: Confluent Cloud or Event Hubs | Managed messaging |
| GitHub Actions deploy pipeline | Build → push → deploy to AKS |
| OpenTelemetry instrumentation | Distributed tracing |
| Prometheus + Grafana | Metrics dashboards |
| Loki for centralized logging | Searchable logs |
| **Terraform modules for Azure infra** | AKS, PostgreSQL, Redis, VNet, Key Vault, ACR |
| **Remote state in Azure Storage** | Shared state with locking for team workflows |
| **Per-environment Terraform configs** | staging/ and production/ with same modules, different vars |
| **Terraform CI/CD (plan on PR, apply on merge)** | IaC pipeline in GitHub Actions |
| Horizontal pod autoscaling | Scale workers with load |
| **TLS 1.3 at ingress (cert-manager + Let's Encrypt)** | Automatic cert renewal |
| **HSTS with preload** | Force HTTPS everywhere |
| **Azure Key Vault for secrets** | No secrets in K8s manifests |
| **Network policies** | Restrict pod-to-pod traffic |
| **mTLS between services** (optional) | Istio/Linkerd service mesh |
| **DAST scanning in staging** | OWASP ZAP automated scans |
| **Penetration testing** | Manual security review before go-live |

**Exit criteria:** Platform runs on AKS with full observability. TLS everywhere.
All Azure infrastructure provisioned via Terraform (no manual portal clicks).
Secrets in Key Vault. Deploy with `git push` to main. Pen test passed.

---

## Phase 6: AI Agent — RAG, LangChain, and Custom Agents
**Goal:** Build a custom AI-powered QA agent using real AI engineering
patterns — RAG, embeddings, vector databases, tool-use agents, and LLM
orchestration. This is the AI/ML engineering learning phase.

### 6A: Foundation — RAG Pipeline

| Deliverable | Stack | What you learn |
|---|---|---|
| **Python AI service** (`apps/ai-agent/`) | Python 3.12, FastAPI | Adding a Python service to a Java ecosystem |
| **Embedding pipeline** | OpenAI / local embeddings | How text embeddings work |
| **Vector database** | Pinecone (cloud) or pgvector (Postgres) | Vector similarity search, indexing |
| **Document ingestion** | LangChain document loaders | Chunking, metadata, preprocessing |
| **RAG retrieval chain** | LangChain RetrievalQA | Context-augmented generation |
| **Index test results + logs** | Custom pipeline | Feed historical failures into vector store |
| **Index codebase** | Git + embedding pipeline | Agent understands your code |

**What you'll be able to do:** Ask the agent "Why did the login test fail
last 5 times?" and it retrieves relevant logs, test results, and code
to give a contextualized answer.

### 6B: Tool-Use Agent

| Deliverable | Stack | What you learn |
|---|---|---|
| **Agent with tools** | LangChain agents + tools | Tool-use pattern, ReAct prompting |
| **Tool: query test results** | REST API call to your platform | Agent calls your own API |
| **Tool: read source code** | Git + file reader | Agent browses the codebase |
| **Tool: run Playwright test** | Playwright MCP | Agent executes a test and reads results |
| **Tool: create GitHub issue** | GitHub API | Agent files bug reports |
| **Tool: query Postgres** | SQL tool (read-only) | Agent queries your database |
| **Agent memory** | Redis or Postgres | Conversation history, long-term memory |
| **Agent orchestration** | LangGraph or custom | Multi-step reasoning chains |

**What you'll be able to do:** Tell the agent "Analyze the last regression
run, find the root cause of failures, and create GitHub issues for real bugs."
The agent calls tools, reasons through the data, and acts.

### 6C: Advanced AI Features

| Deliverable | Stack | What you learn |
|---|---|---|
| **Generate tests from OpenAPI spec** | LangChain + code generation | LLM code generation, validation |
| **Generate tests from PR diffs** | GitHub webhook + agent | Event-triggered AI workflows |
| **Auto-triage failures** | Classification chain | Prompt engineering, few-shot learning |
| **Flaky test predictor** | scikit-learn or simple heuristics | ML basics, feature engineering |
| **Failure clustering** | Embeddings + DBSCAN/k-means | Unsupervised ML, similarity |
| **PR review agent** | GitHub MCP + code analysis | Agent comments on test coverage gaps |
| **Figma → test generation** | Figma MCP + Playwright | Multimodal AI, visual understanding |
| **Fine-tuning experiment** | OpenAI fine-tune or local model | Transfer learning, data curation |
| **Evaluation framework** | Custom evals | How to measure agent quality |
| **Prompt management** | LangSmith or custom | Prompt versioning, A/B testing |

### 6D: Agent Infrastructure

| Deliverable | Stack | What you learn |
|---|---|---|
| **Agent API endpoint** | FastAPI | Serving AI agents as a service |
| **Streaming responses** | SSE / WebSocket | Real-time AI output to frontend |
| **Agent observability** | LangSmith / Langfuse | Tracing AI chains, cost tracking |
| **Rate limiting for LLM calls** | Redis | Controlling API costs |
| **Caching LLM responses** | Redis + semantic cache | Deduplication, cost savings |
| **Fallback models** | Primary → fallback chain | Resilience for AI features |
| **Agent chat UI** | React component | Chat interface in your dashboard |

### AI Agent tech stack

| Layer | Technology | Why |
|---|---|---|
| Language | Python 3.12 | AI/ML ecosystem lives in Python |
| Framework | FastAPI | Async, fast, great for AI APIs |
| LLM orchestration | LangChain / LangGraph | Industry standard for agent building |
| Embeddings | OpenAI `text-embedding-3-small` or local | Best balance of quality vs cost |
| Vector DB | Pinecone (managed) or pgvector (Postgres) | Pinecone to learn managed vector DBs, pgvector to keep it simple |
| LLM provider | OpenAI / Anthropic Claude API | Start with one, add fallback |
| Observability | LangSmith or Langfuse | Trace and debug AI chains |
| Memory | Redis + Postgres | Short-term (Redis) + long-term (Postgres) |

**Exit criteria:** AI agent can analyze a failed run (using RAG over
historical data), generate missing tests, create bug reports, and explain
failures in natural language. You understand RAG, embeddings, vector search,
tool-use agents, and LLM orchestration well enough to discuss them in a
system design interview.

---

## Phase 7: System Design Playground (ongoing)
**Goal:** Use the platform as a **lab** to practice system design, load
testing, and UI design workflows. Add one experiment at a time. Nothing here
is mandatory for MVP — pick what you want to learn that week.

| Pattern | Exercise | Difficulty |
|---|---|---|
| Circuit Breaker | Add Resilience4j to Worker | Easy |
| CQRS | Separate read/write models for dashboard | Medium |
| Event Sourcing | Rebuild run state from event log | Medium |
| Saga Pattern | Multi-step run with compensating actions | Medium |
| State machine modeling | Status enums + transition guards (run, subscription, payment) | Easy |
| Domain modeling (DDD) | Aggregates, value objects, rich entities, invariants | Medium |
| Transactional outbox | Event in same DB tx, relay to Kafka separately | Medium |
| Optimistic locking | `@Version` + 409 retry on concurrent updates | Easy |
| Cache consistency | Invalidate-on-write vs TTL; document stale-read policy | Medium |
| Service discovery | K8s DNS + optional Spring Cloud discovery client | Medium |
| Redis Pub/Sub | Real-time run status fan-out to WebSocket layer | Easy |
| Modular monolith audit | Enforce package boundaries; arch unit tests | Easy |
| API design review | OpenAPI lint, pagination, error envelope consistency | Easy |
| Bulkhead | Separate thread pools for API vs Kafka | Easy |
| WebSockets | Real-time run status to frontend | Medium |
| Write-Through Cache | Sync Redis + Postgres on status change | Easy |
| Read Replica | Postgres replica for analytics | Medium |
| Consistent Hashing | Distribute runs across Workers | Hard |
| Database Sharding | Shard by org_id | Hard |
| Service Mesh | Istio between services | Hard |
| Bloom Filter | Fast "was this test ever flaky?" lookup | Medium |
| Change Data Capture | Debezium → Kafka for real-time sync | Medium |
| gRPC | Internal service-to-service communication | Medium |
| GraphQL | Alternative API for flexible frontend queries | Medium |
| Feature Flags | LaunchDarkly or custom | Easy |
| A/B Testing | Canary deployments at Gateway | Medium |
| Chaos Engineering | Kill pods, break Kafka, test resilience | Medium |
| k6 load test suite | Smoke + stress scripts in `tests/load/k6/` | Easy |

### Load testing with k6

**Tool:** [Grafana k6](https://k6.io/) — JavaScript scenarios, thresholds,
HTML/JSON reports, runs locally or in CI.

**Planned layout:**

```
tests/load/k6/
├── README.md                 # how to run against local stack
├── config.js                 # base URL, auth helper, default thresholds
├── smoke/
│   └── health-and-login.js   # gateway health + login (low VUs)
├── api/
│   ├── list-projects.js      # read-heavy dashboard path
│   └── trigger-run.js        # write-heavy: POST run (watch Kafka lag)
└── stress/
    └── gateway-rate-limit.js # prove 429 when over limit
```

| Scenario | What you learn | When to run |
|---|---|---|
| Smoke | Stack is up; JWT flow works | After every deploy / `docker compose up` |
| Read load | API + Redis cache under list/dashboard traffic | Phase 2+ |
| Write load | Trigger-run burst → Kafka queue depth, DB writes | Phase 2+ (worker or in-API consumer) |
| Stress / soak | Find breaking point; p95 latency, error rate | Phase 7 playground |
| Rate limit | Gateway returns 429; no cascade to DB | Phase 1+ (once limiting exists) |

**Example thresholds (tune per environment):**

```javascript
export const options = {
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};
```

**Commands (after scripts exist):**

```bash
# Install k6: https://k6.io/docs/get-started/installation/
k6 run tests/load/k6/smoke/health-and-login.js
k6 run --vus 50 --duration 2m tests/load/k6/api/trigger-run.js
```

**CI (optional, Phase 5+):** GitHub Actions job on `tests/load/k6/**` path
filter — smoke only on PR; full stress manual `workflow_dispatch`.

**Concepts practiced:** horizontal scaling (scale pods, re-run k6), load
shedding, backpressure (Kafka lag vs HTTP latency), rate limiting, graceful
degradation (Redis down + k6), failure modes under load.

---

### UI design with Google Stitch

**What it is:** [Google Stitch](https://developers.googleblog.com/en/stitch-a-new-way-to-design-uis/)
(Google Labs) — AI-native UI design canvas. Exports **DESIGN.md**, an
open format for design tokens + rationale that coding agents can follow
([spec](https://github.com/google-labs-code/design.md)).

**How it fits this lab:**

| Step | Action |
|---|---|
| 1 | Design screens in Stitch (prompt, sketch, or URL → UI) |
| 2 | Export or author `apps/web/DESIGN.md` (colors, typography, spacing, components) |
| 3 | Implement in React + Tailwind to match tokens |
| 4 | Optional: Stitch MCP + skills in Claude Code / Cursor for iteration |

**Planned files:**

```
apps/web/
├── DESIGN.md              # Stitch design system (YAML tokens + prose)
├── src/                   # React implementation
└── README.md              # link to Stitch + how DESIGN.md maps to Tailwind
```

**MCP setup (when ready)** — add to `.mcp.json` or install skills:

```bash
npx skills add google-labs-code/stitch-skills --global
```

See [Stitch MCP / design.md](https://github.com/google-labs-code/design.md) for
export/import of design rules between projects.

**Concepts practiced:** design systems, agent-friendly specs (same idea as
`CLAUDE.md` but for visual identity), design–dev handoff, accessibility
tokens (WCAG in DESIGN.md linter), optional Figma paste from Stitch.

**Phase suggestion:** Start after Phase 1 shell (login + dashboard routes exist)
so Stitch targets real pages. Phase 3+ for Figma MCP + test-generation experiments.

---

### System design concepts map

One row per concept: where it lives in the lab and what to build or study.
**Depth:** Production = real feature in product phases; Exercise = Phase 7
spike; Theory = ADR + docs + interview prep (no full implementation required).

#### Security, auth, and gateway

| Concept | Depth | Phase | Concrete exercise |
|---|---|---|---|
| JWT tokens | Production | 1 | Spring Security: access + refresh, rotation |
| RBAC / authorization | Production | 1–4 | Owner/Admin/Member/Viewer + `@PreAuthorize` |
| API Gateway (routing, filters) | Production | 1 | Spring Cloud Gateway → API + web |
| Rate limiting / throttling | Production | 1–2 | Redis token bucket at gateway + per-tenant app limits |
| CORS, security headers | Production | 1 | Gateway filters + Spring Security headers |
| OAuth / SSO | Production | 4 | GitHub/Google OIDC, optional Azure AD |
| Two-factor auth (2FA) | Production | 4 | Email OTP + SMS OTP; optional TOTP app |
| SSO + 2FA together | Production | 4 | SSO for identity, MFA step before issuing API JWT |

#### Architecture style and API design

| Concept | Depth | Phase | Concrete exercise |
|---|---|---|---|
| Modular monolith | Production | 1+ | One deployable API; packages per domain; split worker in Phase 2 |
| Hexagonal (ports & adapters) | Production | 1+ | `execution`, `identity` modules; adapters for JPA, Kafka, REST |
| API design (REST, versioning) | Production | 1 | `/api/v1/`, envelope, pagination, OpenAPI from annotations |
| API design (errors, status codes) | Production | 1 | Standard error body; 409 on duplicates; `api-design` rule |
| API design (idempotent POST) | Production + Exercise | 1, 7 | Idempotency-Key header for run trigger (optional spike) |
| AOP, aspects, advice, pointcuts | Production + Exercise | 2 | Spring proxy-based AOP with `@Audited`/`@Timed`; compare annotation and package pointcuts; document self-invocation limits |
| Service discovery | Production + Exercise | 5, 7 | K8s Service DNS; optional Spring Cloud K8s discovery |
| JVM virtual threads | Production | 1–2 | Java 21 `spring.threads.virtual.enabled=true` on API |
| Actor model (theory) | Theory | 7 | ADR: Kafka consumers vs Akka actors for run orchestration |
| Virtual machines (cloud) | Production + Theory | 5 | AKS nodes = VMs; ADR on VM SKUs vs container density |

#### Domain modeling (DDD building blocks)

| Concept | Depth | Phase | Concrete exercise |
|---|---|---|---|
| Entities (identity + lifecycle) | Production | 1+ | Model Organization, Project, TestRun with stable IDs and invariants |
| Value objects | Production + Exercise | 1, 7 | Immutable `Email`, `Slug`, `Tag`; validate on construction, no setters |
| Aggregates + aggregate root | Production + Exercise | 1, 7 | `TestRun` root owns `TestResult`s; mutate children only through the root |
| Bounded contexts | Production | 1+ | Module boundaries map to contexts: identity, execution, result, billing |
| Domain events | Production | 1–2 | `RunRequested`, `RunCompleted` as first-class events, not just DTOs |
| Invariants / guards | Production | 1+ | Reject illegal state: can't complete a run that never started |
| Ubiquitous language | Production | 1+ | Same names in code, DB, API, UI (Run, Suite, Environment) |
| Rich vs anemic model | Exercise | 7 | Put behavior on the entity (`run.start()`) vs service-only logic; ADR |

#### State machines (statuses done right)

| Concept | Depth | Phase | Concrete exercise |
|---|---|---|---|
| Test run status | Production | 1–2 | `PENDING → RUNNING → COMPLETED / FAILED / CANCELLED`; reject illegal jumps |
| Test result status | Production | 1–2 | `PASSED / FAILED / SKIPPED / FLAKY` with retry count |
| Subscription status | Production | 4B | `trialing → active → past_due → canceled → expired` (Stripe-driven) |
| Payment status | Production | 4B | `requires_payment → processing → succeeded / failed` |
| Transition table + guards | Production + Exercise | 1, 4B | Encode allowed transitions; unit-test every illegal transition → 409 |
| Status as domain method | Exercise | 7 | `run.markRunning()` enforces source state, not a raw setter |
| Audit of transitions | Production | 4 | Append-only log of who/what changed status and when |

#### Transactions and data integrity

| Concept | Depth | Phase | Concrete exercise |
|---|---|---|---|
| ACID transactions | Production | 1+ | `@Transactional` service methods; one unit of work per command |
| Transaction boundaries | Production | 1+ | Keep tx short; no remote/HTTP calls inside a transaction |
| Optimistic locking | Production | 2 | `@Version` on `TestRun`; concurrent update → 409 → retry |
| Pessimistic locking | Exercise | 7 | `SELECT … FOR UPDATE` on a contended usage-metering counter |
| Isolation levels | Theory + Exercise | 7 | ADR: `READ_COMMITTED` default; when `REPEATABLE_READ` is needed |
| Deduplication (unique key) | Production | 1–4B | Unique constraint on `(org_id, idempotency_key)`; duplicate insert → no-op |
| Idempotency key store | Production + Exercise | 1, 4B | Table of processed event/request IDs; skip if already seen |
| At-least-once + dedup = effectively-once | Production | 1–4B | Kafka redelivers → consumer dedup by event ID makes it safe |
| Transactional outbox | Exercise | 7 | Write event to outbox in same tx; relay to Kafka separately |
| Saga vs distributed transaction | Theory | 7 | ADR: no 2PC across services; use saga + compensating events |

#### Caching and data access

| Concept | Depth | Phase | Concrete exercise |
|---|---|---|---|
| Caching with Redis | Production | 2 | Dashboard reads, session, run status |
| Cache-aside | Production | 2 | Read-through for project/run lists |
| Write-through cache | Exercise | 7 | Update Redis + Postgres on run status change |
| Write-behind cache | Exercise | 7 | Async flush to DB (spike only; complexity vs benefit) |
| Cache consistency | Production + Exercise | 2, 7 | Invalidate Redis on run complete; ADR: stale reads OK for dashboard? |
| Strong vs eventual cache | Theory + Exercise | 7 | ADR + test: read-your-writes for run status after trigger |
| Caching strategy (TTL, eviction) | Production + Exercise | 2, 7 | Document in ADR; tune `maxmemory-policy` |
| Read replica | Exercise | 7 | Postgres replica for analytics queries |
| Database sharding | Exercise | 7 | Shard by `org_id`; routing layer spike |
| Replication (DB HA) | Production | 5 | Azure Database for PostgreSQL HA option |
| Eventual consistency | Production + Theory | 2, 4B, 7 | Kafka + DB; Stripe webhook sync; ADR on trade-offs |

#### Messaging, queuing, and execution

| Concept | Depth | Phase | Concrete exercise |
|---|---|---|---|
| Queuing | Production | 1–2 | Kafka topics: `test-runs.requested`, etc. |
| Idempotency | Production | 1–4B | Consumer check-then-act; Stripe event dedup |
| Retries | Production | 1–2 | Spring Kafka retry + backoff → DLT |
| Backpressure | Exercise | 7 | Max concurrent runs per worker; consumer pause |
| Propagation (events) | Production | 1–2 | Run lifecycle events across modules |
| Change Data Capture | Exercise | 7 | Debezium → Kafka for analytics sync |
| Redis Pub/Sub | Exercise | 2, 7 | Publish run status; gateway or web subscribes for live UI |
| Redis Streams (alternative) | Theory | 7 | ADR: Pub/Sub vs Streams vs Kafka for notifications |
| Saga pattern (orchestration) | Exercise | 7 | Choreography via Kafka events + compensating `run.cancelled` |
| Saga pattern (central coordinator) | Exercise | 7 | Optional: saga state table + orchestrator service spike |

#### Resilience and failure handling

| Concept | Depth | Phase | Concrete exercise |
|---|---|---|---|
| Fault tolerance | Production | 1–5 | DLT, health checks, K8s restarts |
| Circuit breaker | Exercise | 7 | Resilience4j on external calls (Playwright runner) |
| Bulkhead | Exercise | 7 | Separate thread pools: HTTP vs Kafka consumers |
| Load shedding | Exercise | 7 | Reject new runs when queue depth > threshold |
| Graceful degradation | Production | 2 | Redis down → app works without cache |
| Failure modes | Production + Theory | All | `system-design` skill table; extend per feature |
| Cascading failures | Exercise | 7 | Chaos: kill worker while API accepts runs |
| Retries vs idempotency | Production | 1–2 | Document in ADR; test duplicate delivery |
| Saga (compensating actions) | Exercise | 7 | Multi-step run: allocate → execute → report; rollback on failure |

#### Scaling and load

| Concept | Depth | Phase | Concrete exercise |
|---|---|---|---|
| Horizontal scaling | Production | 5 | HPA on API and Worker pods in AKS |
| Stateless architecture | Production | 1+ | No server-side session; JWT in cookie; Redis for shared state only; any pod handles any request |
| Service discovery (K8s) | Production | 5 | `api-service`, `worker-service` DNS inside cluster |
| Service discovery (local) | Production | 1 | Docker Compose service names; gateway routes by hostname |
| Consistent hashing | Exercise | 7 | Distribute runs across worker instances |
| Load / stress testing (k6) | Exercise | 5, 7 | See **Load testing with k6** above; smoke → stress |
| k6 smoke tests in CI | Exercise | 5 | PR path filter on `tests/load/k6/smoke/**` |
| k6 + autoscaling | Exercise | 5, 7 | Run stress test, scale HPA, compare p95 latency |
| A/B testing / canary | Exercise | 7 | Canary route at gateway for new API version |
| CDN (Cloudflare free tier) | Exercise | 5, 7 | Proxy static frontend assets + DNS; see CDN section below |
| GeoDNS / geo-routing | Theory + Exercise | 7 | Cloudflare geo-routing rules; ADR on multi-region latency |
| Multi-region deployment | Theory | 7 | ADR: active-active vs active-passive; when to split regions |
| Database replication | Production + Theory | 5, 7 | Azure Postgres HA (sync replica); read replica for analytics |
| Vertical vs horizontal scaling | Theory | 7 | ADR: when to scale up vs scale out; cost comparison |
| Scaling to millions of users | Theory | 7 | See **Scaling to millions** design section below |

#### Concurrency, locks, and HTTP

| Concept | Depth | Phase | Concrete exercise |
|---|---|---|---|
| Concurrency (JVM) | Production | 1–2 | Virtual threads; `@Async` for fire-and-forget |
| Virtual threads (Project Loom) | Production | 1–2 | High concurrency HTTP without thread-per-request cost |
| DB locks (optimistic) | Production | 2 | `@Version` on run entity; conflict → 409 |
| Distributed locks | Exercise | 7 | Redis `SET key NX EX` — one worker per org run |
| HTTP connection pooling | Production | 2 | WebClient/RestTemplate pool config for test runner |
| Latency analysis | Exercise | 7 | OpenTelemetry traces: gateway → API → Kafka → worker |
| Network analysis | Theory + Exercise | 5, 7 | TLS at ingress; optional Wireshark on local stack |

#### Advanced architecture patterns

| Concept | Depth | Phase | Concrete exercise |
|---|---|---|---|
| CQRS | Exercise | 7 | Separate read model for dashboard aggregates |
| Event sourcing | Exercise | 7 | Rebuild run state from Kafka event log |
| Hexagonal / ports-adapters | Production | 1+ | `execution` module structure |
| Feature flags | Exercise | 7 | Toggle flaky detection or AI features per org |
| gRPC / GraphQL | Exercise | 7 | Optional internal or BFF API experiments |
| Service mesh (mTLS) | Exercise | 5, 7 | Optional Istio/Linkerd on AKS |
| Bloom filter | Exercise | 7 | Fast “ever flaky?” lookup before DB query; Guava or RedisBloom |
| Modular monolith boundaries | Production + Exercise | 1, 7 | ArchUnit: no `project` → `execution` illegal imports |
| API versioning strategy | Production + Theory | 1, 7 | `/api/v1` only until v2 needed; ADR on breaking changes |

#### Redis-specific patterns

| Concept | Depth | Phase | Concrete exercise |
|---|---|---|---|
| Redis as cache | Production | 2 | Cache-aside for reads |
| Redis as rate limiter | Production | 1 | Token bucket keys per tenant/IP |
| Redis Pub/Sub | Exercise | 2, 7 | Live run status channel per `orgId` |
| Redis distributed lock | Exercise | 7 | `SET run:{id}:lock NX EX 300` |
| Redis session store | Production | 4 | Optional: gateway session affinity |
| Actor model + Redis | Theory | 7 | ADR: event handlers as “actors” keyed by `runId` vs full Akka |

#### Theory and interview depth (document, don’t over-build)

| Concept | Depth | Phase | Concrete exercise |
|---|---|---|---|
| CAP theorem trade-offs | Theory | 7 | ADR: why Kafka at-least-once + idempotent consumers |
| Consensus (Raft/Paxos) | Theory | 7 | ADR: compare to Kafka consumer group coordination |
| Memory models | Theory | 7 | Reading + notes; link to JVM concurrency docs |
| Failover logic | Production + Theory | 5 | Azure HA + K8s; ADR on RTO/RPO for QA platform |
| Replication (multi-region) | Theory | 7 | ADR spike only unless product needs geo |

### CDN, Cloudflare, and GeoDNS (Phase 5 / 7)

**Goal:** Practice putting a CDN in front of your SaaS frontend and understand
geo-routing, DNS management, and edge caching — without spending money.

**Cloudflare free tier (what you get for free):**

| Feature | Free tier |
|---|---|
| CDN / proxy | Yes — unlimited |
| Global anycast network (200+ PoPs) | Yes |
| DNS management | Yes — free |
| GeoDNS / geo-routing rules | Yes (page rules / workers) |
| DDoS protection | Yes — basic |
| SSL / TLS termination | Yes — auto |
| Caching (static assets) | Yes |
| Cloudflare Workers (edge logic) | 100k requests/day free |
| Analytics | Yes |

**What you can practice:**

- Point your domain DNS to Cloudflare (change nameservers)
- Proxy static React frontend through Cloudflare CDN
- Cache `index.html`, JS, CSS at the edge
- Add security headers at Cloudflare (instead of gateway)
- Geo-routing rule: redirect `/api` requests based on country
- Simulate latency: turn proxy off/on, compare p95 with k6
- Use Cloudflare Workers for edge logic (rate limiting, auth checks)

**Cost:** Free for everything you need in a lab.

**Domain:** You need a real domain to use Cloudflare DNS. Cheapest route:
`qualityops.dev` or similar on Namecheap/Porkbun (~$10–12/year).

**Planned exercise (Phase 7):**

1. Deploy React frontend to Azure Static Web Apps or AKS ingress
2. Point domain to Cloudflare
3. Enable CDN proxy for frontend
4. Run k6 before/after and compare response times
5. Write an ADR on caching strategy (what to cache at edge vs origin)
6. Add a geo-routing rule (EU → Azure West Europe, US → Azure East US — theory exercise)

### Scaling to millions — system design framework

**For Alex Xu-style system design interviews.** Map every concept to something
you built or can build in this lab.

| Tier | What it means | In this lab |
|---|---|---|
| **Single server** | App + DB on one machine | Phase 1 local Docker Compose |
| **Separate DB** | Postgres + app on separate hosts | Phase 1 Docker services |
| **Load balancer** | Distribute traffic across app instances | Phase 5 AKS service + ingress |
| **Stateless app tier** | No session on server; JWT + Redis | Phase 1 (JWT) + Phase 5 |
| **CDN** | Static assets at edge | Phase 5/7 Cloudflare |
| **DB read replica** | Scale reads, reduce DB load | Phase 7 exercise |
| **Cache layer** | Redis cache-aside, reduce DB hits | Phase 2 |
| **Message queue** | Async background work | Phase 1–2 Kafka |
| **Shard DB** | Horizontal DB scale by `org_id` | Phase 7 exercise |
| **Multi-region** | Traffic routed to nearest region | Theory ADR + GeoDNS |
| **Autoscale** | Add pods under load | Phase 5 HPA |

**Alex Xu numbers to internalize (theory):**

| Scale | Architecture |
|---|---|
| 1K users | Single Spring Boot + Postgres |
| 10K users | + Redis cache, connection pool tuning |
| 100K users | + Read replica, CDN, HPA |
| 1M users | + Kafka, stateless workers, DB sharding strategy |
| 10M users | + Multi-region, GeoDNS, global load balancer |

**Your lab can realistically practice up to ~100K scale** locally (k6 + HPA + Redis + read replica). Beyond that is mostly theory ADRs.

**Recommended study alongside the lab:**
- "System Design Interview" vol 1 + 2 (Alex Xu) — read a chapter, then find the relevant concept in this lab's Phase 7 map and build it.

**How to use this map**

1. Finish Phases 1–5 for **Production** rows — you learn by shipping real behavior.
2. Pick one **Exercise** row per month in Phase 7 — small PR, one pattern.
3. Write an **ADR** for every **Theory** row you care about — 1–2 pages in
   `docs/architecture/decisions/`.
4. After an exercise, add a line to the Phase 7 pattern table above if it
   worked well as a repeatable lab.

**No exit criteria.** This phase never ends — it's your ongoing lab.

---

## Progression summary

```
Phase 1:  Foundation    ← Hexagonal API + JWT + Docker + DESIGN.md (Stitch)
Phase 2:  Core         ← Extract Worker + real execution + analytics + HTTPS
Phase 3:  Intelligence ← AI failure analysis (API calls to Claude/OpenAI)
Phase 4:  SaaS         ← OAuth/SSO + API tokens + audit log + OWASP audit
Phase 4B: Payments     ← Stripe + subscriptions + webhooks + plan enforcement
Phase 5:  Cloud        ← AKS + Terraform + TLS 1.3 + Key Vault + pen testing
Phase 6:  AI Agent     ← RAG + LangChain + vector DB + tool-use agents
Phase 7:  Playground   ← System design patterns + k6 load tests, never ends
```

Each phase builds on the previous one. Don't skip ahead — the foundation
must be solid before adding intelligence.

**But remember:** you can always add skills, edit ARCHITECTURE.md, update
rules, and evolve the project structure at any point. These files are
living documents. Improve them as you learn.
