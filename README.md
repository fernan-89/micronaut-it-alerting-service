# micronaut-it-alerting-service

BIAN-aligned Service Domain **it-alerting** (Control Record: `Alert`), port `8104`.

The second slice of Journey 14 (operations): when something the health monitor watches goes down, IT staff get an **incident**, once, and a
note when it is back. An `AlertRule` says what to do (which check, with what impact and urgency, for which requester); an `Alert` is one
outage of one check, opened and resolved by this service itself, never by hand.

## What it guarantees, and what it does not

- **One alert and one incident per outage** (ADR-030): a scheduler (every 5 s) reads the tenant's checks from the health monitor. A check
  seen `DOWN` with an ACTIVE rule covering it gets an alert and an incident; while it stays down nothing more is opened; when it is seen
  `UP` the alert is `RESOLVED` and the incident gets an **internal note**. The incident is **never closed automatically**.
- **The oldest ACTIVE rule that covers the check wins.** A paused rule opens nothing, but never keeps an open alert from being resolved.
- **Alert first, incident second** (ADR-030): if the incident service is down the alert stays open without an incident and the next round
  retries. Nothing is lost on a restart: the state is the alerts.
- **Safe with several instances** (ADR-033): one OPEN alert per check is a partial unique index; every change is guarded by the status
  loaded; the loser of a race does nothing (only the winner of a resolve comments on the incident).
- **Nothing leaks from upstream** (ADR-031): the target of a check is never read, and an error says which service and which HTTP status,
  never what it answered. Calls go through the public routes as the service identity `system:alerting`.
- **Staff only** (ADR-032): every route refuses a `REQUESTER` with 403 `ERR-ALR-00403`; another tenant's rule or alert answers 404.
- **Not here yet:** notifying people (email, chat, paging), escalation, silencing during maintenance, grouping many checks into one
  incident, closing the incident when the check recovers, events instead of polling. A check that flaps opens a new alert and incident per
  outage (the monitor's thresholds are what dampens it). If the process dies between the incident being created and its id being saved, that
  incident can be duplicated on the retry.

## BIAN Behavior Qualifier Contract

`X-Tenant-Id` is mandatory on every call; `X-Executor` is mandatory on the actions; `X-Role` is optional and, with platform security on,
comes from the verified token.

| Behavior Qualifier | Route |
|---|---|
| rule/initiate | `POST /it-alerting/v1/rule/initiate` `{"name":"Production down","checkId":"<uuid, optional>","impact":"HIGH","urgency":"MEDIUM","requesterId":"<uuid>"}` (no `checkId` = every check) |
| rule/retrieve | `GET /it-alerting/v1/rule/{id}/retrieve` |
| rule/retrieve (collection) | `GET /it-alerting/v1/rule/retrieve` (oldest first) |
| rule/update | `PUT /it-alerting/v1/rule/{id}/update` (the whole definition again, in any status) |
| rule/control/pause | `PUT /it-alerting/v1/rule/{id}/control/pause` (ACTIVE -> PAUSED) |
| rule/control/resume | `PUT /it-alerting/v1/rule/{id}/control/resume` (PAUSED -> ACTIVE) |
| rule/audit-log/retrieve | `GET /it-alerting/v1/rule/{id}/audit-log/retrieve` |
| retrieve | `GET /it-alerting/v1/{id}/retrieve` |
| retrieve (collection) | `GET /it-alerting/v1/retrieve?status=&checkId=` (newest first) |
| audit-log/retrieve | `GET /it-alerting/v1/{id}/audit-log/retrieve` |
| evaluation/execute | `PUT /it-alerting/v1/evaluation/execute` (looks at the tenant right now, as the scheduler does: `{"opened":1,"resolved":0,"incidentsOpened":1}`; 502 when the monitor cannot be read) |

```text
rule:   ACTIVE <-> PAUSED
alert:  OPEN -> RESOLVED      (OPEN may or may not have an incident yet; `problem` says why not)
```

Impact and urgency are `LOW`, `MEDIUM` or `HIGH`, as the incident service takes them. The requester is the person the incident is filed
for (the incident service requires one).

```bash
curl -X PUT "http://localhost:8104/it-alerting/v1/evaluation/execute" -H "X-Tenant-Id: <organisationId>" -H "X-Executor: <userId>"
```

## Error catalog

| Code | HTTP | Meaning |
|---|---|---|
| `ERR-ALR-00403` | 403 | A REQUESTER used alerting (ADR-032) |
| `ERR-ALR-00404` | 404 | Rule or alert not found (another tenant's answers the same) |
| `ERR-ALR-00409` | 409 | Duplicate rule name, illegal transition (pause a paused rule), or it changed while the write was applied (retry) |
| `ERR-ALR-00502` | 502 | The health monitor or the incident service could not be reached or refused (ADR-031) |
| `ERR-VALIDATION-00400` | 400 | Payload/header/identifier validation failure |
| `ERR-INTERNAL-00500` | 500 | Unexpected technical failure |

## Configuration

`HEALTH_MONITORING_SERVICE_URL` (default `http://localhost:8103`), `INCIDENT_SERVICE_URL` (default `http://localhost:8098`),
`thinklab.alerting.scheduler-enabled` (env `THINKLAB_ALERTING_SCHEDULER_ENABLED`, default true: turn it off for an instance that only
serves the API), `tick` (5s) and `concurrency` (5 tenants at a time).

Do not put personal data in a rule name: it is stored with the rule.

## Architecture decisions

001 hexagonal architecture · 005 UUID identity sovereignty and audit tracing · 013 BIAN conventions · 019 HTTP 409 for state conflicts ·
030 one alert and one incident per outage, found by polling · 031 upstreams through public routes · 032 staff only · 033 guarded writes and
unique backstops.

## License

Licensed under the [PolyForm Strict License 1.0.0](LICENSE): you may read and use this software for noncommercial purposes only. Modifying it, creating derivative works, redistributing it and any commercial use are not permitted without a separate written license. This software is not open source.
