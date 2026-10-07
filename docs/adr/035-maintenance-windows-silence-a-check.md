# ADR-035: Maintenance Windows Silence a Check

## Status
Accepted. Refines ADR-030.

## Context
Planned work takes checks down on purpose. Without a way to say so, every planned restart opened an incident and woke people up.

## Decision
- A **MaintenanceWindow** (`/window/...`, second aggregate of the Service Domain, staff only like everything else, ADR-032) has a name (up to 80 characters), an optional `checkId` (none = every check of the tenant), `startsAt` and `endsAt`. It lasts at most 30 days, must end after it starts and **cannot end in the past**. The start is included and the end is not.
- While an ACTIVE window covers a check **no alert is opened or reopened and no `OPENED`, `REOPENED` or `ESCALATED` notice is sent** for it. The silence is judged at the moment of each round, not stored on alerts.
- A window **never resolves anything and never hides a recovery**: a check that comes back is still resolved, the resolution is still recorded and its `RESOLVED` notice is still sent. An alert already open when the window starts stays open.
- A window is **cancelled, never edited or deleted** (ACTIVE -> CANCELLED, guarded write, audit trail), so what happened is what the trail says; the silence stops at once. Planning and cancelling go through the Behavior Qualifiers `window/initiate` and `window/{id}/control/cancel`; `window/retrieve` lists newest start first.
- Indexed on `(organisationId, status, endsAt)`: a round reads the windows that are ACTIVE and have not ended.

## Consequences
- Positive: planned work opens no incident and sends no notice; the audit shows who planned the silence and who cancelled it.
- Negative: a check that stays down after the window ends is alerted on the next round, not before; a window cannot be shortened, only cancelled and planned again.
