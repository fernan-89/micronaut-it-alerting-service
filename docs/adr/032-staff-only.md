# ADR-032: Alerting Is for IT Staff; Alerts Are Never Written by Hand

## Status
Accepted

## Context
Alerts and rules describe the health of internal systems and who is paged for them. A requester (end user) has no business reading them.

## Decision
- Every route refuses a `REQUESTER` with 403 `ERR-ALR-00403`; any other role, or none, is staff (the same rule as the monitor and the incident service).
- Every lookup is scoped by `X-Tenant-Id`: another tenant's alert or rule is a 404, never a 403.
- **Alerts are read-only through the API.** They are opened and resolved only by the evaluation (scheduler or `evaluation/execute`), so the lifecycle of ADR-030 cannot be bypassed. Rules can be created, updated, paused and resumed; there is no delete (a rule is paused, so its history stays).
- Audit is kept on both aggregates, only for real events (rule created/updated/paused/resumed; alert opened, incident linked, resolved). It carries the executor id, never a person's data or anything a target said.

## Consequences
- Positive: one rule for access across the three ITSM-operations services; no way to forge or hide an alert.
- Negative: closing an alert early means fixing the check, not editing the alert.
