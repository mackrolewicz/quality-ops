-- V19 (ADR-008 §1-2): indexes backing the on-the-fly analytics window/aggregate queries.
CREATE INDEX IF NOT EXISTS idx_test_results_case_created ON test_results (test_case_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_test_results_run_created  ON test_results (run_id, created_at);
CREATE INDEX IF NOT EXISTS idx_test_results_org_created  ON test_results (org_id, created_at DESC);
