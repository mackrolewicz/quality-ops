-- V26 (reviewer fix, ADR-009 §7/§13): repository_run telemetry had no epoch
-- guard — a stale/redelivered results.chunk could overwrite newer provenance
-- with older data (repository_test_item already guards this via its own
-- attempt_epoch, V25). Mirrors that pattern: applyTelemetry now guards on
-- `attempt_epoch <= :epoch` and advances it when the write wins.
ALTER TABLE repository_run ADD COLUMN IF NOT EXISTS attempt_epoch INT NOT NULL DEFAULT 0;
