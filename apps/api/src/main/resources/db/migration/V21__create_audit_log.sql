-- V21 (ADR-008 §7): durable org-scoped audit trail written by AuditRecorder (REQUIRES_NEW).
CREATE TABLE IF NOT EXISTS audit_log (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID        NOT NULL,
    actor_user_id UUID,
    action        VARCHAR(64) NOT NULL,
    target_type   VARCHAR(64),
    target_id     UUID,
    outcome       VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' CHECK (outcome IN ('SUCCESS','FAILURE')),
    detail        JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_audit_log_org_created ON audit_log (org_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_action      ON audit_log (action);
