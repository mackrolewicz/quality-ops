---
name: system-design
description: Use this skill when making architecture decisions, designing new modules, evaluating trade-offs, or writing Architecture Decision Records (ADRs). Covers system design principles, patterns, and the decision-making process for this project.
---

# System design patterns

This skill covers how architecture decisions are made and documented
in this project.

## 1. Architecture Decision Records (ADRs)

Every significant decision gets documented as an ADR in
`docs/architecture/decisions/`.

### When to write an ADR
- Adding a new technology or dependency.
- Changing how modules communicate.
- Adding a new domain module.
- Changing the data model in a non-trivial way.
- Choosing between two reasonable approaches.

### When NOT to write an ADR
- Fixing a bug.
- Adding a CRUD endpoint that follows existing patterns.
- Routine dependency updates.

### ADR format

```markdown
# ADR-NNN: <title>

## Status
Proposed | Accepted | Deprecated | Superseded by ADR-NNN

## Context
<What situation or problem led to this decision? What constraints exist?>

## Decision
<What did we decide? Be specific — include the technology, pattern, or
approach chosen.>

## Consequences

### Positive
- <benefit 1>
- <benefit 2>

### Negative
- <trade-off 1>
- <trade-off 2>

### Risks
- <what could go wrong>

## Alternatives considered

### Option A: <name>
- Pros: ...
- Cons: ...

### Option B: <name>
- Pros: ...
- Cons: ...
```

Number ADRs sequentially: `001-initial-architecture.md`,
`002-kafka-for-orchestration.md`, etc.

## 2. Module design principles

This project follows the **modular monolith** pattern:

### Boundary rules
- Each module owns its data (tables). No module reads another module's tables directly.
- Modules expose services (Java interfaces) for cross-module communication.
- Modules can publish and consume Kafka events for async communication.
- DTOs (records) cross boundaries. JPA entities never cross boundaries.

### When to create a new module
- The domain concept has its own lifecycle (CRUD operations).
- It has its own database tables.
- It could theoretically be a separate service.

### When NOT to create a new module
- It's just a utility or helper (put it in a `shared` package).
- It only has one class.
- It's tightly coupled to an existing module and has no independent lifecycle.

## 3. API design principles

### REST conventions
- Use nouns for resources, not verbs: `/projects`, not `/getProjects`.
- Use plural nouns: `/projects`, not `/project`.
- Nest when there's a clear parent-child: `/projects/{id}/runs`.
- Version the API: `/api/v1/...`.
- Use standard HTTP methods and status codes.

### Response format

```json
{
  "data": { ... },
  "meta": {
    "page": 1,
    "pageSize": 20,
    "total": 142
  }
}
```

Error response:
```json
{
  "error": {
    "code": "PROJECT_NOT_FOUND",
    "message": "Project with id 550e8400-... not found",
    "details": []
  }
}
```

### Pagination
- Use offset-based pagination for simple lists: `?page=1&size=20`.
- Use cursor-based pagination for real-time feeds (run results stream).

## 4. Data model principles

### Multi-tenancy
Every table has `org_id` (directly or through a parent FK chain). Every
query filters by `org_id`. No exceptions.

### IDs
Use UUIDs (v7 for time-sortable, v4 otherwise). Never expose sequential
IDs — they leak information about entity counts.

### Timestamps
- `created_at` — set once, never updated (use `@PrePersist`).
- `updated_at` — updated on every change (use `@PreUpdate`).
- Store as `TIMESTAMPTZ` in Postgres, `Instant` in Java.

### Soft deletes
Use `deleted_at TIMESTAMPTZ NULL` instead of hard deletes for
audit-sensitive entities (orgs, users, projects). Add a partial index
`WHERE deleted_at IS NULL` for query performance.

## 5. Event-driven design principles

### Event naming
- Commands (caller wants something done): `run.requested`
- Facts (something happened): `run.completed`, `user.created`
- Use past tense for facts, imperative for commands.

### Event payload rules
- Self-contained: consumer doesn't need to call back for context.
- Include `orgId` for tenant isolation.
- Include timestamps.
- Use UUIDs, not entity references.
- Events are immutable — never change a published event schema.

### Schema evolution
- Add new fields (with defaults) — safe.
- Remove fields — unsafe, requires versioning.
- Change field types — never do this. Add a new field instead.

## 6. Domain modeling

