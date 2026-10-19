# ADR-039: Alert on Licences Over Their Limit

## Status
Accepted. Extends ADR-037 and ADR-038 with a sixth source.

## Context
The licence registry (license-registry ADR-031) judges each licence when read: `OK`, `NEAR_LIMIT`, `OVER_LIMIT`, `NO_DATA` or `PAUSED`. Using more seats than were bought is a compliance problem that somebody must act on (buy more, or free seats), and nobody is told unless they open the screen.

## Decision
- A rule can watch the source **`LICENSE`**: `checkId` is one licence, left out means every licence of the tenant. The licence registry is read through its `retrieve` route only while an ACTIVE rule watches it, as the service identity.
- **DOWN** only when the licence is `OVER_LIMIT` (`LICENSE_OVER_LIMIT`). `OK` and `NEAR_LIMIT` are **UP**: near the limit is a warning for the screen, not an incident. `NO_DATA` and `PAUSED` are not judged (open nothing, resolve nothing).
- The alert is named by the product and carries no asset (a licence covers no single asset). Nothing else from the registry is kept: no count, so the incident says "is not being kept (LICENSE_OVER_LIMIT)" without numbers that would go stale.
- When seats are freed or more are bought (the licence is back to `OK` or `NEAR_LIMIT`), the next round resolves the alert and notes it on the incident.

## Consequences
- Positive: an over-used licence reaches a person (incident and webhook) without a scheduler in the licence registry.
- Negative: a licence that hovers around its limit will open and resolve alerts as the counts move (the reopening of ADR-034 keeps that on one incident while it is being worked).
