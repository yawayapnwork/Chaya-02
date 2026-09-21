# Contracts

Shared, versioned API contracts. `openapi/v1.yaml` describes `/api/v1`. A breaking change means a new `openapi/v2.yaml` and `/api/v2` routes, never an edit to v1.

The contract is currently maintained by hand; generating clients or contract-testing the backend against it is a later milestone.