Model the business domain explicitly. Avoid anemic models where entities are
just bags of getters/setters and all logic lives in services.

### Building blocks

| Block | Rule | Example |
|---|---|---|
| Entity | Has identity (ID) and a lifecycle | `TestRun`, `Project`, `Organization` |
| Value object | Immutable, no identity, validated on construction | `Email`, `Slug`, `Tag` |
| Aggregate root | Owns child entities; the only entry point for changes | `TestRun` owns `TestResult`s |
| Domain event | A fact that happened, named in past tense | `RunCompleted` |
| Invariant | A rule that must always hold | A run cannot complete before it starts |

### Rules

- Mutate child entities only through the aggregate root — never save a
  `TestResult` directly without going through its `TestRun`.
- Put behavior on the entity: `run.start()`, `run.markCompleted(results)` —
  not `run.setStatus(RUNNING)` from a service.
- Validate invariants inside the entity, so illegal states are impossible.
- Use the same names everywhere (ubiquitous language): code, DB, API, UI.

### State machines (statuses)

Any field called `status` is a state machine. Model it explicitly:

```java
public enum RunStatus {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED;

    private static final Map<RunStatus, Set<RunStatus>> ALLOWED = Map.of(
        PENDING,   EnumSet.of(RUNNING, CANCELLED),
        RUNNING,   EnumSet.of(COMPLETED, FAILED, CANCELLED),
        COMPLETED, EnumSet.noneOf(RunStatus.class),
        FAILED,    EnumSet.noneOf(RunStatus.class),
        CANCELLED, EnumSet.noneOf(RunStatus.class));

    public boolean canTransitionTo(RunStatus next) {
        return ALLOWED.get(this).contains(next);
    }
}
```

The entity enforces it; illegal transitions throw, surfacing as `409 Conflict`:

```java
public void markRunning() {
    if (!status.canTransitionTo(RunStatus.RUNNING)) {
        throw new IllegalStateTransitionException(status, RunStatus.RUNNING);
    }
    this.status = RunStatus.RUNNING;
}
```

Other state machines in this project:
- **Test result:** `PASSED / FAILED / SKIPPED / FLAKY`
- **Subscription (Phase 4B):** `trialing → active → past_due → canceled → expired`
- **Payment (Phase 4B):** `requires_payment → processing → succeeded / failed`

Unit-test every illegal transition. Keep an append-only audit of who changed
status and when (Phase 4).

### Transactions and data integrity

- One command = one `@Transactional` unit of work. Keep transactions short;
  never make HTTP/Kafka calls while holding a DB transaction open.
- Save to DB first, then publish the event (transactional outbox later if
  exactly-once matters).
- Concurrency: use optimistic locking (`@Version`) on entities that can be
  updated concurrently (e.g. `TestRun`); a conflict surfaces as `409` and the
  caller retries.
- Deduplication: enforce a unique constraint (e.g. `(org_id, idempotency_key)`).
  Combined with at-least-once Kafka delivery and idempotent consumers, this
  gives effectively-once processing.

## 7. Failure mode analysis

Before shipping any new integration, ask:

| Question | Example answer |
|---|---|
| What happens if Postgres is slow? | Requests timeout, users see errors |
| What happens if Redis is down? | Cache misses, fall back to Postgres |
| What happens if Kafka is down? | Runs stay in PENDING, retry when Kafka recovers |
| What happens if the worker crashes mid-run? | Run stays in RUNNING, need a timeout/reaper |
| What happens if the same event is processed twice? | Must be idempotent — check-then-act |
| What happens if a migration fails halfway? | Flyway marks it as failed, manual intervention needed |

## 8. Decision-making framework

When choosing between approaches:

1. **Reversibility** — Prefer reversible decisions. If a choice is easy to undo,
   make it fast. If it's hard to undo (database schema, public API), think longer.
2. **Boring technology** — Prefer well-known, well-documented tools over novel ones.
   Novel is fun for a weekend; boring is reliable for a year.
3. **Delay decisions** — If you don't need to decide now, don't. Gather more
   information first. But don't delay so long that you build on shaky assumptions.
4. **Single-tenant first** — Build for one tenant with multi-tenant hooks. Don't
   build the full multi-tenant system until you need it.
5. **Modular first** — Keep modules separate so you can extract services later.
   But don't extract until you must — a modular monolith is simpler to operate.
