import { useState } from "react";

import { clsx } from "clsx";

import { formatDuration } from "../../lib/time";
import { shortId } from "../../lib/shortId";
import { StatusBadge } from "../../components/StatusBadge";
import type { TestResultResponse } from "../../api/types";

interface RunResultsTableProps {
  results: TestResultResponse[];
}

export function RunResultsTable({ results }: RunResultsTableProps) {
  const [expanded, setExpanded] = useState<string | null>(null);

  return (
    <div className="overflow-x-auto rounded-lg border border-line">
      <table className="w-full text-sm">
        <thead className="bg-surface text-subtle">
          <tr>
            <th className="px-4 py-2 text-left text-xs font-medium uppercase tracking-wide">
              Test case
            </th>
            <th className="px-4 py-2 text-left text-xs font-medium uppercase tracking-wide">
              Status
            </th>
            <th className="px-4 py-2 text-left text-xs font-medium uppercase tracking-wide">
              Duration
            </th>
            <th className="px-4 py-2 text-left text-xs font-medium uppercase tracking-wide">
              Error
            </th>
            <th className="px-4 py-2 text-left text-xs font-medium uppercase tracking-wide">
              Retries
            </th>
          </tr>
        </thead>
        <tbody>
          {results.map((result) => {
            const isOpen = expanded === result.id;
            const isFailed = result.status === "FAILED";
            return (
              <tr
                key={result.id}
                data-testid="result-row"
                onClick={() =>
                  setExpanded(isOpen ? null : result.id)
                }
                className={clsx(
                  "border-t border-line hover:bg-surface-raised",
                  result.errorMessage && "cursor-pointer",
                  isFailed && "bg-status-failed/5",
                )}
              >
                <td className="px-4 py-3">
                  <span className="font-mono text-xs text-muted">
                    {shortId(result.testCaseId)}
                  </span>
                </td>
                <td className="px-4 py-3">
                  <StatusBadge status={result.status} />
                </td>
                <td className="px-4 py-3 text-secondary">
                  {formatDuration(result.durationMs)}
                </td>
                <td className="px-4 py-3">
                  {result.errorMessage ? (
                    isOpen ? (
                      <pre
                        data-testid="result-row-expand"
                        className="whitespace-pre-wrap font-mono text-xs text-secondary"
                      >
                        {result.errorMessage}
                      </pre>
                    ) : (
                      <span className="line-clamp-1 font-mono text-xs text-muted">
                        {result.errorMessage}
                      </span>
                    )
                  ) : (
                    <span className="text-subtle">—</span>
                  )}
                </td>
                <td className="px-4 py-3 text-secondary">
                  {result.retryCount}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
