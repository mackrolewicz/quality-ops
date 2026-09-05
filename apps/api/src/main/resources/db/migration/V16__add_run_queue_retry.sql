-- V16: queue-driven retry linkage on run_queue (Phase 2D, ADR-007 §2.4).
-- Columns on run_queue (not a sibling table): run_queue is already 1:1 with
-- test_runs and the retry linkage/count are per-queue-row facts with no
-- independent lifecycle. The row already carries org_id.
ALTER TABLE run_queue ADD COLUMN IF NOT EXISTS retry_of    UUID REFERENCES run_queue (run_id);
ALTER TABLE run_queue ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_run_queue_retry_of
    ON run_queue (retry_of) WHERE retry_of IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_run_queue_retry_window
    ON run_queue (org_id, created_at) WHERE retry_of IS NOT NULL;
