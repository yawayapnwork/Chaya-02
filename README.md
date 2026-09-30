# Chaya 02

Digital-twin platform for physical venues: State 01 (the venue) -> State 02 (its living digital reconstruction).

Status: foundation. Web status page, backend `/api/v1/health` and `/api/v1/version`, and local infrastructure exist. No business functionality yet.

- [ARCHITECTURE.md](ARCHITECTURE.md) - system design
- [PROJECT_PLAN.md](PROJECT_PLAN.md) - milestones
- [DEVELOPMENT_RULES.md](DEVELOPMENT_RULES.md) - engineering rules
- [.env.example](.env.example) - configuration template

Layout: `apps/web`, `services/api`, `packages/contracts`, `infra/docker`, `docs/`. Other directories from the target monorepo tree are created when needed.

## Run locally
```
cp .env.example .env            # then fill in the blank values
docker compose --env-file .env -f infra/docker/docker-compose.yml up -d
cd services/api && mvn spring-boot:run     # needs JDK 21 and the POSTGRES_* variables exported
cd apps/web && npm install && CHAYA_API_BASE_URL=http://localhost:8080 npm run dev
```
Checks: `cd apps/web && npm run lint && npm run typecheck && npm test && npm run build`; `cd services/api && mvn verify`.
What a clean checkout runs, and what CI does not execute: [docs/TEST_REPRODUCIBILITY.md](docs/TEST_REPRODUCIBILITY.md).
