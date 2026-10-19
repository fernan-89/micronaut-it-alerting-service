# ADR-041: Alert on Recovery Plans That Stop Being Ready

## Status
Accepted

## Context
The DR registry judges, when read, whether each recovery plan is proved (DR registry ADR-031). Nobody is told when a plan stops being ready: the verdict is only seen by whoever opens the screen. The alerting service already turns the verdicts of the backup, patch, capacity, vendor and licence registries into one alert and one incident (ADR-037 to ADR-039).

## Decision
- A rule can watch the source **`DR`**: `checkId` is one recovery plan, left out means every plan of the tenant. The DR registry is read through its `retrieve` route only while an ACTIVE rule watches it, as the service identity.
- **DOWN** when the plan is `LAST_EXERCISE_FAILED` (`DR_EXERCISE_FAILED`), `OBJECTIVE_MISSED` (`DR_OBJECTIVE_MISSED`) or `TEST_OVERDUE` (`DR_TEST_OVERDUE`). **UP** when it is `READY`. `NEVER_TESTED` (still inside its first interval) and `PAUSED` are not judged: they open nothing and resolve nothing.
- The alert is named by the plan and carries its asset. Only a fixed code is kept (never a recovery time, a reason or a runbook reference), so the incident says "is not being kept (DR_OBJECTIVE_MISSED)" without numbers that would go stale.
- When a later exercise passes inside the objectives, or the objectives are changed to ones the last pass meets, the plan is `READY` again: the next round resolves the alert and notes it on the incident. A plan that stays broken opens nothing more.
- The default class of such an alert is `PROTECTION` (ADR-040), like backups and patches.
- A DR registry that cannot be read is skipped for that round, as for the other registries.

## Consequences
- Positive: a plan that stops being ready becomes work for the team, with an owner, a maintenance window and a trail, with no new mechanism.
- Negative: an overdue exercise opens an incident the day it falls due; the interval of the plan is the only knob (a plan that is exercised rarely on purpose should have a long interval). The incident does not say which of the three things happened beyond the fixed code.
