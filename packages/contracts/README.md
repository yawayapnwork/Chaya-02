# Contracts

Shared, versioned API contracts. `openapi/v1.yaml` describes `/api/v1`. A breaking change means a new `openapi/v2.yaml` and `/api/v2` routes, never an edit to v1.

## `openapi/v1.yaml` is generated

Do not edit it by hand. The backend generates it from its controllers with springdoc. The generated document is served to authenticated callers at `GET /api/v1/openapi` (JSON) and `/api/v1/openapi.yaml`; this file is that output, committed.

- **Paths, parameters, request and response schemas** come from the controller mappings and the DTO records they take and return.
- **Summaries and operation-specific errors** come from `@Operation` and `@ProblemResponse` on each handler, next to the code they describe.
- **Security** comes from the code that enforces it. Public endpoints are `security: []` per `SecurityConfig.isPublic`. Every other operation lists `bearerAuth`. `viewerToken` (`X-Chaya-Viewer-Token`) is listed only where `@PreAuthorize` allows `PUBLIC_VIEWER`, and `x-chaya-roles` lists the roles `@PreAuthorize` allows.
- **Common errors** (400, 401, 403, 404, 429, 503) are added by `dev.chaya.api.web.OpenApiConfig` from the filters and the exception handler that produce them. Every error body is an RFC 9457 problem (`#/components/schemas/Problem`) with a stable `code`, except 401 (empty body) and `/health`'s 503 (a `HealthReport`).

## Regenerating

After changing a controller, DTO or annotation, run from `services/api`:

```
mvn test -Dtest=OpenApiContractTest -Dchaya.openapi.write=true
```

Then review the diff of `v1.yaml`. Write mode refuses to drop an operation that the committed file has.

## What fails the build

Backend, `services/api` (`OpenApiContractTest`, part of `mvn verify`):

- a controller mapping missing from the generated document, or a documented operation that no controller serves;
- an operation of the committed `v1.yaml` that the API no longer serves (a breaking change);
- `v1.yaml` differing from the generated document;
- an unauthenticated call that is not answered 401 where the document says credentials are needed (or is answered 401 where the document says the operation is public);
- a `viewerToken` offer that disagrees with the roles, an operation without a success response, an error response that is not a problem;
- a real response with a property that its documented schema does not have, or without one that the schema requires (version, health, venues, floors, ops overview and access);
- two DTOs that share a schema name (generation fails; give one `@Schema(name = ...)`).

Web client, `apps/web` (`lib/api-contract.test.ts`, part of `npm test`): a backend path that the client calls and that is not in `v1.yaml`.
