import { useState } from "react";

import { formatDateTime } from "../../lib/time";
import { ChevronDownIcon } from "../../components/icons";
import type { RepositoryRunResponse } from "../../api/types";

interface RepositoryExecutionPanelProps {
  repositoryRun: RepositoryRunResponse;
}

/** ADR-009 §11/§13 — the run-detail page's repository-execution provenance
 *  panel: exact resolved commit, image digest, exit code, item counts, and the
 *  checkout/started/finished timestamps. Additive-nullable — only rendered
 *  when the run carries a repository test case. */
export function RepositoryExecutionPanel({
  repositoryRun,
}: RepositoryExecutionPanelProps) {
  const [open, setOpen] = useState(true);
  const r = repositoryRun;

  return (
    <div
      className="rounded-lg border border-line bg-surface p-4"
      data-testid="repository-execution-panel"
    >
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center justify-between text-sm font-medium text-primary"
      >
        Repository execution
        <ChevronDownIcon
          className={open ? "rotate-180 transition-transform" : "transition-transform"}
        />
      </button>

      {open && (
        <dl className="mt-3 space-y-2 text-sm">
          <div className="flex justify-between gap-4">
            <dt className="text-subtle">Repository</dt>
            <dd className="font-mono text-xs text-secondary">
              {r.provider} / {r.repoPath}
            </dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt className="text-subtle">Ref</dt>
            <dd className="text-secondary">
              {r.requestedRef} ({r.refType})
            </dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt className="text-subtle">Commit</dt>
            <dd
              data-testid="repository-commit-sha"
              className="font-mono text-xs text-muted"
            >
              {r.commitSha}
            </dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt className="text-subtle">Framework</dt>
            <dd className="text-secondary">{r.framework}</dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt className="text-subtle">State</dt>
            <dd data-testid="repository-run-state" className="text-secondary">
              {r.state}
            </dd>
          </div>
          {r.runnerImageDigest && (
            <div className="flex justify-between gap-4">
              <dt className="text-subtle">Image digest</dt>
              <dd className="truncate font-mono text-xs text-muted" title={r.runnerImageDigest}>
                {r.runnerImageDigest}
              </dd>
            </div>
          )}
          <div className="flex justify-between gap-4">
            <dt className="text-subtle">Exit code</dt>
            <dd data-testid="repository-exit-code" className="text-secondary">
              {r.containerExitCode ?? "—"}
            </dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt className="text-subtle">Items</dt>
            <dd className="text-secondary">
              {r.itemsPassed ?? 0} passed / {r.itemsFailed ?? 0} failed /{" "}
              {r.itemsSkipped ?? 0} skipped (of {r.itemsTotal ?? 0})
            </dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt className="text-subtle">Checkout</dt>
            <dd className="text-secondary">{formatDateTime(r.checkoutAt)}</dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt className="text-subtle">Finished</dt>
            <dd className="text-secondary">{formatDateTime(r.finishedAt)}</dd>
          </div>
        </dl>
      )}
    </div>
  );
}
