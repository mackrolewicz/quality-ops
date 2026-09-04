import { render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";

import { Button } from "../../src/components/Button";

describe("Button", () => {
  it("applies variant and size classes", () => {
    render(
      <Button variant="danger" size="lg">
        Delete
      </Button>,
    );
    const btn = screen.getByRole("button", { name: "Delete" });
    expect(btn).toHaveClass("text-status-failed");
    expect(btn).toHaveClass("h-11");
  });

  it("shows a spinner and is disabled while loading", () => {
    render(<Button isLoading>Save</Button>);
    const btn = screen.getByRole("button", { name: "Save" });
    expect(btn).toBeDisabled();
    expect(screen.getByTestId("spinner")).toBeInTheDocument();
  });

  it("fires onClick when enabled", async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Go</Button>);
    await userEvent.click(screen.getByRole("button", { name: "Go" }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("does not fire onClick while loading", async () => {
    const onClick = vi.fn();
    render(
      <Button onClick={onClick} isLoading>
        Go
      </Button>,
    );
    await userEvent.click(screen.getByRole("button", { name: "Go" }));
    expect(onClick).not.toHaveBeenCalled();
  });

  it("passes through data-testid", () => {
    render(<Button data-testid="my-button">Go</Button>);
    expect(screen.getByTestId("my-button")).toBeInTheDocument();
  });
});
