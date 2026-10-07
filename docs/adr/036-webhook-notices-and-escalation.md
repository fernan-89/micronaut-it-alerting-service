# ADR-036: Webhook Notices and Escalation

## Status
Accepted. Refines ADR-030 ("no notifications to people yet").

## Context
An incident is work for a queue; someone also has to be told that an outage began, that it ended, and that nobody picked up the incident.

## Decision
- A rule can name an **environment variable** as `notifyTarget` and, optionally, as `escalateTarget` with `escalateAfterMinutes` (1 to 1440). The name must match `THINKLAB_ALERT_HOOK_[A-Z0-9_]{1,40}`. The **address is read from the environment when it is used and is never stored, logged, audited or returned**; only the name travels with the rule (the same rule as the external ticketing connections).
- Notices are `OPENED` / `REOPENED` (an OPEN alert, as soon as its incident is linked, or one minute after it began so a down incident service never keeps people in the dark), `RESOLVED` (for an alert resolved in the last hour) and `ESCALATED` (the incident is still `NEW` `escalateAfterMinutes` after the outage began, sent to the escalation target). One notice per event and per **cycle** of the outage (`OPENED_0`, `REOPENED_1`, `ESCALATED_1`, ADR-034).
- The escalation looks at the incident: if somebody acknowledged it the notice is **marked as not needed** (`not needed: the incident was acknowledged`) and not looked at again; if the incident cannot be read the reason is kept and a later round tries again.
- A notice is sent by **the one caller that wins an atomic claim** on the alert (not sent, fewer than 3 attempts, not tried in the last 30 s, and the attempt is counted in the same update), so any number of instances send it once. A failure is kept on the alert as a **fixed reason** and **never fails the evaluation**; after 3 attempts it is given up.
- The webhook must be **https**; plain http only for a host the operator lists in `thinklab.alerting.insecure-hosts` (a test double). The body carries ids, the check name, the monitor's fixed-vocabulary error, the severities and a Slack/Teams style `text` line: never a target address, never a person. A failure says what happened and which status, and never repeats what the webhook answered.
- A maintenance window (ADR-035) silences notices and escalation.

## Consequences
- Positive: people are told once per event, whatever the number of instances; secrets stay in the environment; a broken webhook cannot break alerting.
- Negative: delivery is at most once per attempt window and may be given up after 3 failures (visible on the alert); there is no email, paging or per-person routing, and the escalation is a single level.
