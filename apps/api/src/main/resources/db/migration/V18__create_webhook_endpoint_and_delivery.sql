-- V18: signed run-completion webhooks (Phase 2D, ADR-007 §6.4).
-- state is VARCHAR + CHECK (not a PG enum) — consistent with run_queue: states
-- will churn and ALTER ... CHECK is transaction-safe. Both tables carry org_id.
CREATE TABLE IF NOT EXISTS webhook_endpoint (
    id         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     UUID          NOT NULL,
    project_id UUID,                                  -- NULL => all runs in the org
    url        VARCHAR(2048) NOT NULL,
    secret     VARCHAR(255)  NOT NULL,                -- plaintext at rest in 2D (ADR-007 §6.2)
    enabled    BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by UUID          NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_webhook_endpoint_lookup
    ON webhook_endpoint (org_id, project_id) WHERE enabled;

CREATE TABLE IF NOT EXISTS webhook_delivery (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID        NOT NULL,
    webhook_endpoint_id UUID        NOT NULL REFERENCES webhook_endpoint (id) ON DELETE CASCADE,
    run_id              UUID        NOT NULL REFERENCES test_runs (id),
    event_type          VARCHAR(32) NOT NULL,
    payload_json        JSONB       NOT NULL,
    state               VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                            CHECK (state IN ('PENDING', 'DELIVERED', 'EXHAUSTED')),
    attempt             INT         NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error          VARCHAR(500),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (run_id, webhook_endpoint_id)
);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_due
    ON webhook_delivery (next_attempt_at) WHERE state = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_org ON webhook_delivery (org_id);
