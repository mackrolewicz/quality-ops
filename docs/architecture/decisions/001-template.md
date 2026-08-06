# ADR-001: Architecture Decision Record Template

## Status
Accepted

## Context
We need a consistent format for documenting architecture decisions in this
project. As the system grows (Spring Boot API, React frontend, Kafka, Redis,
Postgres, Docker, Kubernetes), decisions will compound and context will be
lost unless we write them down.

## Decision
Use this ADR format for all significant architecture decisions. Store ADRs
in `docs/architecture/decisions/` with sequential numbering.

### When to write an ADR
- Adding a new technology or dependency
- Changing how modules communicate
- Adding a new domain module
- Changing the data model in a non-trivial way
- Choosing between two reasonable approaches

### ADR template (copy this for new ADRs)

```markdown
# ADR-NNN: <title>

## Status
Proposed | Accepted | Deprecated | Superseded by ADR-NNN

## Context
<What situation or problem led to this decision? What constraints exist?>

## Decision
<What did we decide? Be specific.>

## Consequences

### Positive
- ...

### Negative
- ...

### Risks
- ...

## Alternatives considered

### Option A: <name>
- Pros: ...
- Cons: ...

### Option B: <name>
- Pros: ...
- Cons: ...
```

## Consequences

### Positive
- Decisions are documented and searchable.
- New contributors can understand why things are the way they are.
- We can revisit decisions with full context.

### Negative
- Small overhead for each decision.

### Risks
- People skip writing ADRs. Mitigation: the reviewer subagent checks for
  missing ADRs on structural changes.
