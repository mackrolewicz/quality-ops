import { useForm } from "react-hook-form";
import { Navigate, useLocation, useNavigate } from "react-router-dom";

import { useLogin } from "../../api/auth";
import { isApiError } from "../../api/ApiError";
import { Button } from "../../components/Button";
import { Field } from "../../components/Field";
import { Logo } from "../../components/Logo";
import { TextInput } from "../../components/TextInput";
import { useAuth } from "./useAuth";

interface LoginForm {
  email: string;
  password: string;
}

interface LocationState {
  from?: { pathname: string };
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function LoginPage() {
  const { isAuthenticated } = useAuth();
  const login = useLogin();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as LocationState | null)?.from?.pathname ?? "/";

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginForm>({ mode: "onSubmit" });

  if (isAuthenticated) {
    return <Navigate to={from} replace />;
  }

  const onSubmit = (data: LoginForm) => {
    login.mutate(data, {
      onSuccess: () => navigate(from, { replace: true }),
    });
  };

  const errorMessage =
    login.isError &&
    (isApiError(login.error) && login.error.status === 401
      ? "Invalid email or password."
      : isApiError(login.error)
        ? login.error.message
        : "Unable to sign in. Please try again.");

  return (
    <div className="flex min-h-screen items-center justify-center bg-canvas px-4">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex justify-center">
          <Logo />
        </div>

        {errorMessage && (
          <div
            data-testid="login-error"
            className="mb-4 rounded-lg border border-status-failed/30 bg-status-failed/5 p-3 text-sm text-status-failed"
          >
            {errorMessage}
          </div>
        )}

        <form
          onSubmit={handleSubmit(onSubmit)}
          className="space-y-4 rounded-lg border border-line bg-surface p-6"
          noValidate
        >
          <Field label="Email" htmlFor="login-email" error={errors.email?.message}>
            <TextInput
              id="login-email"
              type="email"
              autoComplete="email"
              data-testid="login-email"
              invalid={Boolean(errors.email)}
              {...register("email", {
                required: "Email is required",
                pattern: {
                  value: EMAIL_PATTERN,
                  message: "Enter a valid email address",
                },
              })}
            />
          </Field>

          <Field
            label="Password"
            htmlFor="login-password"
            error={errors.password?.message}
          >
            <TextInput
              id="login-password"
              type="password"
              autoComplete="current-password"
              data-testid="login-password"
              invalid={Boolean(errors.password)}
              {...register("password", {
                required: "Password is required",
                minLength: {
                  value: 8,
                  message: "Password must be at least 8 characters",
                },
              })}
            />
          </Field>

          <Button
            type="submit"
            className="w-full"
            isLoading={login.isPending}
            data-testid="login-submit"
          >
            Sign in
          </Button>

          <a
            href="#"
            aria-disabled="true"
            className="block text-xs text-subtle"
            onClick={(e) => e.preventDefault()}
          >
            Forgot password?
          </a>

          <div className="flex items-center gap-3 text-xs text-subtle">
            <span className="h-px flex-1 bg-line" />
            or continue with
            <span className="h-px flex-1 bg-line" />
          </div>

          <div className="grid grid-cols-2 gap-2">
            <Button
              variant="secondary"
              disabled
              title="SSO is coming in Phase 4"
            >
              GitHub
            </Button>
            <Button
              variant="secondary"
              disabled
              title="SSO is coming in Phase 4"
            >
              Google
            </Button>
          </div>
        </form>

        <p className="mt-6 text-center text-xs text-subtle">
          Don&apos;t have an account? Contact your admin
        </p>
      </div>
    </div>
  );
}
