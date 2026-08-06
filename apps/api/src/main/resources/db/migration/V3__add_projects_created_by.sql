-- V3: Add created_by tracking to projects (nullable FK to users)

ALTER TABLE projects ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES users (id);
CREATE INDEX IF NOT EXISTS idx_projects_created_by ON projects (created_by);
