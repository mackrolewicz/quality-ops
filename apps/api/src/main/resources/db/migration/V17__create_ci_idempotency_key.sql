-- V17: CI idempotency dedupe (Phase 2D, ADR-007 §5.3). Every table carries org_id.
CREATE TABLE IF NOT EXISTS ci_idempotency_key (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID         NOT NULL,
    idempotency_key     VARCHAR(200) NOT NULL,
    request_fingerprint VARCHAR(64)  NOT NULL,
    run_id              UUID         NOT NULL REFERENCES test_runs (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (org_id, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_ci_idempotency_created_at ON ci_idempotency_key (created_at);
CREATE INDEX IF NOT EXISTS idx_ci_idempotency_run_id     ON ci_idempotency_key (run_id);
