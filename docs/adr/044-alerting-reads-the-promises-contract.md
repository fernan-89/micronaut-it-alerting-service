# ADR-044: Alerting Reads the Promises Contract

## Status
Accepted (2026-10-20). Implements platform ADR-001 (`thinklab-platform/docs/adr/001-the-promises-contract.md`), approved by the owner. Supersedes the per-registry clients and mappings of ADR-037 to ADR-043; what those ADRs say about **what** each source means (the codes, the classes, what resolves an alert) is unchanged.

## Context
Alerting knew nine registries: a client, a DTO of that registry's fields and the rule that turned its verdicts into DOWN, UP or not judged, for each of eight sources. Every new registry meant changes in five places, the rules were not tested with the domain they describe, and the same problem (a reader that must see what *recovered*, not only what is wrong) was solved case by case: a second read of "the most recent" ones, a `presence=MISSING` filter, a `changedWithinDays` filter.

## Decision
- **One adapter** (`PromisesContractAdapter`) reads every registry that answers the contract: `GET <url><path>/promises/retrieve?changedWithinDays=30&limit=500` as the service identity `system:alerting`. The registry returns every broken promise and every one that changed lately, so **one read** shows both what is wrong and what was settled since.
- **Mapping:** verdict `BROKEN` is DOWN, `KEPT` is UP, anything else is not judged; the registry's `code` is the alert's error (only for a broken promise), its `status` is the check's status (only `ACTIVE` can open an alert; a registry reports a promise it wants settled without ever opening one, a terminated resource or a cancelled order, as `KEPT` with another status), its `name` and `assetId` name the alert.
- **Configuration, not code:** `thinklab.alerting.registries.<SOURCE>` gives the service address and the BIAN path of the registry that serves a source (`BACKUP`, `PATCH`, `CAPACITY`, `VENDOR`, `LICENSE`, `DR`, `CLOUD_INVENTORY`, `PROCUREMENT`); the name is matched however Micronaut normalises the key. The fixed list of source names, with the noun used in the incident and the default class (ADR-040), stays in the domain (`AlertRule.Source`), because it is a vocabulary of the API.
- The health checks stay as they are: the health monitor measures them, no registry judges them.
- A registry that cannot be read is skipped for that round, as before: the error says which registry and which HTTP status and never repeats its answer.
- **Deleted:** the eight declarative clients, their DTOs, the mapping of each registry's verdicts and their tests.

## Consequences
- Positive: a new source of alerts is one configuration entry, one line in the domain list and a registry that implements the contract; the rules live with their domain and are tested there; one read replaces two for the registries that needed two.
- Negative: the registries had to change first (each answers the contract since the day this ADR was accepted): a registry that does not yet answer it is a source that is skipped (HTTP 404) until it does. Alerting does not check that a registry's codes are fixed words: that is the registry's contract.
