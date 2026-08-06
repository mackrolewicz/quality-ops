---
paths:
  - "**/db/migration/**/*.sql"
  - "**/migrations/**/*.sql"
  - "**/flyway/**/*.sql"
  - "**/init-db.sql"
  - "**/seed-data.sql"
---
# Database Migration Rules

- Flyway naming: `V{number}__{description}.sql` (double underscore).
- NEVER modify an existing migration that has been applied.
- Every table MUST have `org_id UUID NOT NULL` (or inherit through FK).
- Use `TIMESTAMPTZ` for all timestamps, not `TIMESTAMP`.
- Always include `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`.
- Use UUID primary keys (`id UUID PRIMARY KEY DEFAULT gen_random_uuid()`).
- Add indexes on foreign keys and frequently queried columns.
- Include `IF NOT EXISTS` for safety on CREATE statements.
- Soft deletes: use `deleted_at TIMESTAMPTZ NULL` for audit-sensitive entities.
- Add partial index `WHERE deleted_at IS NULL` for soft-deleted tables.
- Test migration against an empty database AND an existing one.
