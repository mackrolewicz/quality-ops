-- V24 (ADR-009 §3): 1:1 with test_runs (run_id UNIQUE), mirrors run_queue.
-- Columns down to timeout_seconds are frozen at enqueue by the API
-- (domain rule #2). state + runner_image_digest .. error_detail are execution
-- telemetry written by the lifecycle + result consumers (API is sole writer)
-- from runs.started / the v5 terminal / results.chunk.
-- All 8 enum-like columns are VARCHAR + CHECK, never a PG enum.
CREATE TABLE IF NOT EXISTS repository_run (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                   UUID         NOT NULL,
    run_id                   UUID         NOT NULL UNIQUE REFERENCES test_runs (id),
    repository_connection_id UUID         NOT NULL REFERENCES repository_connection (id),
    provider                 VARCHAR(16)  NOT NULL CHECK (provider IN ('GITHUB', 'GITLAB')),
    repo_host                VARCHAR(255) NOT NULL,
    repo_path                VARCHAR(512) NOT NULL,
    requested_ref            VARCHAR(255) NOT NULL,
    commit_sha               VARCHAR(40)  NOT NULL,
    ref_type                 VARCHAR(16)  NOT NULL CHECK (ref_type IN ('BRANCH', 'TAG', 'COMMIT')),
    framework_preset         VARCHAR(16)  NOT NULL
                                 CHECK (framework_preset IN ('PLAYWRIGHT', 'JUNIT', 'PYTEST', 'CYPRESS', 'K6')),
    runner_image_ref         VARCHAR(512) NOT NULL,
    working_dir              VARCHAR(512),
    command_json             JSONB        NOT NULL,
    report_format            VARCHAR(24)  NOT NULL
                                 CHECK (report_format IN ('JUNIT_XML', 'K6_SUMMARY_JSON')),
    report_paths_json        JSONB,
    artifact_globs_json      JSONB,
    resource_profile         VARCHAR(16)  NOT NULL
                                 CHECK (resource_profile IN ('SMALL', 'MEDIUM', 'LARGE')),
    network_policy           VARCHAR(16)  NOT NULL CHECK (network_policy IN ('ISOLATED', 'EGRESS')),
    timeout_seconds          INT          NOT NULL,
    state                    VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                                 CHECK (state IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    runner_image_digest      VARCHAR(80),
    container_exit_code      INT,
    items_total              INT,
    items_passed             INT,
    items_failed             INT,
    items_skipped            INT,
    checkout_at              TIMESTAMPTZ,
    started_at               TIMESTAMPTZ,
    finished_at              TIMESTAMPTZ,
    error_detail             VARCHAR(1000),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_repository_run_org ON repository_run (org_id);
CREATE INDEX IF NOT EXISTS idx_repository_run_conn ON repository_run (repository_connection_id);
