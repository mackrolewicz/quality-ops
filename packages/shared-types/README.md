# Shared Types

Shared DTOs, API contracts, and schemas used across apps.

## Purpose
- Define API request/response types shared between frontend and backend.
- OpenAPI spec generation (later).
- Contract testing support.

> **Note:** Kafka event contracts (producer/consumer wire records) live in the
> separate `packages/shared-events` module (`com.qualityops.events`), not here.
> This module is for HTTP/OpenAPI DTOs shared between `apps/web` and `apps/api`.

## Usage
This package is referenced by `apps/web` for TypeScript types and by
`apps/api` for Java DTOs (via shared OpenAPI spec or manual sync).

In Phase 1, types are manually kept in sync. In later phases, generate
TypeScript types from the OpenAPI spec.
