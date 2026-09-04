-- V20 (ADR-008 §3): operational health columns (VARCHAR+CHECK, NOT the admin environment_status enum) + probe history.
ALTER TABLE environments
    ADD COLUMN IF NOT EXISTS health_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (health_status IN ('UNKNOWN','HEALTHY','DEGRADED','DOWN')),
    ADD COLUMN IF NOT EXISTS last_probe_at        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_healthy_at      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS consecutive_failures INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS environment_health_check (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         UUID        NOT NULL,
    environment_id UUID        NOT NULL REFERENCES environments (id),
    project_id     UUID        NOT NULL,
    checked_at     TIMESTAMPTZ NOT NULL,
    health_status  VARCHAR(16) NOT NULL CHECK (health_status IN ('UNKNOWN','HEALTHY','DEGRADED','DOWN')),
    http_status    INT,
    latency_ms     INT,
    error_detail   VARCHAR(500),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_env_health_check_env ON environment_health_check (environment_id, checked_at DESC);
CREATE INDEX IF NOT EXISTS idx_env_health_check_org ON environment_health_check (org_id);
