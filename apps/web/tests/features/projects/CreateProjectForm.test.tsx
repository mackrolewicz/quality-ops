import { render, screen, waitFor } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";

import { CreateProjectForm } from "../../../src/features/projects/CreateProjectForm";

const mutateMock = vi.fn();

vi.mock("../../../src/api/projects", () => ({
  useCreateProject: () => ({
    mutate: mutateMock,
    isPending: false,
    isError: false,
    error: null,
  }),
}));

function renderForm() {
  return render(
    <CreateProjectForm onCreated={vi.fn()} onCancel={vi.fn()} />,
  );
}

beforeEach(() => {
  mutateMock.mockReset();
});

describe("CreateProjectForm", () => {
  it("auto-suggests a kebab-case slug from the name", async () => {
    renderForm();
    await userEvent.type(screen.getByTestId("project-name"), "My New App");
    await waitFor(() =>
      expect(screen.getByTestId("project-slug")).toHaveValue("my-new-app"),
    );
  });

  it("stops auto-filling the slug after a manual edit", async () => {
    renderForm();
    await userEvent.type(screen.getByTestId("project-slug"), "custom-slug");
    await userEvent.type(screen.getByTestId("project-name"), "Another Name");
    expect(screen.getByTestId("project-slug")).toHaveValue("custom-slug");
  });

  it("rejects an invalid slug pattern", async () => {
    renderForm();
    await userEvent.type(screen.getByTestId("project-name"), "Valid Name");
    await userEvent.clear(screen.getByTestId("project-slug"));
    await userEvent.type(screen.getByTestId("project-slug"), "Bad_Slug!");
    await userEvent.click(screen.getByTestId("project-submit"));

    expect(
      await screen.findByText(
        "Use lowercase letters, numbers and single hyphens",
      ),
    ).toBeInTheDocument();
    expect(mutateMock).not.toHaveBeenCalled();
  });

  it("submits a trimmed payload", async () => {
    renderForm();
    await userEvent.type(screen.getByTestId("project-name"), "  Checkout  ");
    await userEvent.type(
      screen.getByTestId("project-description"),
      "  billing flows  ",
    );
    await userEvent.clear(screen.getByTestId("project-slug"));
    await userEvent.type(screen.getByTestId("project-slug"), "checkout");
    await userEvent.click(screen.getByTestId("project-submit"));

    await waitFor(() => expect(mutateMock).toHaveBeenCalledTimes(1));
    expect(mutateMock.mock.calls[0][0]).toEqual({
      name: "Checkout",
      description: "billing flows",
      slug: "checkout",
    });
  });
});
