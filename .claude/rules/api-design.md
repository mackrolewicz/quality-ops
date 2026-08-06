---
paths:
  - "**/*Controller.java"
  - "**/controller/**/*"
  - "**/web/**/*"
  - "**/api/**/*.ts"
  - "**/api/**/*.tsx"
---
# API Design Rules

- RESTful: resources (nouns), not actions (verbs). Plural nouns.
- All endpoints under `/api/v1/`. Breaking changes → `/api/v2/`.
- Standard HTTP methods: GET (read), POST (create), PUT (update), DELETE (remove).
- Standard status codes: 200, 201, 204, 400, 401, 403, 404, 409, 429, 500.
- Consistent response envelope: `{ "data": {...}, "meta": {...} }`.
- Error envelope: `{ "error": { "code": "...", "message": "...", "details": [] } }`.
- Pagination: `?page=1&size=20` for lists. Include `meta.total`.
- Every endpoint authenticated except `/actuator/health` and `/auth/**`.
- Rate limiting headers on every response: X-RateLimit-Limit, Remaining, Reset.
- `@Valid` on all request bodies. Return 400 with field-level errors.
- Never expose internal IDs, stack traces, or tenant data in error messages.
- OpenAPI annotations on all endpoints for auto-generated docs.
