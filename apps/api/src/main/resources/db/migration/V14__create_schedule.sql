-- V14: recurring/one-time run schedules + the per-occurrence dedup ledger
-- (Phase 2C, ADR-006 §1). kind/priority/catch_up_policy are VARCHAR+CHECK.
-- next_fire_at is a materialised absolute instant so the tick query is a pure
-- indexed range scan (no cron parsing on the hot path).
CREATE TABLE IF NOT EXISTS schedule (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID         NOT NULL,
    project_id      UUID         NOT NULL REFERENCES projects (id),
    suite_id        UUID         NOT NULL REFERENCES test_suites (id),
    environment_id  UUID         NOT NULL REFERENCES environments (id),
    name            VARCHAR(200) NOT NULL,
    kind            VARCHAR(16)  NOT NULL CHECK (kind IN ('ONE_TIME', 'RECURRING')),
    cron_expression VARCHAR(120),
    time_zone       VARCHAR(64),
    fire_at         TIMESTAMPTZ,
    priority        VARCHAR(16)  NOT NULL DEFAULT 'NORMAL'
                        CHECK (priority IN ('HIGH', 'NORMAL', 'LOW')),
    catch_up_policy VARCHAR(16)  NOT NULL DEFAULT 'SKIP_MISSED'
                        CHECK (catch_up_policy IN ('SKIP_MISSED', 'FIRE_ONCE')),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    next_fire_at    TIMESTAMPTZ,
    last_fired_at   TIMESTAMPTZ,
    last_error      TEXT,
    last_error_at   TIMESTAMPTZ,
    created_by      UUID         NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_schedule_org ON schedule (org_id);
CREATE INDEX IF NOT EXISTS idx_schedule_project ON schedule (project_id);
CREATE INDEX IF NOT EXISTS idx_schedule_due
    ON schedule (next_fire_at) WHERE enabled AND next_fire_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS schedule_fire (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID        NOT NULL,
    schedule_id UUID        NOT NULL REFERENCES schedule (id) ON DELETE CASCADE,
    fire_slot   TIMESTAMPTZ NOT NULL,
    run_id      UUID        REFERENCES test_runs (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (schedule_id, fire_slot)
);
CREATE INDEX IF NOT EXISTS idx_schedule_fire_schedule ON schedule_fire (schedule_id);
CREATE INDEX IF NOT EXISTS idx_schedule_fire_created_at ON schedule_fire (created_at);

-- schedule_id FK deferred from V13 until `schedule` exists.
ALTER TABLE run_queue
    ADD CONSTRAINT fk_run_queue_schedule
    FOREIGN KEY (schedule_id) REFERENCES schedule (id);
