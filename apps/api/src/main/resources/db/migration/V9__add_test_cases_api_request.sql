-- V9: optional API-request spec authored on a test case (Phase 2B1).
-- Nullable ⇒ UI/placeholder cases stay simulated. Not indexed (never queried by content).
ALTER TABLE test_cases ADD COLUMN IF NOT EXISTS api_request JSONB NULL;
