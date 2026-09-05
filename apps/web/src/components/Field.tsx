import type { ReactNode } from "react";

interface FieldProps {
  label: string;
  htmlFor?: string;
  error?: string;
  help?: string;
  children: ReactNode;
}

export function Field({ label, htmlFor, error, help, children }: FieldProps) {
  return (
    <div className="space-y-1">
      <label
        htmlFor={htmlFor}
        className="text-xs font-medium uppercase tracking-wide text-subtle"
      >
        {label}
      </label>
      {children}
      {error ? (
        <p className="mt-1 text-xs text-status-failed">{error}</p>
      ) : help ? (
        <p className="mt-1 text-xs text-subtle">{help}</p>
      ) : null}
    </div>
  );
}
