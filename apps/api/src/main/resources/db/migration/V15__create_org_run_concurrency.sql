-- V15: per-org max concurrent active runs override (Phase 2C, ADR-006 §4.2).
-- 2C ships the table + read path + a global default only; the write API/UI is 2D+.
CREATE TABLE IF NOT EXISTS org_run_concurrency (
    org_id          UUID        PRIMARY KEY,
    max_active_runs INT         NOT NULL CHECK (max_active_runs > 0),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
