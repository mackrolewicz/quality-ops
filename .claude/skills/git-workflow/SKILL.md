---
name: git-workflow
description: Use this skill when making commits, creating branches, writing commit messages, or managing PRs. Covers the project's Git conventions, branching strategy, and commit message format.
---

# Git workflow conventions

This skill defines how version control is used in this project.

## 1. Branching strategy

```
main                  ← always deployable, never commit directly
├── feat/<name>       ← new features
├── fix/<name>        ← bug fixes
├── refactor/<name>   ← restructuring without behavior change
├── infra/<name>      ← infrastructure, CI/CD, Docker changes
├── test/<name>       ← test-only changes
└── docs/<name>       ← documentation only
```

Branch names: lowercase, hyphens, short.
Good: `feat/run-orchestration`, `infra/docker-compose`, `fix/flaky-test-scoring`.
Bad: `Feature_Add_Run_Orchestration_To_Platform`.

## 2. Commit message format

```
<type>(<scope>): <what changed> (imperative mood, ≤72 chars)

<optional body — why the change was made, not what>
```

**Types**: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `infra`

**Scopes** (optional, match app/module):
`api`, `web`, `worker`, `gateway`, `infra`, `ci`,
`identity`, `project`, `execution`, `result`, `testsuite`

Good examples:
```
feat(execution): add run orchestration via Kafka events
fix(api): filter projects by orgId to prevent tenant data leak
refactor(web): extract RunStatus component from RunDetail page
test(api): add integration tests for project CRUD with Testcontainers
infra: add Redis and Kafka to docker-compose
docs: add ADR for choosing Kafka over RabbitMQ
chore: update Spring Boot to 3.3.0
```

Bad examples:
```
Updated stuff                           ← vague
fix bug                                 ← which bug?
WIP                                     ← don't commit WIP to shared branches
feat: Add Run Orchestration Feature     ← don't capitalize after type
```

## 3. When to commit

- **One logical change per commit.** Don't mix a feature with a refactor.
- **Tests go with the code they test** — in the same commit.
- **Database migrations go with the code that uses them** — same commit.
- **Don't commit broken code** to `main`. Run `./mvnw compile` and
  `npm run typecheck` at minimum.
- **Don't commit generated files** — `target/`, `node_modules/`, `dist/`
  belong in `.gitignore`.

## 4. Pull request conventions

PR title follows the same format as commit messages:
```
feat(execution): add run orchestration via Kafka events
```

PR body template:
```markdown
## What
<one paragraph describing the change>

## Why
<motivation — what problem does this solve?>

## Affected layers
- [ ] Backend API
- [ ] Worker
- [ ] Gateway
- [ ] Frontend
- [ ] Database migration
- [ ] Infrastructure / CI

## How to test
<steps to verify the change works>

## Checklist
- [ ] Tests pass (`./mvnw verify` and `npm test`)
- [ ] No new linter warnings
- [ ] Multi-tenancy: orgId enforced on new queries
- [ ] ARCHITECTURE.md updated if structure changed
- [ ] ADR created if significant decision made
```

## 5. Monorepo considerations

This is a monorepo with multiple apps. When making changes:

- Prefix commit scopes with the app/module: `feat(api):`, `feat(web):`.
- If a change spans multiple apps (e.g., new API endpoint + frontend page),
  use a broader scope or no scope: `feat: add environment registry`.
- PRs that touch both backend and frontend are fine — they ship together.
- CI runs all checks regardless of which files changed (for now). Later,
  add path-based filtering.

## 6. .gitignore essentials

```gitignore
# Java
target/
*.class
*.jar
.idea/
*.iml

# Node
node_modules/
dist/
.env.local

# General
.env
*.log
.DS_Store
Thumbs.db

# Test output
.pytest_cache/
.coverage
htmlcov/
test-results/
playwright-report/

# Docker
docker-compose.override.yml
```

## 7. Rules

- Never force-push to `main`.
- Never commit secrets, API keys, or `.env` files.
- Rebase feature branches on `main` before merging (keeps history clean).
- Delete branches after merge.
- Squash-merge PRs with a clean commit message.
- Use conventional commit format — it enables automated changelogs later.
