# ADR-031: Upstream Services Are Read and Written Through Their Public Routes

## Status
Accepted

## Context
Alerting needs the tenant's checks (health monitor) and must open an incident and comment on it (incident service). It owns neither.

## Decision
- Two declarative `@Client`s behind two ports (`HealthChecksPort`, `IncidentsPort`): `GET /it-health-monitoring/v1/retrieve` and `POST /it-incident-management/v1/initiate` and `/{id}/comment/initiate`. No shared database, no shared library.
- Calls travel with the tenant in `X-Tenant-Id` and with the **service identity `system:alerting`** in `X-Executor`, so the incident's audit trail shows who opened it; the note is sent `internal: true` so a requester never reads it.
- From the monitor's answer alerting keeps only id, name, asset, health, status and the fixed-vocabulary last error. **Never the target**, which can be an internal address.
- A failure becomes `UpstreamUnavailableException` (`ERR-ALR-00502`, 502): the message names which service and the HTTP status and **never repeats the upstream body or the exception text**; the log carries only the exception class.
- URLs come from `HEALTH_MONITORING_SERVICE_URL` and `INCIDENT_SERVICE_URL` (defaults: the local ports).

## Consequences
- Positive: the contracts are the ones already public and documented; either service can be replaced or scaled alone; nothing from upstream leaks into errors or logs.
- Negative: alerting depends on both being reachable; while they are not, the round fails for the tenant and tries again at the next tick.
