import { expect, test } from "@playwright/test";

// ADR-009 §11 — connect a repository, author a repo test case via the case
// editor's "Repository" tab, and run it through the EXISTING suite Run-now
// flow (gap #1 — there is no "run now from a connection"). Requires the WP9
// compose network topology (docker-proxy + qualityops-runner-egress) to be up
// so the Worker can actually launch the checkout/framework containers;
// coordinated with WP12, not run against a live stack here.
test("owner can connect a repository, author a repo case, and run it", async ({
  page,
}) => {
  const stamp = Date.now();
  const projectName = `Repo E2E ${stamp}`;

  await page.goto("/login");
  await page.getByTestId("login-email").fill("owner@demo.com");
  await page.getByTestId("login-password").fill("password123");
  await page.getByTestId("login-submit").click();

  await expect(page).toHaveURL(/\/$/);

  await page.getByTestId("nav-projects").click();
  await page.getByTestId("new-project").click();
  await page.getByTestId("project-name").fill(projectName);
  await page.getByTestId("project-submit").click();

  await page
    .getByTestId("project-card")
    .filter({ hasText: projectName })
    .click();

  // Connect a repository.
  await page.getByTestId("tab-repositories").click();
  await page.getByTestId("add-repository-connection").click();
  await page.getByTestId("repo-owner").fill("octocat");
  await page.getByTestId("repo-name").fill("Hello-World");
  await page.getByTestId("repo-default-ref").fill("master");
  await page.getByTestId("repo-submit").click();
  await expect(page.getByTestId("repo-connection-row")).toHaveCount(1);

  // Test the connection (outbound probe).
  await page.getByTestId("repo-test-connection").click();
  await expect(page.getByTestId("repo-test-result")).toBeVisible({
    timeout: 15000,
  });

  // An environment is still required to trigger a run.
  await page.getByTestId("tab-environments").click();
  await page.getByTestId("add-environment").click();
  await page.getByTestId("env-name").fill("Local");
  await page.getByTestId("env-baseurl").fill("http://localhost:3000");
  await page.getByTestId("env-type").selectOption("DEV");
  await page.getByTestId("env-submit").click();
  await expect(page.getByTestId("env-row")).toHaveCount(1);

  // Author a repo test case through the case editor's "Repository" tab.
  await page.getByTestId("tab-suites").click();
  await page.getByTestId("add-suite").click();
  await page.getByTestId("suite-name").fill("Repo suite");
  await page.getByTestId("suite-type").selectOption("API");
  await page.getByTestId("suite-submit").click();

  await page.getByTestId("suite-row").first().click();
  await page.getByTestId("add-case").click();
  await page.getByTestId("case-name").fill("pytest smoke");
  await page.getByTestId("case-tab-repository").click();
  await page
    .getByTestId("repo-test-connection")
    .selectOption({ label: "octocat/Hello-World" });
  await page.getByTestId("repo-test-ref").fill("master");
  await page.getByTestId("repo-test-framework").selectOption("PYTEST");
  await page
    .getByTestId("repo-test-command")
    .fill("pytest --junitxml=report.xml");
  await page.getByTestId("repo-test-report-paths").fill("report.xml");
  await page.getByTestId("case-submit").click();
  await expect(page.getByTestId("case-row")).toHaveCount(1);

  // Trigger the suite through the existing Run-now flow (no ad-hoc repo-run
  // endpoint — the repo case rides the normal suite run).
  await page.getByRole("link", { name: projectName, exact: true }).click();
  await page.getByTestId("tab-runs").click();
  await page.getByTestId("trigger-run").click();
  await page.getByTestId("run-suite-select").selectOption({ label: "Repo suite" });
  await page.getByTestId("run-env-select").selectOption({ label: "Local" });
  await page.getByTestId("run-submit").click();

  await expect(page).toHaveURL(/\/runs\/[0-9a-f-]+$/);
  await expect(page.getByTestId("run-status")).toHaveText(
    /PASSED|FAILED/,
    { timeout: 120000 },
  );

  // Repository execution provenance + parsed test items.
  await expect(page.getByTestId("repository-execution-panel")).toBeVisible();
  await expect(page.getByTestId("repository-commit-sha")).not.toBeEmpty();
  await expect(page.getByTestId("repository-test-items-table")).toBeVisible();
  await expect(page.getByTestId("repository-item-row").first()).toBeVisible();
});
