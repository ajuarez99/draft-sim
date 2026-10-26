# Implementation Plan: Weekly High-Score Ledger

**Branch**: `048-weekly-high-score-ledger` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/048-weekly-high-score-ledger/spec.md`. Research: [research.md](research.md).

## Summary

> **Amended after review (2026-10-25):** see "Amended after review" (A1–A15) at the end, which
> wins over the text here. Most visibly: the shortcut is per-component greedy (no pairwise
> fallback); the live line uses `peek` and only shows once the week has started; ESPN leagues get
> a fixed "Sleeper leagues only" refusal in v1; rosters are named by `RosterOwners.ownerNames`.

A per-league-season side bet tracker: each regular-season week's top scorer collects a stake
(default $1) from every other roster. Everything is derived on read from stored weekly scores;
the only stored state is the commissioner's on/off switch and stake (migration V41).

- **Engine**: a pure `WeeklyPotLedger` (no Spring, no DB) turns (weekly scores, stake) into
  week results, balances, pairwise debts and a shortcut list, all in integer cents.
- **Service**: `WeeklyPotService` loads the row's final regular-season weeks, names the
  rosters the way Expected wins does, adds the live leader, and applies the setting.
- **API**: `GET /api/leagues/{id}/weekly-pot`, `PUT .../weekly-pot/settings` (commissioner only).
- **Web**: a "Weekly pot" page in the rail's "The season" group: standings sheet, week list,
  settle-up (pairwise / shortcut tabs), commissioner panel.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5), TypeScript (React + Vite)

**Primary Dependencies**: existing only (Spring `JdbcClient`, React Router)

**Storage**: Postgres. New table `league_weekly_pot` (**V41**; highest on main is V40). Reads
`roster_week_points`, `roster_season`, `manager`, `league_member`, `league_week_fetch`
(via `ScoredWeeks`), `league` playoff settings (via `regularSeasonBound`).

**Testing**: JUnit 5 unit tests for the pure ledger (preference/ordering assertions, not only
structure — lessons bug class 1), a `*IT` against local Postgres for the repository, the
endpoint and the migration; vitest for the page.

**Target Platform**: Railway (two services); local dev on Windows.

**Project Type**: web application (backend + web)

**Performance Goals**: one `allScores` read + one `ScoredWeeks` snapshot + one members read per
request; at most 14 rosters × 18 weeks — trivial. The live read is `LiveWeekSource.current`,
already bounded at 2.5 s and cached.

**Constraints**:
- Zero-sum exact (integer cents).
- One implementation per rule: finality (`ScoredWeeks`), regular season
  (`ExpectedWinsService.regularSeasonBound`), commissioner gate (`LeagueMembership.canCommission`),
  roster naming (Expected wins' pattern).
- Both sports.

**Scale/Scope**: ~6 real chains, 10–14 rosters each.

## Constitution Check

| Rule | Status |
|---|---|
| Migrations append-only | **Applies.** New `V41__league_weekly_pot.sql`; nothing earlier edited. |
| `api.ts` mirrors Java records | **Applies.** `WeeklyPot`, `WeeklyPotWeek`, `WeeklyPotBalance`, `WeeklyPotDebt`, `WeeklyPotLiveLeader` land in the same task as the Java records. |
| No retuning of hand-set constants | Holds. New hand-set values (stake cap 100000 cents; tie-cent order) are labelled arbitrary in code and payload. |
| `Map.of` null hazard | **Applies.** Several nullable fields; response bodies use records / `LinkedHashMap`, no `Map.of` with nullable values. |
| Ask before committing | Holds. |
| Planning doc ≠ spec | Applied: spec's per-week manager attribution (R6) and "no navigation when off" (R10) were checked against the code and amended. |

**Gate: PASS.** Re-checked after design: still PASS.

## Design

### Backend

1. **`V41__league_weekly_pot.sql`** — table per data-model.md.
2. **`store/LeagueWeeklyPotRepository`** — `find(leagueId) → Optional<Setting(enabled, stakeCents)>`,
   `upsert(leagueId, enabled, stakeCents, updatedBy)`.
3. **`engine/WeeklyPotLedger`** (pure, static):
   - Input: `List<Score(week, rosterId, BigDecimal points)>` already filtered to counted weeks,
     `long stakeCents`.
   - Output: `Ledger(weeks, balances(rosterId → weeksWon/collected/paid/net), pairwise, shortcut,
     shortcutIsPairwise)`.
   - Rules: R4 (ties, cents), R5 (pairwise net; greedy shortcut, fall back to pairwise when greedy
     is longer), `NO_SCORES` when a week's max is 0.
4. **`engine/WeeklyPotService`**:
   - `view(LeagueRow row, String sleeperUserId)`: setting (absent → off, stake 100), `canEdit`,
     `commissionerKnown`; if off return early.
   - Bound = `expectedWins.regularSeasonBound(row)`; `regularSeasonKnown = bound.throughWeek() != null`.
   - Scores = `weekPoints.allScores(List.of(row.id()), bound)` filtered to
     `scoredWeeks.of(row.id()).isFinal(week)`.
   - Names: members' team names + `ScoreRow` manager/avatar (Expected wins' pattern,
     `ExpectedWinsService.java:411-425`).
   - Live leader: `liveWeekSource.current(row)` per R9.
   - `seasonFinal = row.complete() && (regularSeasonKnown ? throughWeek == boundWeek : true)`.
5. **`api/WeeklyPotController`** — the two routes in contracts/weekly-pot-api.md; 403 body copies
   the conduct list's shape (extract nothing — the message text differs; keep `commissionerKnown`).

### Web

6. `api.ts`: the types above + `getWeeklyPot(id)`, `saveWeeklyPotSettings(id, body)`.
7. `destinations.ts`: row `weeklyPot`, group `season`, glyph `$`, sports `['nfl','nba']`,
   label "Weekly pot", href `/leagues/${season}/weekly-pot`. `App.tsx` route (no gate).
8. `pages/WeeklyPot.tsx`:
   - Header: season, stake ("$1 a week from everyone to the top scorer"), "through week N"
     / "final", regular-season range or the "playoffs start unknown" note.
   - **Live line** (US4) when `liveLeader`: "Leading week 8 (not final): Team X, 88.2".
   - **Standings sheet** (table, not cards — content is tabular): rank, avatar + team, weeks won,
     collected, paid, net (green/red tinted pill). Viewer's own row highlighted.
   - **Week by week**: compact list, newest first: week, winner(s) (tie badge), score, margin, pot.
   - **Settle up**: two tabs "Shortcut" (default) / "Every pair"; one sentence explaining both
     clear the same balances; `tieCentRule` footnote when any tie happened.
   - **Off state**: one sentence; commissioner sees `CommissionerPotPanel` (toggle + stake input
     in dollars, saved as cents); non-commissioner with no commissioner recorded sees that reason.
   - Phone: table collapses to rank/team/net with weeks won under the name.
9. `pages/LeagueHome.tsx`: nothing (out of scope; see below).

## Not being built (and why)

- **Marking debts paid / payment links** — spec Assumption; the app never touches money.
- **Per-week manager attribution** — not in the schema (R6).
- **A home-page tile** — the rail entry is enough for v1; a tile can follow if Allan wants it.
- **Multi-season / all-time pot totals** — each season's bet is settled on its own.
- **Proven-minimal settle-up** — NP-hard in general; R5's shortcut is guaranteed no longer than pairwise.

## Acceptance criteria for verification

Quickstart scenarios 1–10 against the real server and a real league, plus:
- SC-001 on at least one complete real season (all decided weeks), NFL and NBA one each if data allows.
- R3's "to measure": which real chains lack `playoff_week_start`.

## Project Structure

### Documentation (this feature)

```text
specs/048-weekly-high-score-ledger/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/weekly-pot-api.md
├── checklists/requirements.md
└── tasks.md            (/speckit-tasks)
```

### Source Code

```text
backend/src/main/resources/db/migration/V41__league_weekly_pot.sql
backend/src/main/java/com/ballknowers/draftsim/store/LeagueWeeklyPotRepository.java
backend/src/main/java/com/ballknowers/draftsim/engine/WeeklyPotLedger.java
backend/src/main/java/com/ballknowers/draftsim/engine/WeeklyPotService.java
backend/src/main/java/com/ballknowers/draftsim/api/WeeklyPotController.java
backend/src/test/java/com/ballknowers/draftsim/engine/WeeklyPotLedgerTest.java
backend/src/test/java/com/ballknowers/draftsim/api/WeeklyPotControllerIT.java
web/src/api.ts
web/src/destinations.ts
web/src/App.tsx
web/src/pages/WeeklyPot.tsx (+ WeeklyPot.test.tsx, styles alongside existing page CSS)
web/src/components/CommissionerPotPanel.tsx
```

**Structure Decision**: existing backend/web split; new files follow the per-feature
engine/store/api layout used by specs 033–047.

## Complexity Tracking

None — no constitution violations.

## Amended after review (2026-10-25)

From [plan-review.md](plan-review.md) (F1–F15). These win over the text above.

- **A1 (F1) Shortcut = greedy per connected component** of the pairwise debt graph. In each
  component with m rosters the pairwise view has ≥ m − 1 edges and greedy uses ≤ (non-zero rosters
  in it) − 1 transfers, so it is never longer than pairwise. The pairwise fallback and
  `shortcutIsPairwise` are removed. Greedy ties broken by `rosterId` ascending. US2 scenarios 1–2
  (debtor→creditor only, zero-balance rosters absent) apply to the **shortcut**; the pairwise view
  can legitimately have a +$5 roster owe a +$20 one, and the page says so.
- **A2 (F2) Live line only once the week has started**: reuse `WeeklyReportService.decideLive`'s
  "any points > 0" rule (`WeeklyReportService.java:477-484`) — extract it to one shared static if it
  isn't already callable — never a 12-way 0.0 tie.
- **A3 (F3) `LiveWeekSource.peek`, not `current`**: the line moves no money; the first view after
  expiry may omit it. `liveUnavailable` is true only when the cached answer is FAILED.
- **A4 (F4) Naming via `RosterOwners.ownerNames(rosterSeasons.forLeague(id), members.forLeague(id),
  callerManagerId)`** (`engine/RosterOwners.java:26`), which also yields `isMe`. `Balance` gains
  `name` (replaces `teamName`/`username`) and `isMe`. Caller id via `ManagerRepository.caller`.
- **A5 (F5) Balances cover every roster in `roster_season`** for the row, even before any counted
  week (all zeros), so FR-005 holds in week 1 and the live leader can be named.
- **A6 (F6) Week status walk**: weeks `1..min(boundWeek or max(stored, live week))` each get
  `DECIDED`, `NO_SCORES` (rows exist, every stored score is exactly 0 — negatives are not zero),
  `NOT_FINAL` (stored, not final) or `MISSING` (no rows). Only `DECIDED` moves money.
  `throughWeek` = highest week with every week ≤ it `DECIDED`/`NO_SCORES`. `seasonFinal` = every week
  `1..boundWeek` is `DECIDED`/`NO_SCORES` when `regularSeasonKnown`, else `row.complete()`.
- **A7 (F7) SC-005/FR-001 restated**: with the ledger off, no *weekly-pot* amount (stake, balance,
  pot, payment) is shown to anyone except the commissioner's own stake control. The rail glyph is
  `◎`, not `$`, so the rail doesn't imply money on leagues that never opted in.
- **A8 (F8) Doubling only for untied weeks**: quickstart 7 and US3's independent test assert
  doubling on untied weeks and R4's formula on tied ones; unit test with a 3-way tie at 100¢ and 200¢.
- **A9 (F9) ESPN: fixed-unavailable in v1** (option b), matching Expected wins and superlatives,
  whose rules this reuses: add `weeklyPot → ["weekly-pot"]` to `LeagueAvailability.ROUTES` and a
  FIXED message "The weekly pot follows Sleeper leagues only.", `gate="weeklyPot"` in App.tsx.
  **Flagged to Allan** as a scope choice: his ESPN NBA league won't get the pot until a follow-up
  handles ESPN's matchup-period vs scoring-period units.
- **A10 (F10) Full destinations row**: `key: 'weeklyPot'` (added to `DestinationKey`), group
  `season`, glyph `◎`, sports `['nfl','nba']`, label "Weekly pot", href
  `` `/leagues/${ctx.season.sleeperLeagueId}/weekly-pot` ``, `match: /^\/leagues\/([^/]+)\/weekly-pot\/?$/`,
  `idKind: 'league'`, `requiresStatus: null`, `isAction: false`, `inRail: true`.
- **A11 (F11) Input hygiene**: request record `Boolean enabled, Integer stakeCents` (boxed; missing
  → 400); range checked before the DB; web converts with `Math.round(Number(v) * 100)` and rejects
  more than 2 decimals; Jackson float→int coercion means the server must reject non-integral JSON
  numbers (`stakeCents` read as `JsonNode`/`BigDecimal` and checked `scale()<=0`, or
  `FAIL_ON_FLOAT_FOR_INT` on this endpoint). `api.ts` type is a union on `enabled`. Ties compared
  with `BigDecimal.compareTo`.
- **A12 (F12) Payers**: a roster with a row (even 0.0) pays; an orphan roster (`managerId == null`)
  pays and collects, and the page notes "had no manager this season".
- **A13 (F13) Past-season commissioners**: R7 states the limit (a commissioner can only turn on a
  season they're recorded in; a season with no recorded commissioner needs the app owner).
  verification.md runs the `bool_or(is_commissioner)` query from plan-review F13.
- **A14 (F14) Quickstart 4** uses `rank() over (partition by week order by starters_points desc) = 1`
  restricted to the payload's `DECIDED` weeks.
- **A15 (F15) Verification**: `WeeklyPotControllerIT` must report 0 skipped; the "Every pair" tab
  (up to 91 rows) gets a phone layout: grouped by payer, collapsible.
