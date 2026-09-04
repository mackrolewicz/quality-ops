import { render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";

import { Pagination } from "../../src/components/Pagination";

describe("Pagination", () => {
  it("derives the total page count from meta", () => {
    render(
      <Pagination page={1} pageSize={20} total={45} onPageChange={vi.fn()} />,
    );
    expect(screen.getByText("Page 1 of 3")).toBeInTheDocument();
  });

  it("disables Prev on the first page", () => {
    render(
      <Pagination page={1} pageSize={20} total={45} onPageChange={vi.fn()} />,
    );
    expect(screen.getByTestId("pagination-prev")).toBeDisabled();
    expect(screen.getByTestId("pagination-next")).toBeEnabled();
  });

  it("disables Next on the last page", () => {
    render(
      <Pagination page={3} pageSize={20} total={45} onPageChange={vi.fn()} />,
    );
    expect(screen.getByTestId("pagination-next")).toBeDisabled();
  });

  it("emits the new page on click", async () => {
    const onPageChange = vi.fn();
    render(
      <Pagination
        page={2}
        pageSize={20}
        total={45}
        onPageChange={onPageChange}
      />,
    );
    await userEvent.click(screen.getByTestId("pagination-next"));
    expect(onPageChange).toHaveBeenCalledWith(3);
    await userEvent.click(screen.getByTestId("pagination-prev"));
    expect(onPageChange).toHaveBeenCalledWith(1);
  });
});
