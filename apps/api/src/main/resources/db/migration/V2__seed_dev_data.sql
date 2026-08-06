-- V2: Dev seed data — one org, two users (owner + member), one project.
-- Passwords: 'password123' hashed with bcrypt cost 12.
-- ON CONFLICT DO NOTHING makes this idempotent across restarts.

INSERT INTO organizations (id, name, slug)
VALUES ('00000000-0000-0000-0000-000000000001', 'Demo Org', 'demo-org')
ON CONFLICT DO NOTHING;

INSERT INTO users (id, org_id, email, hashed_password, role)
VALUES
    ('00000000-0000-0000-0000-000000000010',
     '00000000-0000-0000-0000-000000000001',
     'owner@demo.com',
     '$2b$12$OXMUKMJZUSpGAX6/rSQSsO0rM0bJrSBe2tqV7NafvFonGiwZ7TSoq',
     'OWNER'),
    ('00000000-0000-0000-0000-000000000011',
     '00000000-0000-0000-0000-000000000001',
     'member@demo.com',
     '$2b$12$OUfJrhIBLBTtZ3fw5M2QieaMlJEgGNaQZo80Aq.8.4yNz2Sx5GwWO',
     'MEMBER')
ON CONFLICT DO NOTHING;

INSERT INTO projects (id, org_id, name, slug, description)
VALUES ('00000000-0000-0000-0000-000000000020',
        '00000000-0000-0000-0000-000000000001',
        'Demo Project',
        'demo-project',
        'Seed project for local development')
ON CONFLICT DO NOTHING;
