-- V22 (ADR-009 §3): org- + project-scoped GitHub/GitLab connection.
-- provider is VARCHAR + CHECK (NOT a PG enum). credential_ref is the opaque
-- resolver key ONLY — a provider token is NEVER stored here. Soft-deleted.
CREATE TABLE IF NOT EXISTS repository_connection (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         UUID         NOT NULL,
    project_id     UUID         NOT NULL REFERENCES projects (id),
    provider       VARCHAR(16)  NOT NULL CHECK (provider IN ('GITHUB', 'GITLAB')),
    host           VARCHAR(255) NOT NULL,
    owner_path     VARCHAR(512) NOT NULL,
    repo_name      VARCHAR(255) NOT NULL,
    default_ref    VARCHAR(255) NOT NULL DEFAULT 'main',
    credential_ref VARCHAR(64)  CHECK (credential_ref ~ '^[A-Z0-9_]{1,64}$'),
    created_by     UUID         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_repo_conn_identity
    ON repository_connection (org_id, project_id, provider, host, owner_path, repo_name)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_repo_conn_org ON repository_connection (org_id);
CREATE INDEX IF NOT EXISTS idx_repo_conn_project
    ON repository_connection (project_id) WHERE deleted_at IS NULL;
