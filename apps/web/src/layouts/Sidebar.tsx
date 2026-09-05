import { clsx } from "clsx";
import { NavLink } from "react-router-dom";

import { useAuth } from "../features/auth/useAuth";
import { Logo } from "../components/Logo";
import {
  DashboardIcon,
  ProjectsIcon,
  ResultsIcon,
  RunsIcon,
  SettingsIcon,
  SuitesIcon,
} from "../components/icons";

interface NavItem {
  to: string;
  label: string;
  icon: typeof DashboardIcon;
  end?: boolean;
  testId: string;
}

const NAV_ITEMS: NavItem[] = [
  { to: "/", label: "Dashboard", icon: DashboardIcon, end: true, testId: "nav-dashboard" },
  { to: "/projects", label: "Projects", icon: ProjectsIcon, testId: "nav-projects" },
  { to: "/suites", label: "Test Suites", icon: SuitesIcon, testId: "nav-suites" },
  { to: "/runs", label: "Runs", icon: RunsIcon, testId: "nav-runs" },
  { to: "/results", label: "Results", icon: ResultsIcon, testId: "nav-results" },
  { to: "/settings", label: "Settings", icon: SettingsIcon, testId: "nav-settings" },
];

export function Sidebar() {
  const { user } = useAuth();

  return (
    <aside className="sticky top-0 flex h-screen w-60 shrink-0 flex-col border-r border-line bg-surface">
      <div className="flex h-14 items-center px-4">
        <Logo />
      </div>
      <nav className="flex-1 space-y-1 px-3 py-2">
        {NAV_ITEMS.map((item) => {
          const Icon = item.icon;
          return (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              data-testid={item.testId}
              className={({ isActive }) =>
                clsx(
                  "flex items-center gap-3 rounded-md px-3 py-2 text-sm",
                  isActive
                    ? "bg-accent-subtle text-primary"
                    : "text-muted hover:bg-surface-raised hover:text-primary",
                )
              }
            >
              <Icon />
              {item.label}
            </NavLink>
          );
        })}
      </nav>
      {user && (
        <div className="border-t border-line p-3 text-xs text-subtle">
          <p className="truncate text-secondary">{user.email}</p>
          <p className="truncate">Org {user.orgId.slice(0, 8)}</p>
        </div>
      )}
    </aside>
  );
}
