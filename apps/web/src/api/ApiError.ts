import type { ApiErrorDetail } from "./types";

export class ApiError extends Error {
  readonly code: string;
  readonly details: ApiErrorDetail[] | null;
  readonly status: number | null;

  constructor(
    code: string,
    message: string,
    details: ApiErrorDetail[] | null = null,
    status: number | null = null,
  ) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.details = details;
    this.status = status;
  }
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}
