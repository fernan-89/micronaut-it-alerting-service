# ADR-043: Alert on Purchase Orders That Are Late

## Status
Accepted

## Context
The procurement registry judges, when read, whether a placed purchase order is late (procurement ADR-031: not completely received and past the expected day). Nobody is told when a vendor's delivery is overdue: the verdict is only seen by whoever opens the list. The alerting service already turns the verdicts of the other registries into one alert and one incident (ADR-037 to ADR-042).

## Decision
- A rule can watch the source **`PROCUREMENT`**: `checkId` is one purchase order, left out means every order of the tenant. The registry is read through its `retrieve` route only while an ACTIVE rule watches it, as the service identity.
- **DOWN** when an order is late (`PURCHASE_ORDER_LATE`). **UP** when a placed order is not late, and also when it was **cancelled**: nothing is owed any more, so an alert about its lateness is resolved. An order in any other status (a draft, waiting for approval, approved, rejected) is not judged. A cancelled order is passed on with the status `CANCELLED`, not `ACTIVE`, so it never opens an alert.
- The alert is named by the order's reference and carries no asset. Only the fixed code is kept (never a vendor, a line or a price), so the incident says "is not being kept (PURCHASE_ORDER_LATE)".
- **What is read:** the late orders, asked of the registry itself (`late=true`, up to 500), so they are never cut off a list of the most recent ones; and the orders **last changed within 30 days** (`changedWithinDays=30`, up to 500), which hold what was received, completed or cancelled since an alert opened. One in both is read once. A late order that is finally received changes, so it is among the recently changed ones and its alert is resolved by the next round.
- When the goods are received completely the order is no longer late, so the next round resolves the alert and notes it on the incident.
- The default class of such an alert is `CONTRACT` (ADR-040): the vendor owes something agreed.
- A procurement registry that cannot be read is skipped for that round, as for the other registries.

## Consequences
- Positive: an overdue delivery becomes work for the team, with an owner and a trail, with no new mechanism.
- Negative: an alert whose order has not changed for more than 30 days while no longer late could not be seen resolved; that cannot happen without a change to the order, which moves it into the window. Beyond 500 late orders at once, the rest are read as the first are resolved.
