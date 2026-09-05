-- V13: authoritative test-run queue (Phase 2C, ADR-006 §3). 1:1 with test_runs.
-- priority/queue_state are VARCHAR+CHECK (NOT a PG ENUM) so 2C/2D can ALTER the
-- allowed set cheaply. requested_event_json is a single-purpose mini-outbox for
-- the QUEUED->DISPATCHED hop; nulled on any terminal transition (hence nullable).
CREATE TABLE IF NOT EXISTS run_queue (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id               UUID        NOT NULL,
    run_id               UUID        NOT NULL UNIQUE REFERENCES test_runs (id),
    schedule_id          UUID,
    priority             VARCHAR(16) NOT NULL DEFAULT 'NORMAL'
                             CHECK (priority IN ('HIGH', 'NORMAL', 'LOW')),
    queue_state          VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
                             CHECK (queue_state IN ('QUEUED', 'DISPATCHED', 'RUNNING',
                                                    'COMPLETED', 'FAILED', 'CANCELLED')),
    requested_event_json JSONB,
    enqueued_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    dispatched_at        TIMESTAMPTZ,
    dispatch_attempts    INT         NOT NULL DEFAULT 0,
    last_dispatch_at     TIMESTAMPTZ,
    cancel_requested     BOOLEAN     NOT NULL DEFAULT FALSE,
    cancel_requested_at  TIMESTAMPTZ,
    terminal_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_run_queue_dispatch
    ON run_queue (priority, enqueued_at) WHERE queue_state = 'QUEUED';
CREATE INDEX IF NOT EXISTS idx_run_queue_active
    ON run_queue (org_id) WHERE queue_state IN ('DISPATCHED', 'RUNNING');
CREATE INDEX IF NOT EXISTS idx_run_queue_state ON run_queue (queue_state);
CREATE INDEX IF NOT EXISTS idx_run_queue_org_id ON run_queue (org_id);
CREATE INDEX IF NOT EXISTS idx_run_queue_schedule_id ON run_queue (schedule_id);
