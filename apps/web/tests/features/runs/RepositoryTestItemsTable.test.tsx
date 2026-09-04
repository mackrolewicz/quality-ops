import { render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";

import { RepositoryTestItemsTable } from "../../../src/features/runs/RepositoryTestItemsTable";
import type { RepositoryTestItemResponse } from "../../../src/api/types";

const ITEMS: RepositoryTestItemResponse[] = [
  {
    suite: "tests/test_login.py",
    name: "test_valid_login",
    status: "PASSED",
    durationMs: 120,
    failureType: null,
    failureMessage: null,
  },
  {
    suite: "tests/test_login.py",
    name: "test_invalid_login",
    status: "FAILED",
    durationMs: 80,
    failureType: "AssertionError",
    failureMessage: "expected 401 but was 200",
  },
];

describe("RepositoryTestItemsTable", () => {
  it("renders one row per item with its status", () => {
    render(<RepositoryTestItemsTable items={ITEMS} />);

    expect(screen.getAllByTestId("repository-item-row")).toHaveLength(2);
    expect(screen.getByText("test_valid_login")).toBeInTheDocument();
    expect(screen.getByText("FAILED")).toBeInTheDocument();
  });

  it("expands a failure message on row click", async () => {
    render(<RepositoryTestItemsTable items={ITEMS} />);

    await userEvent.click(screen.getByText("test_invalid_login"));

    expect(screen.getByText("expected 401 but was 200")).toBeInTheDocument();
  });
});
