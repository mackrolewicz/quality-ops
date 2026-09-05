import { forwardRef, type InputHTMLAttributes } from "react";

import { clsx } from "clsx";

interface TextInputProps extends InputHTMLAttributes<HTMLInputElement> {
  invalid?: boolean;
}

export const TextInput = forwardRef<HTMLInputElement, TextInputProps>(
  function TextInput({ invalid = false, className, ...rest }, ref) {
    return (
      <input
        ref={ref}
        aria-invalid={invalid || undefined}
        className={clsx(
          "h-9 w-full rounded-md bg-surface-raised border border-line px-3 text-sm",
          "text-primary placeholder:text-subtle focus:border-accent focus:ring-2",
          "focus:ring-accent/40 outline-none",
          invalid &&
            "border-status-failed focus:border-status-failed focus:ring-status-failed/30",
          className,
        )}
        {...rest}
      />
    );
  },
);
