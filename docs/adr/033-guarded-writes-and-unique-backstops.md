# ADR-033: Guarded Writes, and Unique Indexes as the Atomic Backstops

## Status
Accepted

## Context
Several instances evaluate the same tenant, and staff edit rules while rounds run. Checking first and writing later is not atomic.

## Decision
- Every change to an existing rule or alert is **one atomic update guarded by the status that was loaded**, which also appends the audit entry. A lost race is 409 (`ERR-ALR-00409`): read again and decide again. In an evaluation, losing the resolve race means someone else resolved it, and **only the winner comments on the incident**.
- **One OPEN alert per check** is a partial unique index on `(organisationId, checkId)` where `status = OPEN`. The loser of a simultaneous open gets `DuplicateAlertException` and does nothing (no second incident). Resolved alerts are outside the index, so a new outage can open a new alert.
- **A rule name is unique per organisation** (`(organisationId, name)`), on create and on rename.
- Indexes are created at startup, fail-open and idempotent; `thinklab.mongo.create-indexes=false` turns them off. Others serve the real queries: tenants with an active rule, tenants with an open alert, the alert list `(organisationId, status, openedAt desc)`.
- The mongo adapters set the update time themselves and never write what the domain did not ask for.

## Consequences
- Positive: with N instances there is still exactly one alert and one incident per outage; no lost updates.
- Negative: without the index (it is fail-open) the "one open alert" rule would rely on the evaluator's own check only; the startup log says when an index could not be created.
