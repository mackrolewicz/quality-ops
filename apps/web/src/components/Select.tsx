import { forwardRef, type SelectHTMLAttributes } from "react";

import { clsx } from "clsx";

interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  invalid?: boolean;
}

export const Select = forwardRef<HTMLSelectElement, SelectProps>(
  function Select({ invalid = false, className, children, ...rest }, ref) {
    return (
      <select
        ref={ref}
        aria-invalid={invalid || undefined}
        className={clsx(
          "h-9 w-full rounded-md bg-surface-raised border border-line px-3 text-sm",
          "text-primary focus:border-accent focus:ring-2 focus:ring-accent/40 outline-none",
          invalid &&
            "border-status-failed focus:border-status-failed focus:ring-status-failed/30",
          className,
        )}
        {...rest}
      >
        {children}
      </select>
    );
  },
);
