# ADR-030: One Alert and One Incident per Outage, Found by Polling

## Status
Accepted

## Context
The health monitor (it-health-monitoring) knows when a check is DOWN or UP. Someone has to turn an outage into work for IT staff: an incident, once, with the right priority, and a note when the service is back. Doing it inside the monitor would couple monitoring to the incident process, and one incident per failed probe would flood the queue.

## Decision
- **Polling, not events.** A scheduler runs a round every few seconds (`thinklab.alerting.tick`, default 5s); a round still running when the next tick comes is skipped. The monitor is not changed and publishes nothing. Tenants to look at are the **active rules ∪ open alerts**, so a tenant whose last rule was paused is still looked at until its open alerts are resolved. `PUT /evaluation/execute` runs the same evaluation for one tenant on demand.
- **An `Alert` is one outage of one check.** OPEN → RESOLVED, terminal. At most one OPEN alert exists per `(organisation, check)` (partial unique index, ADR-033). When the check is seen UP the alert is RESOLVED; a later outage is a new alert.
- **An `AlertRule` says what to do:** it covers one check or all of them, and carries impact, urgency and the requester the incident is filed for. The **oldest ACTIVE rule that covers the check wins**; a PAUSED rule opens nothing but never stops an open alert from being resolved.
- **Alert first, incident second.** The alert is saved before the incident is opened. If the incident service is down the alert stays OPEN without an incident and records a short fixed `problem`; the next round retries while the check is still DOWN. If the process dies between the incident and saving its id, that incident can be duplicated on the retry (accepted, see Consequences).
- **Recovery only comments.** On RESOLVED the incident gets an **internal** note; it is never closed automatically: a person decides that the cause is understood.
- The evaluation counts what it did (`opened`, `resolved`, `incidentsOpened`) and one tenant failing (monitor unreachable → 502) never stops the other tenants in a round.

## Consequences
- Positive: one incident per outage however long it lasts; no change to the monitor; works with several instances; a restart loses nothing (the state is the alerts).
- Negative: detection takes up to one tick plus the monitor's own thresholds; a flapping check opens a new alert and incident per outage (the monitor's thresholds are what dampens that); the crash window above can duplicate an incident.
