import { forwardRef, type ButtonHTMLAttributes } from "react";

import { clsx } from "clsx";

import { Spinner } from "./Spinner";

type Variant = "primary" | "secondary" | "ghost" | "danger";
type Size = "sm" | "md" | "lg";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  isLoading?: boolean;
  "data-testid"?: string;
}

const VARIANT_CLASSES: Record<Variant, string> = {
  primary: "bg-gradient-accent text-white hover:opacity-95",
  secondary:
    "bg-surface-raised text-secondary border border-line hover:border-line-strong",
  ghost: "text-muted hover:text-primary hover:bg-surface-raised",
  danger:
    "bg-status-failed/15 text-status-failed border border-status-failed/30 hover:bg-status-failed/25",
};

const SIZE_CLASSES: Record<Size, string> = {
  sm: "h-8 px-3 text-xs",
  md: "h-9 px-4 text-sm",
  lg: "h-11 px-5 text-sm",
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  function Button(
    {
      variant = "primary",
      size = "md",
      isLoading = false,
      disabled,
      className,
      children,
      type = "button",
      ...rest
    },
    ref,
  ) {
    return (
      <button
        ref={ref}
        type={type}
        disabled={disabled || isLoading}
        className={clsx(
          "inline-flex items-center justify-center gap-2 rounded-md font-medium",
          "disabled:opacity-50 disabled:pointer-events-none focus-visible:outline-none",
          "focus-visible:ring-2 focus-visible:ring-accent/60 transition-colors",
          VARIANT_CLASSES[variant],
          SIZE_CLASSES[size],
          className,
        )}
        {...rest}
      >
        {isLoading && <Spinner />}
        {children}
      </button>
    );
  },
);
