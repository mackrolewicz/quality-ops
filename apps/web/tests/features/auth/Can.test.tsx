import { render, renderHook, screen } from "@testing-library/react";
import type { ReactNode } from "react";

import { Can } from "../../../src/features/auth/Can";
import { useHasRole } from "../../../src/features/auth/useRole";
import { MockAuthProvider, makeUser } from "../../testUtils";
import type { Role } from "../../../src/types/auth";

function wrapper(role: Role) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <MockAuthProvider user={makeUser(role)}>{children}</MockAuthProvider>;
  };
}

describe("Can", () => {
  it("renders children when the role is allowed", () => {
    render(
      <Can roles={["OWNER", "ADMIN"]} fallback={<span>denied</span>}>
        <span>allowed</span>
      </Can>,
      { wrapper: wrapper("OWNER") },
    );
    expect(screen.getByText("allowed")).toBeInTheDocument();
  });

  it("renders the fallback when the role is not allowed", () => {
    render(
      <Can roles={["OWNER", "ADMIN"]} fallback={<span>denied</span>}>
        <span>allowed</span>
      </Can>,
      { wrapper: wrapper("MEMBER") },
    );
    expect(screen.getByText("denied")).toBeInTheDocument();
    expect(screen.queryByText("allowed")).not.toBeInTheDocument();
  });

  it("useHasRole returns a boolean for the current role", () => {
    const { result } = renderHook(() => useHasRole(["OWNER", "ADMIN"]), {
      wrapper: wrapper("ADMIN"),
    });
    expect(result.current).toBe(true);

    const denied = renderHook(() => useHasRole(["OWNER"]), {
      wrapper: wrapper("VIEWER"),
    });
    expect(denied.result.current).toBe(false);
  });
});
