# MVP — QualityOps Lab

The Minimum Viable Product. Ship this before anything else.

## Goal
A working QA platform where a user can create a project, register an
environment, define a test suite, trigger a test run, and see results
on a dashboard.

## MVP scope

### Must have (Phase 1)

| Feature | Backend | Frontend | Notes |
|---|---|---|---|
| **Login** | Spring Security + JWT | Login page | Single org, hardcoded users OK |
| **Create project** | CRUD API | Project list + create form | |
| **Register environment** | CRUD API | Environment list per project | name, URL, status |
| **Create test suite** | CRUD API | Suite list per project | name, type (API/UI), tags |
| **Create test cases** | CRUD API | Case list per suite | name, priority |
| **Trigger test run** | POST endpoint → Kafka event | "Run Tests" button | |
| **Worker executes run** | Kafka consumer, simulated execution | — | Simulate results first |
| **Results dashboard** | GET run results | Results table: pass/fail/skip | |
| **Run history** | GET run list per project | Run list with status + duration | |
| **Docker Compose** | All infra | — | Postgres + Redis + Kafka |
| **CI pipeline** | GitHub Actions | — | Lint + test + build |

### Explicitly NOT in MVP
- Multi-tenancy (single org is fine)
- Real Playwright execution (simulate first)
- AI features
- Kubernetes / cloud deployment
- SSO / OAuth
- Flaky test detection
- Test data management
- Mocking / dependency virtualization

## Acceptance criteria

### Project management
- [ ] User can create a project with a name and description.
- [ ] User can list all projects.
- [ ] User can view a single project with its environments and suites.

### Environment registry
- [ ] User can register an environment (name, URL) for a project.
- [ ] User can list environments for a project.

### Test catalog
- [ ] User can create a test suite for a project.
- [ ] User can add test cases to a suite.
- [ ] User can list suites and cases.

### Test execution
- [ ] User can trigger a run for a suite + environment.
- [ ] Run appears with status PENDING immediately.
- [ ] Worker picks up the run via Kafka and sets status to RUNNING.
- [ ] Worker simulates test execution (random pass/fail per case).
- [ ] Worker publishes results back via Kafka.
- [ ] Run status updates to COMPLETED or FAILED.

### Results dashboard
- [ ] User can view run results: list of test cases with pass/fail status.
- [ ] User can see total pass/fail/skip counts.
- [ ] User can see run duration.

### Infrastructure
- [ ] `docker compose up` starts Postgres, Redis, Kafka, API, Worker, Gateway.
- [ ] Frontend dev server connects through gateway.
- [ ] GitHub Actions CI runs lint + test + build on PR.

## Technical decisions for MVP

| Decision | Choice | Why |
|---|---|---|
| Auth | Spring Security + JWT, hardcoded users | Simplest that works |
| Test execution | Simulated (random results) | Real runners come in Phase 2 |
| Real-time updates | Polling (5s interval) | WebSocket comes later |
| Gateway | Spring Cloud Gateway | Already in the stack plan |
| Database | PostgreSQL with Flyway | Production-grade from day one |

## Definition of done

The MVP is done when:
1. All acceptance criteria pass.
2. CI pipeline is green.
3. `docker compose up` gives you a working platform in under 2 minutes.
4. A new developer can follow `docs/runbooks/local-dev-setup.md` and be
   running in 15 minutes.
