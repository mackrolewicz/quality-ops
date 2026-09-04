import type { RunResponse, RunStatus } from "../../api/types";

export const ACTIVE = new Set<RunStatus>(["PENDING", "RUNNING"]);

const POLL_MS = 2000;

export function isActiveStatus(status: RunStatus | undefined): boolean {
  return status !== undefined && ACTIVE.has(status);
}

export function runRefetchInterval(data: RunResponse | undefined): number | false {
  return data && ACTIVE.has(data.status) ? POLL_MS : false;
}

export function runsListActive(
  list: RunResponse[] | undefined,
): number | false {
  return list?.some((run) => ACTIVE.has(run.status)) ? POLL_MS : false;
}
