-- V10: optional declarative browser-test spec authored on a test case (Phase 2B2, ADR-004).
-- Nullable ⇒ API/simulated cases are unaffected. Mutually exclusive with api_request
-- (enforced in the application layer). Not indexed (never queried by content).
ALTER TABLE test_cases ADD COLUMN IF NOT EXISTS browser_test JSONB NULL;
