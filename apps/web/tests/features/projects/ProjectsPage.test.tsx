import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { ProjectsPage } from "../../../src/features/projects/ProjectsPage";
import { AppProviders, makeUser } from "../../testUtils";
import type { AuthUser } from "../../../src/types/auth";

const useProjectsMock = vi.fn();
const useRunsMock = vi.fn();

vi.mock("../../../src/api/projects", () => ({
  useProjects: () => useProjectsMock(),
  useCreateProject: () => ({ mutate: vi.fn(), isPending: false, isError: false }),
}));

vi.mock("../../../src/api/runs", () => ({
  useRuns: () => useRunsMock(),
}));

vi.mock("../../../src/api/suites", () => ({
  useSuites: () => ({ data: { items: [], meta: { total: 0 } } }),
}));

function project(id: string, name: string) {
  return {
    id,
    name,
    description: "desc",
    slug: name.toLowerCase(),
    createdBy: "u",
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
  };
}

function renderPage(user: AuthUser | null) {
  return render(
    <AppProviders user={user}>
      <MemoryRouter>
        <ProjectsPage />
      </MemoryRouter>
    </AppProviders>,
  );
}

beforeEach(() => {
  useRunsMock.mockReturnValue({ data: { items: [], meta: { total: 0 } } });
});

describe("ProjectsPage", () => {
  it("shows skeletons while loading", () => {
    useProjectsMock.mockReturnValue({ isLoading: true });
    const { container } = renderPage(makeUser("OWNER"));
    expect(container.querySelectorAll(".animate-pulse").length).toBeGreaterThan(
      0,
    );
    expect(screen.queryAllByTestId("project-card")).toHaveLength(0);
  });

  it("shows an empty state when there are no projects", () => {
    useProjectsMock.mockReturnValue({
      isLoading: false,
      isError: false,
      data: { items: [], meta: { total: 0 } },
    });
    renderPage(makeUser("OWNER"));
    expect(screen.getByText("No projects yet")).toBeInTheDocument();
  });

  it("renders one card per project", () => {
    useProjectsMock.mockReturnValue({
      isLoading: false,
      isError: false,
      data: {
        items: [project("1", "Alpha"), project("2", "Beta")],
        meta: { total: 2 },
      },
    });
    renderPage(makeUser("OWNER"));
    expect(screen.getAllByTestId("project-card")).toHaveLength(2);
  });

  it("disables New Project for MEMBER and enables it for OWNER", () => {
    useProjectsMock.mockReturnValue({
      isLoading: false,
      isError: false,
      data: { items: [], meta: { total: 0 } },
    });

    const { unmount } = renderPage(makeUser("MEMBER"));
    expect(screen.getByTestId("new-project")).toBeDisabled();
    unmount();

    renderPage(makeUser("OWNER"));
    expect(screen.getByTestId("new-project")).toBeEnabled();
  });
});
