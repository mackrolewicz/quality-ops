-- V4: Environments — deployed targets (dev/staging/production) per project

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'environment_type') THEN
        CREATE TYPE environment_type AS ENUM ('DEV', 'STAGING', 'PRODUCTION');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'environment_status') THEN
        CREATE TYPE environment_status AS ENUM ('ACTIVE', 'INACTIVE');
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS environments (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  UUID        NOT NULL REFERENCES projects (id),
    org_id      UUID        NOT NULL,
    name        VARCHAR(255) NOT NULL,
    base_url    VARCHAR(2048) NOT NULL,
    type        environment_type NOT NULL,
    status      environment_status NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ NULL,
    CONSTRAINT uq_environments_name_project UNIQUE (project_id, name)
);
CREATE INDEX IF NOT EXISTS idx_environments_project_id ON environments (project_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_environments_org_id ON environments (org_id) WHERE deleted_at IS NULL;
