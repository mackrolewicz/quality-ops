import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { RequireAuth } from "../../../src/features/auth/RequireAuth";
import { MockAuthProvider, makeUser } from "../../testUtils";
import type { AuthUser } from "../../../src/types/auth";

function renderWith(user: AuthUser | null) {
  return render(
    <MockAuthProvider user={user}>
      <MemoryRouter initialEntries={["/"]}>
        <Routes>
          <Route path="/login" element={<div>login screen</div>} />
          <Route element={<RequireAuth />}>
            <Route path="/" element={<div>protected content</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </MockAuthProvider>,
  );
}

describe("RequireAuth", () => {
  it("redirects unauthenticated users to the login screen", () => {
    renderWith(null);
    expect(screen.getByText("login screen")).toBeInTheDocument();
    expect(screen.queryByText("protected content")).not.toBeInTheDocument();
  });

  it("renders the child outlet when authenticated", () => {
    renderWith(makeUser());
    expect(screen.getByText("protected content")).toBeInTheDocument();
  });
});
