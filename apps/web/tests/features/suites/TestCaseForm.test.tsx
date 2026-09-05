import { render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";

import { TestCaseForm } from "../../../src/features/suites/TestCaseForm";

const createMutate = vi.fn();
const updateMutate = vi.fn();

vi.mock("../../../src/api/cases", () => ({
  useCreateCase: () => ({
    mutate: createMutate,
    isPending: false,
    isError: false,
    error: null,
  }),
  useUpdateCase: () => ({
    mutate: updateMutate,
    isPending: false,
    isError: false,
    error: null,
  }),
}));

vi.mock("../../../src/api/repositories", () => ({
  useRepositoryConnections: () => ({
    data: {
      items: [
        {
          id: "conn-1",
          projectId: "proj-1",
          provider: "GITHUB",
          host: "github.com",
          ownerPath: "acme",
          repoName: "web-app",
          defaultRef: "main",
          credentialRef: null,
          createdAt: "2026-01-01T00:00:00Z",
          updatedAt: "2026-01-01T00:00:00Z",
        },
      ],
      meta: { page: 1, pageSize: 50, total: 1 },
    },
  }),
}));

beforeEach(() => {
  createMutate.mockReset();
  updateMutate.mockReset();
});

function renderForm() {
  return render(
    <TestCaseForm
      projectId="proj-1"
      suiteId="suite-1"
      nextOrderIndex={0}
      onCancel={vi.fn()}
      onDone={vi.fn()}
    />,
  );
}

describe("TestCaseForm", () => {
  it("submits a plain case with an undefined repoTest when no connection is selected", async () => {
    renderForm();
    await userEvent.type(screen.getByTestId("case-name"), "health check");
    await userEvent.click(screen.getByTestId("case-submit"));

    expect(createMutate).toHaveBeenCalledTimes(1);
    expect(createMutate.mock.calls[0][0].repoTest).toBeUndefined();
  });

  it("builds a repoTest payload once a connection is selected on the Repository tab", async () => {
    renderForm();
    await userEvent.type(screen.getByTestId("case-name"), "pytest suite");
    await userEvent.click(screen.getByTestId("case-tab-repository"));
    await userEvent.selectOptions(
      screen.getByTestId("repo-test-connection"),
      "conn-1",
    );
    await userEvent.type(screen.getByTestId("repo-test-ref"), "main");
    await userEvent.type(
      screen.getByTestId("repo-test-command"),
      "pytest --junitxml=report.xml",
    );
    await userEvent.click(screen.getByTestId("case-submit"));

    expect(createMutate).toHaveBeenCalledTimes(1);
    expect(createMutate.mock.calls[0][0].repoTest).toEqual({
      repositoryConnectionId: "conn-1",
      requestedRef: "main",
      framework: "PYTEST",
      workingDir: undefined,
      command: ["pytest", "--junitxml=report.xml"],
      reportFormat: "JUNIT_XML",
      reportPaths: undefined,
      resourceProfile: undefined,
      networkPolicy: undefined,
      timeoutSeconds: undefined,
    });
  });

  it("uses K6_SUMMARY_JSON when the K6 framework is selected", async () => {
    renderForm();
    await userEvent.type(screen.getByTestId("case-name"), "load test");
    await userEvent.click(screen.getByTestId("case-tab-repository"));
    await userEvent.selectOptions(
      screen.getByTestId("repo-test-connection"),
      "conn-1",
    );
    await userEvent.selectOptions(screen.getByTestId("repo-test-framework"), "K6");
    await userEvent.type(screen.getByTestId("repo-test-command"), "k6 run script.js");
    await userEvent.click(screen.getByTestId("case-submit"));

    expect(createMutate.mock.calls[0][0].repoTest.reportFormat).toBe(
      "K6_SUMMARY_JSON",
    );
  });
});
