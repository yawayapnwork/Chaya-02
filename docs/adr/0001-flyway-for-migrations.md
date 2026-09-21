# ADR 0001: Flyway for database migrations

Status: accepted

Choice: Flyway with plain SQL files in `services/api/src/main/resources/db/migration`.

Why: the schema relies on PostgreSQL-specific features (pgvector, CHECK constraints for status enums) that are clearer in native SQL than in Liquibase's changelog abstraction. Flyway is forward-only, matching DEVELOPMENT_RULES. Hibernate is set to `ddl-auto: validate`, so Flyway is the single owner of the schema.

Trade-off: no automatic rollbacks or database-agnostic changesets; we do not need either.

## Also recorded

- Java 21 (LTS) and Spring Boot 3.5.x for the backend.
- Web is Next.js (App Router) with strict TypeScript, tests via the Node built-in test runner to avoid an extra dependency until needed.
