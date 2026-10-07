# ADR-034: Reopen a Resolved Alert When the Check Is Down Again Soon

## Status
Accepted. Refines ADR-030 (an alert is one outage; a later outage is a new alert).

## Context
A check that flaps (down, up, down within minutes) opened a new alert and a new incident per dip. Staff then had several incidents for what they see as one problem, and the incident of the first dip was usually still being worked.

## Decision
- A rule carries `reopenWithinMinutes` (0 to 1440; default 30; 0 = never reopen). When a DOWN, ACTIVE check has no OPEN alert and its **newest RESOLVED alert was resolved less than that long ago** (and the alert belongs to that check), the **same alert is reopened** (RESOLVED -> OPEN) instead of a new one being opened.
- Reopening is a guarded write on the status loaded (ADR-033). It increments `reopenCount`, sets `reopenedAt`, clears `resolvedAt` and adds a `REOPENED` audit entry. The partial unique index still holds: a reopening that meets another OPEN alert of the check loses with `DuplicateAlertException` and does nothing.
- The **incident is told, not duplicated**: only if it is still being worked (not RESOLVED, CLOSED or CANCELLED, read through the incident service) does the alert reopen and the incident get an internal note ("down again" with the fixed-vocabulary error). If the incident is finished, or cannot be read, the outage is **a new alert and a new incident**. An alert that never got its incident gets it now, under the alert id as idempotency key.
- The time is the rule's, not the monitor's: the monitor's own thresholds still dampen the first level of flapping.
- Each cycle of the outage (the opening and every reopening) is a cycle of its own for notices and escalation (ADR-036): `activeSince` is the opening or the last reopening.

## Consequences
- Positive: one incident for one flapping problem; the history stays on one alert (`reopenCount`, audit trail); a person's decision to finish the incident is respected.
- Negative: the resolve note stays on the incident while it reopens, so the incident reads "resolved note, then down again"; reopening needs one extra read of the incident, and a down incident service means a new alert rather than a reopening.
