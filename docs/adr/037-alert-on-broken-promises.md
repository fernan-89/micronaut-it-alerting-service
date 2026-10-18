# ADR-037: Alert on Broken Promises

## Status
Accepted. Extends ADR-030 and ADR-031 (alerting looked only at health checks).

## Context
The backup, patch and capacity registries judge a promise **when it is read** (backup ADR-028, patch ADR-029, capacity ADR-031): a backup policy is `RPO_BREACHED`, a patch policy is `OVERDUE`, a capacity policy is `CRITICAL`. Nothing is stored about that verdict, so nobody is told unless somebody opens the screen. Alerting already knows how to turn "something is down" into one alert, one incident, a reopening on flapping (ADR-034), a silence (ADR-035) and webhook notices (ADR-036).

## Decision
- A rule has a **`source`**: `HEALTH_CHECK` (the default, and what every rule stored before this ADR is), `BACKUP`, `PATCH` or `CAPACITY`. For a registry `checkId` is the id of **one policy**; left out it means every policy of that registry. A rule covers a thing only of its own source.
- A registry is read **only when an ACTIVE rule watches it**, through its public `retrieve` route as the service identity `system:alerting` (the same way as the health monitor, ADR-031). A policy is told to the evaluator as an observed check:
  - **DOWN** (the promise is broken): backup `RPO_BREACHED` (`BACKUP_RPO_BREACHED`) or restore test `OVERDUE` (`BACKUP_RESTORE_TEST_OVERDUE`); patch `OVERDUE` (`PATCH_OVERDUE`); capacity `CRITICAL` (`CAPACITY_CRITICAL`).
  - **UP** (kept): backup `OK`; patch `COMPLIANT`; capacity `OK` or `WARNING`.
  - **Not judged**: a paused policy, a backup that has `NEVER` run yet, a capacity policy with `NO_DATA`. It opens nothing and **resolves nothing**: the alert it had stays as it is.
  - The error is one of those fixed codes, never a value read from the registry, so nothing from a registry reaches an alert, an incident or a webhook except the policy name and its asset.
- Everything after that is the existing evaluation, unchanged: one alert per policy (the unique index is per tenant and id), the incident under the alert id as idempotency key, reopening, maintenance windows (a window's `checkId` may be a policy id), notices and escalation. The incident reads "Promise broken: <policy>" and "The backup policy <policy> is not being kept (BACKUP_RPO_BREACHED)".
- **A registry that cannot be read is skipped in that round** (logged with its fixed reason): its open alerts stay as they are, no alert is resolved on a guess, and the health checks are not held back. The health monitor failing still fails the round, as before.
- **Capacity alerts on `CRITICAL` only.** The forecast (`WITHIN_HORIZON`) is a prediction, and an incident for every steep line would be noise; the screen still shows it.
- A rule's `source` is part of its definition, so `rule/update` takes the whole definition again (a `source` left out is `HEALTH_CHECK`). Rules and audit entries stay free of any address or secret.

## Consequences
- Positive: a missed backup, an overdue patch or a full disk becomes an incident and a webhook without a new service, a new store or a second copy of the opening, reopening and notice logic; the registries stay "registry only".
- Negative: a registry is polled every round while a rule watches it; the alert texts still say "Check ... is down" for a promise; one level (CRITICAL) for capacity; a policy deleted from a registry cannot happen (no DELETE), but a registry that stays unreachable leaves its alerts open silently apart from the log.
