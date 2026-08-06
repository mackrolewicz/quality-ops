---
name: code-review
description: Use this skill when reviewing code changes, whether as the reviewer subagent or when the user asks for feedback on their code. Defines what to look for across Java, TypeScript, and infrastructure code.
---

# Code review guide

This skill defines how code reviews are conducted in this project.

## 1. Review priorities (in order)

1. **Security** — Multi-tenancy leaks? SQL injection? XSS? Secrets in logs?
2. **Correctness** — Does it work? Edge cases? Null handling?
3. **Data integrity** — Migration safe? Constraints enforced? Idempotent?
4. **Architecture** — Follows module boundaries? Correct layer for the logic?
5. **Error handling** — Fails gracefully? Meaningful error messages?
6. **Performance** — N+1 queries? Missing indexes? Unbounded result sets?
7. **Readability** — Will someone understand this in 6 months?
8. **Style** — Only if it contradicts the project's coding standards.

## 2. Severity levels

| Level | Meaning | Action |
|---|---|---|
| 🔴 **Must fix** | Security issue, data leak, multi-tenancy violation, broken logic | Blocks merge |
| 🟡 **Should fix** | Missing error handling, unclear naming, missing test | Fix before next release |
| 💡 **Suggestion** | Alternative approach, minor improvement | Author's call |

## 3. What to look for — Java (Spring Boot)

| Check | Good | Bad |
|---|---|---|
| DI style | Constructor injection | `@Autowired` on field |
| DTOs | Records for request/response | Exposing JPA entities |
| Exceptions | Specific: `ProjectNotFoundException` | Generic: `catch (Exception e)` |
| Logging | SLF4J with context: `log.info("Run {}", runId)` | `System.out.println` |
| Queries | Filter by `orgId` always | Raw query without tenant filter |
| Migrations | Append-only, versioned | Editing existing migration |
| Generics | Typed: `List<Project>` | Raw: `List` |
| Nulls | `Optional<>` return, `@NotNull` params | Returning null from service |

## 4. What to look for — TypeScript (React)

| Check | Good | Bad |
|---|---|---|
| Types | Explicit interfaces, no `any` | `any`, type assertions everywhere |
| Server state | TanStack Query | Manual `useEffect` + `fetch` |
| Styling | Tailwind CSS | CSS modules, inline `style={}` |
| Components | Small, functional, named exports | Large, class-based, default export |
| Error handling | Error boundary + inline error states | Unhandled promise rejections |
| Forms | React Hook Form | Manual state per field |

## 5. What to look for — Infrastructure

| Check | Good | Bad |
|---|---|---|
| Dockerfiles | Multi-stage, non-root, healthcheck | Single stage, runs as root |
| Compose | Health checks, named volumes | No health checks, anonymous volumes |
| CI | Caches dependencies, parallel jobs | No caching, sequential everything |
| Secrets | Environment variables, GitHub Secrets | Hardcoded in config files |
| K8s | Resource limits, probes | No limits, no probes |

## 6. Multi-tenancy review (CRITICAL)

Every review must verify:

- [ ] Every DB query filters by `orgId` (directly or through parent FK).
- [ ] Every API endpoint gets `orgId` from the authenticated user, not from
      the request body or URL.
- [ ] Kafka events include `orgId`.
- [ ] Redis keys are scoped by `orgId` where applicable.
- [ ] Error messages don't leak data from other tenants.
- [ ] Test coverage includes "tenant A can't access tenant B's data."

Multi-tenancy violations are always 🔴 Must fix.

## 7. How to give good feedback

**Be specific:**
```
🔴 ProjectService.java:42 — This query fetches projects without filtering
by orgId. Tenant A could see Tenant B's projects. Add .findByOrgId(orgId)
instead of .findAll().
```

**Not vague:**
```
🔴 Fix the security issue.
```

**Explain why, not just what:**
```
🟡 RunController.java:28 — Consider validating that the environment belongs
to the same project before triggering the run. Right now a user could trigger
a run with an environment from a different project, which would produce
confusing results.
```

**Acknowledge good work:**
```
Nice use of records for the DTOs — clean and immutable.
```

## 8. What NOT to do in reviews

- Don't rewrite the code in comments — suggest the direction.
- Don't bikeshed on style if it's consistent within the file.
- Don't request changes for personal preference.
- Don't approve without reading every changed line.
- Don't pile on — if there's a fundamental design issue, flag that first.
- Don't review generated code (migrations, lock files) line by line.

## 9. Hexagonal architecture review

When reviewing modules that use hexagonal architecture:

- [ ] **Dependency direction is inward.** Adapters depend on ports. Domain
      has no framework imports.
- [ ] **Ports are interfaces.** Input ports define use cases. Output ports
      define repository/gateway contracts.
- [ ] **Adapters are thin.** They convert between external formats and domain
      types. No business logic in adapters.
- [ ] **Domain entities are pure.** No `@Entity`, `@Column`, or Spring
      annotations on domain objects. JPA entities are separate in the
      persistence adapter.
- [ ] **Services implement input ports.** The service class has
      `implements TriggerRunUseCase`, not just methods.

## 10. Security review (load `security` skill for full reference)

**Always check on every review:**
- [ ] **Auth enforced** — endpoint has `@PreAuthorize` or is in security config
- [ ] **RBAC correct** — right role required for the operation
- [ ] **org_id from JWT** — never from request body or URL params
- [ ] **Input validated** — `@Valid`, `@NotBlank`, `@Size` on all inputs
- [ ] **No SQL injection** — parameterized queries only, no string concatenation
- [ ] **No XSS** — no `dangerouslySetInnerHTML`, CSP headers set
- [ ] **No secrets in logs** — tokens, passwords, keys never logged
- [ ] **Rate limiting** — expensive endpoints have per-operation limits
- [ ] **Error messages safe** — don't leak stack traces or tenant data

## 11. Full review checklist

### Java
- [ ] Constructor injection only
- [ ] Records for DTOs, entities for JPA
- [ ] Narrow exception handling
- [ ] SLF4J logging, no System.out
- [ ] Type-safe generics
- [ ] `orgId` filtered on all queries
- [ ] Hexagonal dependency direction correct (if applicable)
- [ ] `@PreAuthorize` on controller methods

### TypeScript
- [ ] Strict TypeScript, no `any`
- [ ] TanStack Query for server state
- [ ] Named exports
- [ ] Tailwind CSS
- [ ] Proper error handling
- [ ] No `dangerouslySetInnerHTML`

### General
- [ ] No secrets in code or logs
- [ ] Tests included for new logic
- [ ] Imports properly ordered
- [ ] No dead or commented-out code
- [ ] Multi-tenancy isolation verified
- [ ] Kafka events include orgId
- [ ] OWASP Top 10 items checked (see `security` skill)
