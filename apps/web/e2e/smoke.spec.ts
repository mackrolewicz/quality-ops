import { expect, test } from "@playwright/test";

test("owner can log in and drive a suite through to results", async ({
  page,
}) => {
  const stamp = Date.now();
  const projectName = `Smoke ${stamp}`;

  await page.goto("/login");
  await page.getByTestId("login-email").fill("owner@demo.com");
  await page.getByTestId("login-password").fill("password123");
  await page.getByTestId("login-submit").click();

  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByTestId("nav-dashboard")).toBeVisible();

  await page.getByTestId("nav-projects").click();
  await page.getByTestId("new-project").click();
  await page.getByTestId("project-name").fill(projectName);
  await page.getByTestId("project-submit").click();

  await page
    .getByTestId("project-card")
    .filter({ hasText: projectName })
    .click();

  // Environments
  await page.getByTestId("tab-environments").click();
  await page.getByTestId("add-environment").click();
  await page.getByTestId("env-name").fill("Local");
  await page.getByTestId("env-baseurl").fill("http://localhost:3000");
  await page.getByTestId("env-type").selectOption("DEV");
  await page.getByTestId("env-submit").click();
  await expect(page.getByTestId("env-row")).toHaveCount(1);

  // Suites
  await page.getByTestId("tab-suites").click();
  await page.getByTestId("add-suite").click();
  await page.getByTestId("suite-name").fill("API smoke");
  await page.getByTestId("suite-type").selectOption("API");
  await page.getByTestId("suite-submit").click();

  await page.getByTestId("suite-row").first().click();
  await page.getByTestId("add-case").click();
  await page.getByTestId("case-name").fill("health check");
  await page.getByTestId("case-submit").click();
  await expect(page.getByTestId("case-row")).toHaveCount(1);

  // Back to the project and trigger a run
  await page.getByRole("link", { name: projectName, exact: true }).click();
  await page.getByTestId("tab-runs").click();
  await page.getByTestId("trigger-run").click();
  await page.getByTestId("run-suite-select").selectOption({ label: "API smoke" });
  await page.getByTestId("run-env-select").selectOption({ label: "Local" });
  await page.getByTestId("run-submit").click();

  await expect(page).toHaveURL(/\/runs\/[0-9a-f-]+$/);
  await expect(page.getByTestId("run-status")).toHaveText(/PASSED|FAILED/, {
    timeout: 15000,
  });
  await expect(page.getByTestId("result-row")).toHaveCount(1);
});
