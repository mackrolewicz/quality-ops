import { useState } from "react";

import { clsx } from "clsx";

import { formatDuration } from "../../lib/time";
import type {
  RepositoryItemStatus,
  RepositoryTestItemResponse,
} from "../../api/types";

interface RepositoryTestItemsTableProps {
  items: RepositoryTestItemResponse[];
}

const STATUS_CLASS: Record<RepositoryItemStatus, string> = {
  PASSED: "bg-status-passed/15 text-status-passed",
  FAILED: "bg-status-failed/15 text-status-failed",
  SKIPPED: "bg-status-skipped/15 text-status-skipped",
  ERROR: "bg-status-failed/15 text-status-failed",
};

/** ADR-009 §7/§11 — the parsed per-test breakdown for a repository run
 *  (`meta.repositoryItems` on the results payload). Only rendered when
 *  non-empty. */
export function RepositoryTestItemsTable({ items }: RepositoryTestItemsTableProps) {
  const [expanded, setExpanded] = useState<number | null>(null);

  return (
    <div
      className="overflow-x-auto rounded-lg border border-line"
      data-testid="repository-test-items-table"
    >
      <table className="w-full text-sm">
        <thead className="bg-surface text-subtle">
          <tr>
            <th className="px-4 py-2 text-left text-xs font-medium uppercase tracking-wide">
              Test
            </th>
            <th className="px-4 py-2 text-left text-xs font-medium uppercase tracking-wide">
              Status
            </th>
            <th className="px-4 py-2 text-left text-xs font-medium uppercase tracking-wide">
              Duration
            </th>
            <th className="px-4 py-2 text-left text-xs font-medium uppercase tracking-wide">
              Failure
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((item, index) => {
            const isOpen = expanded === index;
            return (
              <tr
                key={`${item.suite ?? ""}::${item.name}::${index}`}
                data-testid="repository-item-row"
                onClick={() => setExpanded(isOpen ? null : index)}
                className={clsx(
                  "border-t border-line hover:bg-surface-raised",
                  item.failureMessage && "cursor-pointer",
                )}
              >
                <td className="px-4 py-3">
                  <p className="text-secondary">{item.name}</p>
                  {item.suite && (
                    <p className="font-mono text-xs text-muted">{item.suite}</p>
                  )}
                </td>
                <td className="px-4 py-3">
                  <span
                    className={clsx(
                      "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium",
                      STATUS_CLASS[item.status],
                    )}
                  >
                    {item.status}
                  </span>
                </td>
                <td className="px-4 py-3 text-secondary">
                  {formatDuration(item.durationMs)}
                </td>
                <td className="px-4 py-3">
                  {item.failureMessage ? (
                    isOpen ? (
                      <pre className="whitespace-pre-wrap font-mono text-xs text-secondary">
                        {item.failureMessage}
                      </pre>
                    ) : (
                      <span className="line-clamp-1 font-mono text-xs text-muted">
                        {item.failureMessage}
                      </span>
                    )
                  ) : (
                    <span className="text-subtle">—</span>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
