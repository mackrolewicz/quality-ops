import { clsx } from "clsx";

interface SpinnerProps {
  className?: string;
}

export function Spinner({ className }: SpinnerProps) {
  return (
    <span
      data-testid="spinner"
      aria-hidden="true"
      className={clsx(
        "inline-block h-4 w-4 animate-spin rounded-full border-2 border-line border-t-accent",
        className,
      )}
    />
  );
}
