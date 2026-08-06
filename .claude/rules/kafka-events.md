---
paths:
  - "**/event/**/*"
  - "**/consumer/**/*"
  - "**/producer/**/*"
  - "**/messaging/**/*"
  - "**/kafka/**/*"
  - "**/*Event.java"
  - "**/*Consumer.java"
  - "**/*Publisher.java"
  - "**/*Listener.java"
---
# Kafka Event Rules

- Topic naming: `<domain>.<action>` (e.g., `runs.requested`, `results.chunk`).
- Events are Java records. Immutable, serializable, self-contained.
- ALWAYS include `orgId` in every event for tenant isolation.
- ALWAYS include a timestamp field.
- Use entity ID as Kafka message key (ordering guarantee per entity).
- Consumers MUST be idempotent — processing the same event twice is safe.
- Configure dead-letter topics (DLT) for failed messages.
- JSON serialization with Spring Kafka `JsonSerializer`/`JsonDeserializer`.
- Save to DB first, then publish event. Not the other way around.
- Events are facts (past tense) or commands (imperative). Name clearly.
- Never change a published event schema. Add new fields with defaults.
