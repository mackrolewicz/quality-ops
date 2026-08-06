---
name: testing
description: Use this skill when writing, running, or organizing tests at any layer. Covers JUnit 5 for Java, Vitest for React, Playwright for E2E, Testcontainers for integration tests, and testing strategy decisions.
---

# Testing guide

This skill is the source of truth for how tests are written in this repo.

## 1. Testing pyramid

```
         /  E2E  \          ← few, slow, high confidence
        / Playwright \
       /──────────────\
      / Integration    \     ← moderate, real infra, catches wiring bugs
     / Testcontainers   \
    /────────────────────\
   /    Unit tests        \  ← many, fast, focused on logic
  / JUnit 5 + Vitest       \
 /──────────────────────────\
```

**Rule:** Most tests should be unit tests. Integration tests cover the
boundaries (DB, Kafka, Redis). E2E tests cover critical user flows only.

## 2. Backend testing (Java)

### Test locations

```
apps/api/src/test/java/com/qualityops/api/
├── project/
│   ├── ProjectServiceTest.java          # unit test
│   ├── ProjectControllerTest.java       # MockMvc test
│   └── ProjectIT.java                   # integration test
├── execution/
│   ├── RunServiceTest.java
│   └── RunExecutionIT.java
└── testutil/
    └── TestFixtures.java                # shared test data factories
```

### Naming conventions

| Type | Naming | Annotation |
|---|---|---|
| Unit test | `<Class>Test.java` | `@Test` |
| Integration test | `<Class>IT.java` | `@SpringBootTest` + `@Testcontainers` |
| Controller test | `<Class>ControllerTest.java` | `@WebMvcTest` |

### Unit tests (JUnit 5 + Mockito)

Test business logic in isolation:

```java
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {
    @Mock private ProjectRepository repository;
    @Mock private KafkaTemplate<String, Object> kafka;
    @InjectMocks private ProjectService service;

    @Test
    void create_savesProjectWithOrgId() {
        var request = new CreateProjectRequest("My Project", "desc");
        var orgId = UUID.randomUUID();

        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.create(request, orgId);

        assertThat(result.name()).isEqualTo("My Project");
        verify(repository).save(argThat(p -> p.getOrgId().equals(orgId)));
    }
}
```

### Integration tests (Testcontainers)

Test with real Postgres, Redis, and Kafka:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ProjectIT {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createAndRetrieveProject() {
        var created = restTemplate.postForObject("/api/v1/projects",
            new CreateProjectRequest("IT Test"), ProjectResponse.class);

        var retrieved = restTemplate.getForObject(
            "/api/v1/projects/" + created.id(), ProjectResponse.class);

        assertThat(retrieved.name()).isEqualTo("IT Test");
    }
}
```

### What to test at each layer

| Concern | Unit | MockMvc | Integration |
|---|---|---|---|
| Business logic | Yes | No | Indirectly |
| Validation | No | Yes | Yes |
| DB queries | No | No | Yes |
| Kafka events | No | No | Yes |
| Auth/RBAC | No | Yes | Yes |
| Error handling | Yes | Yes | Yes |

## 3. Frontend testing (Vitest + React Testing Library)

### Test locations

```
apps/web/tests/
├── components/
│   ├── Button.test.tsx
│   └── DataTable.test.tsx
├── features/
│   ├── projects/
│   │   └── ProjectList.test.tsx
│   └── runs/
│       └── RunDetail.test.tsx
└── hooks/
    └── useDebounce.test.ts
```

### Component tests

```typescript
import { render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { ProjectList } from "../../src/features/projects/ProjectList";

test("displays project names", () => {
  render(<ProjectList projects={[
    { id: "1", name: "Alpha", description: "" },
    { id: "2", name: "Beta", description: "" },
  ]} />);

  expect(screen.getByText("Alpha")).toBeInTheDocument();
  expect(screen.getByText("Beta")).toBeInTheDocument();
});
```

### Hook tests

```typescript
import { renderHook, act } from "@testing-library/react";
import { useDebounce } from "../../src/hooks/useDebounce";

test("debounces value updates", async () => {
  const { result, rerender } = renderHook(
    ({ value }) => useDebounce(value, 300),
    { initialProps: { value: "hello" } }
  );

  rerender({ value: "world" });
  expect(result.current).toBe("hello");

  await act(() => new Promise(r => setTimeout(r, 350)));
  expect(result.current).toBe("world");
});
```

### Conventions
- Test what the user sees, not implementation details.
- Use `screen.getByRole`, `screen.getByText` — avoid `getByTestId` for unit tests.
- Mock API calls with MSW (Mock Service Worker) or TanStack Query test utils.
- No snapshot tests — they break on every change and catch nothing.

## 4. E2E testing (Playwright)

See the `api-testing` skill for detailed Playwright patterns.

Key points:
- E2E tests live in `apps/web/e2e/`.
- Use `data-testid` attributes for selectors.
- Use page object pattern for complex flows.
- Only test critical user paths (login, create project, run tests, view results).
- Run in CI but don't block PRs (flaky risk is too high early on).

## 5. Running tests

```bash
# Backend unit tests
cd apps/api && ./mvnw test

# Backend integration tests (needs Docker for Testcontainers)
cd apps/api && ./mvnw verify

# Frontend tests
cd apps/web && npm test

# Frontend tests with coverage
cd apps/web && npm test -- --coverage

# E2E tests (needs running frontend + backend)
cd apps/web && npx playwright test

# All tests (CI-style)
cd apps/api && ./mvnw verify && cd ../web && npm test
```

## 6. Test quality checklist

- [ ] Each test tests one concept.
- [ ] Tests are independent — no shared mutable state.
- [ ] No real HTTP requests in unit tests.
- [ ] Integration tests use Testcontainers (real infra, not mocks).
- [ ] Test names describe the scenario: `create_withInvalidInput_returns400`.
- [ ] Fixtures/factories used for test data — no copy-pasted objects.
- [ ] No flaky assertions (avoid timing-dependent checks in unit tests).
- [ ] Multi-tenancy tested: verify tenant A can't see tenant B's data.
