---
name: api-testing
description: Use this skill when writing API tests, E2E tests with Playwright, or integration tests that hit real endpoints. Covers REST API testing patterns, Playwright conventions, test data management, and MCP integration.
---

# API + E2E testing patterns

This skill covers testing the platform at the API and browser level.

## 1. API testing layers

| Layer | Tool | Scope | Speed |
|---|---|---|---|
| Controller tests | MockMvc | Single endpoint, mocked service | Fast |
| Integration tests | Testcontainers + REST | Full stack with real DB/Kafka | Medium |
| Contract tests | Spring Cloud Contract | API compatibility | Fast |
| E2E tests | Playwright | Full browser flow | Slow |

## 2. Controller tests (MockMvc)

Test HTTP layer in isolation:

```java
@WebMvcTest(ProjectController.class)
class ProjectControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProjectService projectService;

    @Test
    void listProjects_returnsOk() throws Exception {
        when(projectService.listByOrg(any()))
            .thenReturn(List.of(sampleProject()));

        mockMvc.perform(get("/api/v1/projects")
                .with(jwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Test Project"));
    }

    @Test
    void createProject_withInvalidInput_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }
}
```

## 3. Integration tests (Testcontainers)

Test the full stack with real infrastructure:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RunExecutionIT {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void triggerRun_publishesKafkaEvent_workerProcesses() {
        // Create project
        var project = restTemplate.postForObject("/api/v1/projects",
            new CreateProjectRequest("Test"), ProjectResponse.class);

        // Trigger run
        var run = restTemplate.postForObject(
            "/api/v1/projects/" + project.id() + "/runs",
            new CreateRunRequest(suiteId, envId), RunResponse.class);

        // Verify run completes (poll or use Awaitility)
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            var status = restTemplate.getForObject("/api/v1/runs/" + run.id(), RunResponse.class);
            return status.status().equals("COMPLETED");
        });
    }
}
```

## 4. Playwright E2E tests

### Setup

```typescript
// playwright.config.ts
import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  baseURL: "http://localhost:5173",
  use: {
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "chromium", use: { browserName: "chromium" } },
  ],
  webServer: {
    command: "npm run dev",
    port: 5173,
    reuseExistingServer: true,
  },
});
```

### Test patterns

```typescript
import { test, expect } from "@playwright/test";

test.describe("Test Runs", () => {
  test("user can trigger a test run and see results", async ({ page }) => {
    await page.goto("/projects");
    await page.click("text=My Project");
    await page.click("button:has-text('Run Tests')");

    await expect(page.locator("[data-testid='run-status']"))
      .toHaveText("RUNNING");

    // Wait for completion (polling)
    await expect(page.locator("[data-testid='run-status']"))
      .toHaveText("COMPLETED", { timeout: 60000 });

    // Check results
    await expect(page.locator("[data-testid='pass-count']"))
      .toBeVisible();
  });
});
```

### Playwright conventions
- Use `data-testid` attributes for test selectors — not CSS classes.
- Use page object pattern for complex flows:

```typescript
class ProjectPage {
  constructor(private page: Page) {}

  async triggerRun(suiteName: string) {
    await this.page.click(`text=${suiteName}`);
    await this.page.click("button:has-text('Run')");
  }

  async waitForRunCompletion(timeout = 60000) {
    await expect(this.page.locator("[data-testid='run-status']"))
      .toHaveText(/COMPLETED|FAILED/, { timeout });
  }
}
```

## 5. Playwright MCP integration

The Playwright MCP server allows AI agents to run and interact with
browser-based tests. Use it for:

- Verifying UI changes after implementation.
- Running E2E tests from Claude Code.
- Debugging visual issues by taking screenshots.

## 6. Test data management

### Factories for test data

```java
public class TestFixtures {
    public static Project sampleProject(UUID orgId) {
        var project = new Project();
        project.setOrgId(orgId);
        project.setName("Test Project " + UUID.randomUUID().toString().substring(0, 8));
        return project;
    }

    public static TestSuite sampleSuite(UUID projectId) {
        var suite = new TestSuite();
        suite.setProjectId(projectId);
        suite.setName("Regression Suite");
        suite.setType(TestType.API);
        return suite;
    }
}
```

### Database reset between tests

```java
@BeforeEach
void cleanUp() {
    // Delete in reverse dependency order
    resultRepository.deleteAll();
    runRepository.deleteAll();
    suiteRepository.deleteAll();
    projectRepository.deleteAll();
}
```

Or use `@Transactional` on test classes to auto-rollback.

## 7. What to test at each layer

| Concern | Controller test | Integration test | E2E test |
|---|---|---|---|
| Input validation | Yes | No | No |
| Auth / RBAC | Yes | Yes | Yes |
| Business logic | No (mocked) | Yes | Indirectly |
| DB queries | No | Yes | Indirectly |
| Kafka events | No | Yes | No |
| UI rendering | No | No | Yes |
| Full user flow | No | No | Yes |
