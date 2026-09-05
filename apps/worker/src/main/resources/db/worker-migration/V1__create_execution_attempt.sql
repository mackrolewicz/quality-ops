-- Worker-owned idempotency ledger. NOT authoritative run state.
CREATE SCHEMA IF NOT EXISTS worker;

CREATE TABLE IF NOT EXISTS worker.execution_attempt (
    execution_id        UUID         PRIMARY KEY,
    run_id              UUID         NOT NULL,
    org_id              UUID         NOT NULL,
    status              VARCHAR(16)  NOT NULL,          -- RUNNING | COMPLETED
    attempt_epoch       INT          NOT NULL DEFAULT 0,
    runner_kind         VARCHAR(16),                    -- SIMULATED | API
    terminal_topic      VARCHAR(32),                    -- runs.completed | runs.failed
    terminal_event_json JSONB,
    claimed_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    heartbeat_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_execution_attempt_run_id  ON worker.execution_attempt (run_id);
CREATE INDEX IF NOT EXISTS idx_execution_attempt_org_id  ON worker.execution_attempt (org_id);
CREATE INDEX IF NOT EXISTS idx_execution_attempt_sweep   ON worker.execution_attempt (status, heartbeat_at);
CREATE INDEX IF NOT EXISTS idx_execution_attempt_created ON worker.execution_attempt (created_at);
