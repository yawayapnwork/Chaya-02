# ADR 0001: Flyway for database migrations

Status: accepted

Choice: Flyway with plain SQL files in `services/api/src/main/resources/db/migration`.

Why: the schema relies on PostgreSQL-specific features (pgvector, CHECK constraints for status enums) that are clearer in native SQL than in Liquibase's changelog abstraction. Flyway is forward-only, matching DEVELOPMENT_RULES. Hibernate is set to `ddl-auto: validate`, so Flyway is the single owner of the schema.

Trade-off: no automatic rollbacks or database-agnostic changesets; we do not need either.

## Also recorded

- Java 21 (LTS) and Spring Boot 4.0.x (Spring Framework 7) for the backend. Moved from 3.5.x on 2026-10-17: the
  open-source fix for CVE-2026-47884 and CVE-2026-47890 is only in Framework 7.0.9; the 6.2.x fix (6.2.20) is
  commercial-only. The API's JSON stays on Jackson 2 (`spring-boot-jackson2`) until a contract-checked move to Jackson 3.
- Web is Next.js (App Router) with strict TypeScript, tests via the Node built-in test runner to avoid an extra dependency until needed.
