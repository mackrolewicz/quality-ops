import { render, screen } from "@testing-library/react";

import { RoleBadge } from "../../src/components/RoleBadge";
import type { Role } from "../../src/types/auth";

const CASES: [Role, string][] = [
  ["OWNER", "text-role-owner"],
  ["ADMIN", "text-role-admin"],
  ["MEMBER", "text-role-member"],
  ["VIEWER", "text-role-viewer"],
];

describe("RoleBadge", () => {
  it.each(CASES)("renders %s with its color class and label", (role, cls) => {
    render(<RoleBadge role={role} />);
    const badge = screen.getByText(role);
    expect(badge).toHaveClass(cls);
  });
});
