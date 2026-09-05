-- V12: ShedLock coordination table (Phase 2C, ADR-006 §2). Infrastructure
-- coordination, NOT tenant data — deliberately has NO org_id, like
-- flyway_schema_history. Canonical ShedLock JdbcTemplate schema.
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
