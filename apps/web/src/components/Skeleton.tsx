import { clsx } from "clsx";

interface SkeletonProps {
  className?: string;
}

export function Skeleton({ className }: SkeletonProps) {
  return (
    <div
      className={clsx("animate-pulse rounded-md bg-surface-raised", className)}
    />
  );
}

interface SkeletonRowsProps {
  count?: number;
  className?: string;
}

export function SkeletonRows({ count = 5, className }: SkeletonRowsProps) {
  return (
    <div className={clsx("space-y-2", className)}>
      {Array.from({ length: count }).map((_, i) => (
        <Skeleton key={i} className="h-10 w-full" />
      ))}
    </div>
  );
}
