import { render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";

import { RepositoryConnectionForm } from "../../../src/features/projects/RepositoryConnectionForm";

const createMutate = vi.fn();
const updateMutate = vi.fn();

vi.mock("../../../src/api/repositories", () => ({
  useCreateRepositoryConnection: () => ({
    mutate: createMutate,
    isPending: false,
    isError: false,
    error: null,
  }),
  useUpdateRepositoryConnection: () => ({
    mutate: updateMutate,
    isPending: false,
    isError: false,
    error: null,
  }),
}));

beforeEach(() => {
  createMutate.mockReset();
  updateMutate.mockReset();
});

describe("RepositoryConnectionForm", () => {
  it("submits a trimmed create payload with an undefined host/credentialRef when blank", async () => {
    render(
      <RepositoryConnectionForm
        projectId="proj-1"
        onCancel={vi.fn()}
        onDone={vi.fn()}
      />,
    );

    await userEvent.type(screen.getByTestId("repo-owner"), "  acme  ");
    await userEvent.type(screen.getByTestId("repo-name"), "  web-app  ");
    await userEvent.click(screen.getByTestId("repo-submit"));

    expect(createMutate).toHaveBeenCalledTimes(1);
    expect(createMutate.mock.calls[0][0]).toEqual({
      provider: "GITHUB",
      host: undefined,
      ownerPath: "acme",
      repoName: "web-app",
      defaultRef: "main",
      credentialRef: undefined,
    });
  });

  it("rejects a credentialRef that doesn't match [A-Z0-9_]{1,64}", async () => {
    render(
      <RepositoryConnectionForm
        projectId="proj-1"
        onCancel={vi.fn()}
        onDone={vi.fn()}
      />,
    );

    await userEvent.type(screen.getByTestId("repo-owner"), "acme");
    await userEvent.type(screen.getByTestId("repo-name"), "web-app");
    await userEvent.type(screen.getByTestId("repo-credential-ref"), "not-valid!");
    await userEvent.click(screen.getByTestId("repo-submit"));

    expect(
      await screen.findByText("Must match [A-Z0-9_]{1,64}"),
    ).toBeInTheDocument();
    expect(createMutate).not.toHaveBeenCalled();
  });

  it("disables the provider select when editing an existing connection", () => {
    render(
      <RepositoryConnectionForm
        projectId="proj-1"
        connection={{
          id: "conn-1",
          projectId: "proj-1",
          provider: "GITLAB",
          host: "gitlab.com",
          ownerPath: "acme",
          repoName: "web-app",
          defaultRef: "main",
          credentialRef: null,
          createdAt: "2026-01-01T00:00:00Z",
          updatedAt: "2026-01-01T00:00:00Z",
        }}
        onCancel={vi.fn()}
        onDone={vi.fn()}
      />,
    );

    expect(screen.getByTestId("repo-provider")).toBeDisabled();
  });
});
