# ADR-038: Alert on Agreements About to End

## Status
Accepted. Extends ADR-037 with a fifth source.

## Context
The vendor registry (vendor-registry ADR-031) judges each agreement when read: `NOT_STARTED`, `IN_FORCE`, `EXPIRING` (the last day is within its notice), `EXPIRED` or `PAUSED`. The notice exists so that somebody is told in time; nobody is, unless they open the screen.

## Decision
- A rule can watch the source **`VENDOR`**: `checkId` is one agreement, left out means every agreement of the tenant. The vendor registry is read through `agreement/retrieve` only while an ACTIVE rule watches it, as the service identity, like the other registries.
- **DOWN** when the agreement is `EXPIRING` (`AGREEMENT_EXPIRING`) or `EXPIRED` (`AGREEMENT_EXPIRED`); **UP** when it is `IN_FORCE` (a renewal moved its last day); **not judged** when it is `NOT_STARTED` or `PAUSED` (opens nothing, resolves nothing).
- The alert names the agreement by its **reference** and carries its asset; nothing else from the registry is kept (no vendor name: the rule and the agreement id are enough to find it).
- An agreement that goes from `EXPIRING` to `EXPIRED` keeps the same open alert (one outage, one incident); the incident tells somebody to renew or replace it. A renewal resolves the alert on the next round and puts an internal note on the incident.

## Consequences
- Positive: the notice days of an agreement now reach a person (incident and webhook) without a scheduler in the vendor registry.
- Negative: the alert keeps the error code of when it opened (`AGREEMENT_EXPIRING`) after the agreement expires; the incident text says "is not being kept", which reads oddly for a contract that simply ends.
