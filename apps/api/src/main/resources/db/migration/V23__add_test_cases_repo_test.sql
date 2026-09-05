-- V23 (ADR-009 §3): repo-test authoring spec on test_cases.
-- Nullable, unindexed (never queried by content). Same shape as api_request
-- (V9) / browser_test (V10); mutually exclusive with them — enforced in the
-- authoring DTO (@AssertTrue), not the schema.
ALTER TABLE test_cases ADD COLUMN IF NOT EXISTS repo_test JSONB NULL;
