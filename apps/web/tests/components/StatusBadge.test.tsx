import { render, screen } from "@testing-library/react";

import { StatusBadge } from "../../src/components/StatusBadge";

describe("StatusBadge", () => {
  it("maps run statuses to their color classes", () => {
    render(<StatusBadge status="PASSED" />);
    const badge = screen.getByText("PASSED");
    expect(badge).toHaveClass("text-status-passed");
    expect(badge).toHaveClass("bg-status-passed/15");
  });

  it("renders the failed color class", () => {
    render(<StatusBadge status="FAILED" />);
    expect(screen.getByText("FAILED")).toHaveClass("text-status-failed");
  });

  it("accepts result statuses like FLAKY", () => {
    render(<StatusBadge status="FLAKY" />);
    expect(screen.getByText("FLAKY")).toHaveClass("text-status-flaky");
  });

  it("accepts result statuses like SKIPPED", () => {
    render(<StatusBadge status="SKIPPED" />);
    expect(screen.getByText("SKIPPED")).toHaveClass("text-status-skipped");
  });
});
