import { render, screen, waitFor } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

import { ApiError } from "../../../src/api/ApiError";
import { LoginPage } from "../../../src/features/auth/LoginPage";
import { AppProviders } from "../../testUtils";

const navigateMock = vi.fn();

vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual<typeof import("react-router-dom")>(
    "react-router-dom",
  );
  return { ...actual, useNavigate: () => navigateMock };
});

const loginMock = vi.fn();

function renderPage() {
  return render(
    <AppProviders user={null} authValue={{ login: loginMock }}>
      <MemoryRouter initialEntries={["/login"]}>
        <LoginPage />
      </MemoryRouter>
    </AppProviders>,
  );
}

beforeEach(() => {
  navigateMock.mockReset();
  loginMock.mockReset();
});

describe("LoginPage", () => {
  it("shows field errors and does not submit on invalid input", async () => {
    renderPage();
    await userEvent.type(screen.getByTestId("login-email"), "not-an-email");
    await userEvent.type(screen.getByTestId("login-password"), "short");
    await userEvent.click(screen.getByTestId("login-submit"));

    expect(
      await screen.findByText("Enter a valid email address"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Password must be at least 8 characters"),
    ).toBeInTheDocument();
    expect(loginMock).not.toHaveBeenCalled();
  });

  it("submits valid credentials and navigates", async () => {
    loginMock.mockResolvedValueOnce(undefined);
    renderPage();
    await userEvent.type(screen.getByTestId("login-email"), "owner@demo.com");
    await userEvent.type(screen.getByTestId("login-password"), "password123");
    await userEvent.click(screen.getByTestId("login-submit"));

    await waitFor(() =>
      expect(loginMock).toHaveBeenCalledWith({
        email: "owner@demo.com",
        password: "password123",
      }),
    );
    await waitFor(() =>
      expect(navigateMock).toHaveBeenCalledWith("/", { replace: true }),
    );
  });

  it("shows an auth error message on a 401", async () => {
    loginMock.mockRejectedValueOnce(
      new ApiError("UNAUTHORIZED", "bad", null, 401),
    );
    renderPage();
    await userEvent.type(screen.getByTestId("login-email"), "owner@demo.com");
    await userEvent.type(screen.getByTestId("login-password"), "password123");
    await userEvent.click(screen.getByTestId("login-submit"));

    expect(
      await screen.findByText("Invalid email or password."),
    ).toBeInTheDocument();
  });
});
