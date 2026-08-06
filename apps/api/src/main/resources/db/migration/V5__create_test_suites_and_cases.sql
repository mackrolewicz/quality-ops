-- V5: Test suites and test cases — the test catalog per project

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'suite_type') THEN
        CREATE TYPE suite_type AS ENUM ('API', 'UI', 'PERFORMANCE');
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS test_suites (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  UUID        NOT NULL REFERENCES projects (id),
    org_id      UUID        NOT NULL,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    type        suite_type  NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ NULL,
    CONSTRAINT uq_test_suites_name_project UNIQUE (project_id, name)
);
CREATE INDEX IF NOT EXISTS idx_test_suites_project_id ON test_suites (project_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_test_suites_org_id ON test_suites (org_id) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS test_cases (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id    UUID        NOT NULL REFERENCES test_suites (id),
    org_id      UUID        NOT NULL,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    order_index INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ NULL
);
CREATE INDEX IF NOT EXISTS idx_test_cases_suite_id ON test_cases (suite_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_test_cases_org_id ON test_cases (org_id) WHERE deleted_at IS NULL;
