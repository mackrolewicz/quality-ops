import {
  runRefetchInterval,
  runsListActive,
} from "../../../src/features/runs/runStatus";
import type { RunResponse, RunStatus } from "../../../src/api/types";

function run(status: RunStatus): RunResponse {
  return {
    id: "r",
    projectId: "p",
    suiteId: "s",
    environmentId: "e",
    status,
    triggeredBy: "u",
    startedAt: null,
    completedAt: null,
    createdAt: "2026-01-01T00:00:00Z",
  };
}

describe("runRefetchInterval", () => {
  it("polls for active statuses", () => {
    expect(runRefetchInterval(run("PENDING"))).toBe(2000);
    expect(runRefetchInterval(run("RUNNING"))).toBe(2000);
  });

  it("does not poll for terminal statuses or undefined", () => {
    expect(runRefetchInterval(run("PASSED"))).toBe(false);
    expect(runRefetchInterval(run("FAILED"))).toBe(false);
    expect(runRefetchInterval(run("CANCELLED"))).toBe(false);
    expect(runRefetchInterval(undefined)).toBe(false);
  });
});

describe("runsListActive", () => {
  it("polls when any run in the list is active", () => {
    expect(runsListActive([run("PASSED"), run("RUNNING")])).toBe(2000);
  });

  it("does not poll when all runs are terminal", () => {
    expect(runsListActive([run("PASSED"), run("FAILED")])).toBe(false);
    expect(runsListActive([])).toBe(false);
    expect(runsListActive(undefined)).toBe(false);
  });
});
