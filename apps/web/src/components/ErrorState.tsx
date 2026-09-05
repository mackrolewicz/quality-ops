import { isApiError } from "../api/ApiError";
import { Button } from "./Button";

interface ErrorStateProps {
  error: unknown;
  onRetry?: () => void;
  title?: string;
}

export function ErrorState({ error, onRetry, title }: ErrorStateProps) {
  const code = isApiError(error) ? error.code : "ERROR";
  const message = isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : "Something went wrong.";

  return (
    <div className="rounded-lg border border-status-failed/30 bg-status-failed/5 p-4 text-sm">
      <p className="font-medium text-status-failed">{title ?? code}</p>
      <p className="mt-1 text-muted">{message}</p>
      {onRetry && (
        <div className="mt-3">
          <Button variant="secondary" size="sm" onClick={onRetry}>
            Retry
          </Button>
        </div>
      )}
    </div>
  );
}
