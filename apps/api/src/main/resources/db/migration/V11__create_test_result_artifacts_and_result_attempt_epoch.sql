-- V11: durable test-artifact metadata + per-case attempt epoch on results (Phase 2B3, ADR-005).
-- The Worker writes opaque blobs to a private object bucket and references them on events;
-- the API stays the sole writer of this authoritative relational state.

CREATE TABLE IF NOT EXISTS test_result_artifacts (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id             UUID         NOT NULL,
    run_id             UUID         NOT NULL REFERENCES test_runs (id),
    test_case_id       UUID         NOT NULL REFERENCES test_cases (id),
    attempt_epoch      INT          NOT NULL DEFAULT 0,
    artifact_type      VARCHAR(24)  NOT NULL,
    storage_key        TEXT,
    content_type       VARCHAR(128),
    size_bytes         BIGINT,
    status             VARCHAR(16)  NOT NULL DEFAULT 'AVAILABLE',
    unavailable_reason VARCHAR(64),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_test_result_artifacts_run_id ON test_result_artifacts (run_id);
CREATE INDEX IF NOT EXISTS idx_test_result_artifacts_org_id ON test_result_artifacts (org_id);
CREATE INDEX IF NOT EXISTS idx_test_result_artifacts_case
    ON test_result_artifacts (run_id, test_case_id, attempt_epoch);
CREATE UNIQUE INDEX IF NOT EXISTS uq_test_result_artifacts_key
    ON test_result_artifacts (run_id, test_case_id, attempt_epoch, artifact_type);

-- "Latest attempt wins" guard for the epoch-monotone result upsert. Existing rows -> 0.
ALTER TABLE test_results ADD COLUMN IF NOT EXISTS attempt_epoch INT NOT NULL DEFAULT 0;
