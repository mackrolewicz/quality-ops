import { clsx } from "clsx";

export interface TabItem {
  id: string;
  label: string;
  testId?: string;
}

interface TabsProps {
  tabs: TabItem[];
  active: string;
  onChange: (id: string) => void;
}

export function Tabs({ tabs, active, onChange }: TabsProps) {
  return (
    <div className="border-b border-line">
      <div className="flex gap-6">
        {tabs.map((tab) => (
          <button
            key={tab.id}
            type="button"
            data-testid={tab.testId}
            onClick={() => onChange(tab.id)}
            className={clsx(
              "px-1 py-3 text-sm font-medium",
              tab.id === active
                ? "-mb-px border-b-2 border-accent text-primary"
                : "text-muted hover:text-primary",
            )}
          >
            {tab.label}
          </button>
        ))}
      </div>
    </div>
  );
}
