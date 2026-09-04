import { useState } from "react";

import { formatDateTime } from "../../lib/time";
import { shortId } from "../../lib/shortId";
import { ChevronDownIcon } from "../../components/icons";
import type { RunResponse } from "../../api/types";

interface RunConfigPanelProps {
  run: RunResponse;
  suiteName?: string;
  environmentName?: string;
  environmentUrl?: string;
}

export function RunConfigPanel({
  run,
  suiteName,
  environmentName,
  environmentUrl,
}: RunConfigPanelProps) {
  const [open, setOpen] = useState(true);

  return (
    <div
      className="rounded-lg border border-line bg-surface p-4"
      data-testid="run-config-panel"
    >
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center justify-between text-sm font-medium text-primary"
      >
        Run configuration snapshot
        <ChevronDownIcon
          className={open ? "rotate-180 transition-transform" : "transition-transform"}
        />
      </button>

      {open && (
        <dl className="mt-3 space-y-2 text-sm">
          <div className="flex justify-between gap-4">
            <dt className="text-subtle">Suite</dt>
            <dd className="text-secondary">
              {suiteName ?? shortId(run.suiteId)}
            </dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt className="text-subtle">Environment</dt>
            <dd className="text-secondary">
              {environmentName ?? shortId(run.environmentId)}
            </dd>
          </div>
          {environmentUrl && (
            <div className="flex justify-between gap-4">
              <dt className="text-subtle">URL</dt>
              <dd className="font-mono text-xs text-muted">{environmentUrl}</dd>
            </div>
          )}
          <div className="flex justify-between gap-4">
            <dt className="text-subtle">Triggered by</dt>
            <dd className="font-mono text-xs text-muted">
              {shortId(run.triggeredBy)}
            </dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt className="text-subtle">Created at</dt>
            <dd className="text-secondary">{formatDateTime(run.createdAt)}</dd>
          </div>
        </dl>
      )}
    </div>
  );
}
