import { useEffect, useRef, useState } from "react";

import { useNavigate } from "react-router-dom";

import { useLogout } from "../api/auth";
import { useAuth } from "../features/auth/useAuth";
import { ChevronDownIcon } from "../components/icons";

function initials(email: string): string {
  const name = email.split("@")[0] ?? email;
  return name.slice(0, 2).toUpperCase();
}

export function UserMenu() {
  const { user } = useAuth();
  const logout = useLogout();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", onClick);
    return () => document.removeEventListener("mousedown", onClick);
  }, []);

  if (!user) return null;

  const handleSignOut = () => {
    logout.mutate(undefined, {
      onSettled: () => navigate("/login", { replace: true }),
    });
  };

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        data-testid="user-menu"
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-2 rounded-md px-2 py-1 text-sm text-muted hover:bg-surface-raised hover:text-primary"
      >
        <span className="flex h-7 w-7 items-center justify-center rounded-full bg-gradient-accent text-xs font-medium text-white">
          {initials(user.email)}
        </span>
        <ChevronDownIcon />
      </button>
      {open && (
        <div className="absolute right-0 mt-2 w-56 rounded-md border border-line bg-surface p-2 shadow-2xl shadow-black/40">
          <div className="px-2 py-1.5">
            <p className="truncate text-sm text-primary">{user.email}</p>
            <p className="truncate text-xs text-subtle">Org {user.orgId.slice(0, 8)}</p>
          </div>
          <button
            type="button"
            data-testid="sign-out"
            onClick={handleSignOut}
            className="mt-1 w-full rounded-md px-2 py-1.5 text-left text-sm text-muted hover:bg-surface-raised hover:text-primary"
          >
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}
