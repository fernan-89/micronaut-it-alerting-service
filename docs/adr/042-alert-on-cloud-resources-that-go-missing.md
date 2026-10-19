# ADR-042: Alert on Cloud Resources That Go Missing

## Status
Accepted

## Context
The cloud inventory judges, when read, whether each resource is still seen by the discovery tool (inventory ADR-032: `PRESENT`, `MISSING` or `GONE`). A resource that vanishes from the tool's sight (deleted by someone, an account that lost access, a broken sync) is only noticed by whoever opens the list. The alerting service already turns the verdicts of the other registries into one alert and one incident (ADR-037 to ADR-041).

## Decision
- A rule can watch the source **`CLOUD_INVENTORY`**: `checkId` is one resource, left out means every resource of the tenant. The inventory is read through its `retrieve` route (the 500 that are missing, asked of the inventory itself, and the 500 most recently seen, which hold what has come back or been terminated; one in both is read once) only while an ACTIVE rule watches it, as the service identity.
- **DOWN** when the resource is `MISSING` (`CLOUD_RESOURCE_MISSING`). **UP** when it is `PRESENT`, and also when it is `GONE`: the tool said it was terminated, so it was removed on purpose and an alert about its earlier absence is resolved. A value the alerting service does not know is not judged.
- A terminated resource is passed on with the status `TERMINATED`, not `ACTIVE`, so it never opens an alert; any other has the status `ACTIVE`.
- The alert is named by the resource and carries no asset (the inventory has none). Only the fixed code is kept (never an id of the provider, a region or a tag), so the incident says "is not being kept (CLOUD_RESOURCE_MISSING)".
- When the tool sees the resource again, it is `PRESENT`: the next round resolves the alert and notes it on the incident.
- The default class of such an alert is `AVAILABILITY` (ADR-040): something that was running is no longer seen.
- An inventory that cannot be read is skipped for that round, as for the other registries.

## Consequences
- Positive: a resource that disappears becomes work for the team, with an owner and a trail, with no new mechanism.
- Negative: a tool that is down makes every resource `MISSING` after the allowed silence (48 hours by default) and opens one incident per resource; the time allowed is the only knob, and a rule for a single resource is the way to watch only the important ones. Beyond 500 missing resources at once, the rest are not read in that round (they are read as the first are resolved); a resource with an open alert that comes back is among the most recently seen.
