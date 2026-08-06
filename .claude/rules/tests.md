---
paths:
  - "**/*Test.java"
  - "**/*IT.java"
  - "**/*test*.ts"
  - "**/*test*.tsx"
  - "**/*spec*.ts"
  - "**/*spec*.tsx"
  - "**/e2e/**/*"
  - "**/tests/**/*"
  - "**/__tests__/**/*"
  - "**/conftest*"
  - "**/playwright*"
---
# Testing Rules

- Each test tests ONE concept. No multi-assertion monsters.
- Tests must be independent — no shared mutable state between tests.
- No real HTTP requests in unit tests. Mock everything external.
- Integration tests use Testcontainers (real Postgres, Kafka, Redis).
- Java unit test naming: `<Class>Test.java`. Integration: `<Class>IT.java`.
- Java test method naming: `methodName_condition_expectedResult`.
- Frontend: test what the user sees, not implementation details.
- Playwright: use `data-testid` attributes, page object pattern for complex flows.
- Always test multi-tenancy: verify tenant A cannot see tenant B's data.
- No snapshot tests in React — they break on every change.
- Test factories for test data. No copy-pasted objects.
