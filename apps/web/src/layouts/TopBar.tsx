import { useMatches } from "react-router-dom";

import { BellIcon } from "../components/icons";
import { UserMenu } from "./UserMenu";

interface RouteHandle {
  title?: string;
}

export function TopBar() {
  const matches = useMatches();
  const title =
    [...matches]
      .reverse()
      .map((m) => (m.handle as RouteHandle | undefined)?.title)
      .find(Boolean) ?? "QualityOps";

  return (
    <header className="sticky top-0 z-10 flex h-14 items-center justify-between border-b border-line bg-canvas/80 px-8 backdrop-blur">
      <h1 className="text-lg font-semibold text-primary">{title}</h1>
      <div className="flex items-center gap-2">
        <button
          type="button"
          title="Notifications"
          className="rounded-md p-2 text-muted hover:bg-surface-raised hover:text-primary"
        >
          <BellIcon />
        </button>
        <UserMenu />
      </div>
    </header>
  );
}
