import type { ReactNode } from "react";

import { clsx } from "clsx";

import { SkeletonRows } from "./Skeleton";

export interface Column<Row> {
  key: string;
  header: string;
  render?: (row: Row) => ReactNode;
  className?: string;
}

interface DataTableProps<Row> {
  columns: Column<Row>[];
  rows: Row[];
  getRowId: (row: Row) => string;
  onRowClick?: (row: Row) => void;
  rowClassName?: (row: Row) => string | undefined;
  rowTestId?: string;
  emptyState?: ReactNode;
  loading?: boolean;
}

export function DataTable<Row>({
  columns,
  rows,
  getRowId,
  onRowClick,
  rowClassName,
  rowTestId,
  emptyState,
  loading = false,
}: DataTableProps<Row>) {
  if (loading) {
    return (
      <div className="rounded-lg border border-line p-4">
        <SkeletonRows count={5} />
      </div>
    );
  }

  if (rows.length === 0 && emptyState) {
    return <div className="rounded-lg border border-line">{emptyState}</div>;
  }

  return (
    <div className="overflow-x-auto rounded-lg border border-line">
      <table className="w-full text-sm">
        <thead className="bg-surface text-subtle">
          <tr>
            {columns.map((col) => (
              <th
                key={col.key}
                className={clsx(
                  "px-4 py-2 text-left font-medium uppercase tracking-wide text-xs",
                  col.className,
                )}
              >
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={getRowId(row)}
              data-testid={rowTestId}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              className={clsx(
                "border-t border-line hover:bg-surface-raised",
                onRowClick && "cursor-pointer",
                rowClassName?.(row),
              )}
            >
              {columns.map((col) => (
                <td
                  key={col.key}
                  className={clsx("px-4 py-3 text-secondary", col.className)}
                >
                  {col.render ? col.render(row) : ""}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
