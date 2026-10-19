# ADR-040: Alerts Are Classified From a Fixed List

## Status
Accepted

## Context
An alert says which check or policy is in trouble, but not what kind of trouble it is or which part of the estate it concerns. Once rules cover six sources, a person reading the list, or a tool filtering it, has nothing to group by except the rule's free-text name.

## Decision
- **Class**, from a fixed list: `AVAILABILITY` (something is not answering), `CAPACITY` (a resource is running out), `PROTECTION` (a backup or patch promise is not kept), `CONTRACT` (a vendor agreement or licence is not kept).
- **Component**, from a fixed list: `NETWORK`, `COMPUTE`, `STORAGE`, `DATABASE`, `APPLICATION`, `SECURITY`, `CLOUD`, `OTHER`. Optional: unsaid is a valid answer.
- **A rule names them** (`alertClass`, `component`, both optional). A rule that names no class takes the one of its source (`HEALTH_CHECK` is `AVAILABILITY`, `BACKUP` and `PATCH` are `PROTECTION`, `CAPACITY` is `CAPACITY`, `VENDOR` and `LICENSE` are `CONTRACT`); the response always shows the class that applies.
- **An alert keeps the classification its rule had when it opened.** Editing the rule later does not reclassify history. An alert opened before this ADR has none (`null`), and is not guessed.
- **Lists can be filtered** with `?alertClass=&component=` beside `status` and `checkId`.
- **The incident says it**: its description ends with `Class: X` and, when the rule names one, `component: Y`.
- **No free text.** Classification is two enums, so nothing a person typed ends up in a filter, an index key or a log line.

## Consequences
- Positive: alerts can be grouped and filtered by kind and by part of the estate; no schema migration (new fields are optional and read as absent).
- Negative: the fixed lists are a decision the code owns; a new class or component needs a release. Existing alerts stay unclassified, and a rule edit does not reach alerts that are already open.
