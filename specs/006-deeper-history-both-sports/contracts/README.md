# Interface Contracts

**Branch**: `006-deeper-history-both-sports` | **Date**: 2026-09-21

| Contract | Kind | Story |
|---|---|---|
| [`manager-profile-api.md`](manager-profile-api.md) | HTTP, read-only — a **breaking change** to a shipped endpoint | US1, US2, US3, US6 |
| [`head-to-head-api.md`](head-to-head-api.md) | HTTP, read-only — new | US4 |

The league-side record book (US5) extends `GET /leagues/{sleeperId}/history`, whose contract is already
written in [`specs/002-league-history-record-book/contracts/league-history-api.md`](../../002-league-history-record-book/contracts/league-history-api.md)
and is amended in place rather than restated here. The one amendment: a standing row's `champion` is
false for any season whose `league.status` is not `complete`, which removes the trophy currently drawn
on two in-progress seasons.

## Conventions shared by both contracts

Taken from the existing `/api/leagues/{sleeperId}/...` and `/api/managers/{managerId}/...` families,
not invented here:

- **Read-only and computed on request.** Nothing in this feature is a claim about a moment, so nothing
  is snapshotted — the reasoning `RosterManagementService`'s own javadoc gives.
- **Refusal is a valid answer and is explicit.** A figure that cannot be computed is absent with a
  stated reason, never a plausible-looking zero. Callers must be able to tell `0.0` from "no answer".
  This is load-bearing here: trades per manager and playoff appearances are both genuinely unavailable.
- **Sport is never defaulted and never spanned.** Every career figure is nested under a sport. No
  top-level total sums two sports.
- **Every figure carries the count it rests on.** A number without its `seasonsCounted` is a season
  record dressed as a career one, and with one or two played seasons per chain that is not a
  hypothetical.
- **Every rank carries its population.** `{ position: 2, population: 12, leagueName: … }`, never a bare
  `#2`.
- **`managerId` is this app's own id**, as the existing `/api/managers/{managerId}/history` route uses;
  `sleeperId` is the Sleeper league id.
- **Membership scoping is unchanged.** `LeagueMembership#canSeeManager` already guards the manager
  route and continues to; a manager id is a small integer and the database must not be walkable by
  counting upwards.
