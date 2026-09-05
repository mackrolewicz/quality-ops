-- V8: persistent execution-attempt identity for lifecycle-event guarding (Phase 2B1)

ALTER TABLE test_runs ADD COLUMN IF NOT EXISTS execution_id UUID;

-- Backfill pre-2B1 rows (dev/CI only; lab has no prod data).
UPDATE test_runs SET execution_id = gen_random_uuid() WHERE execution_id IS NULL;

ALTER TABLE test_runs ALTER COLUMN execution_id SET NOT NULL;

ALTER TABLE test_runs
  ADD CONSTRAINT uq_test_runs_execution_id UNIQUE (execution_id);

CREATE INDEX IF NOT EXISTS idx_test_runs_execution_id ON test_runs (execution_id);
