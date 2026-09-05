import { useNavigate } from "react-router-dom";

import { useLogout } from "../../api/auth";
import { Button } from "../../components/Button";
import { Card } from "../../components/Card";
import { RoleBadge } from "../../components/RoleBadge";
import { useAuth } from "../auth/useAuth";

export function SettingsPage() {
  const { user } = useAuth();
  const logout = useLogout();
  const navigate = useNavigate();

  if (!user) return null;

  return (
    <div className="max-w-2xl space-y-6">
      <h1 className="text-2xl font-semibold text-primary">Settings</h1>

      <Card title="Account">
        <dl className="space-y-3 text-sm">
          <div className="flex justify-between gap-4">
            <dt className="text-subtle">Email</dt>
            <dd className="text-secondary">{user.email}</dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt className="text-subtle">Organization ID</dt>
            <dd className="font-mono text-xs text-muted">{user.orgId}</dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt className="text-subtle">Role</dt>
            <dd>
              <RoleBadge role={user.role} />
            </dd>
          </div>
        </dl>
      </Card>

      <Card title="Coming later">
        <p className="text-sm text-muted">
          Team management, API tokens, and SSO arrive in Phase 4.
        </p>
      </Card>

      <Button
        variant="secondary"
        data-testid="settings-sign-out"
        onClick={() =>
          logout.mutate(undefined, {
            onSettled: () => navigate("/login", { replace: true }),
          })
        }
      >
        Sign out
      </Button>
    </div>
  );
}
