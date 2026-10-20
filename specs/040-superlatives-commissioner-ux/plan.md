# Implementation Plan: Awards — commissioner's list, every player, fairer Embiid cost

**Branch**: `040-superlatives-commissioner-ux` | **Date**: 2026-10-20 | **Spec**: [spec.md](spec.md)
**Input**: `specs/040-superlatives-commissioner-ux/spec.md` (clarified 2026-10-20)

## Summary

Three changes to the Awards page, one existing backend service:

1. **Embiid cost per week, not per game** (US1). `AbsenceCost` charges each counted week once at the player's
   per-game mean (unchanged value, Allan 2026-10-20); a basketball week he played any game in costs nothing.
   Football: same weeks, same value, identical totals.
2. **Commissioner's list moves into the Unethical card** (US2): a panel opened from the card, a name picker over
   the league's rostered players, a through-week (V38), in-place edit (new PUT), per-entry "charged to" teams and
   player-weeks, and edits to the URL's own season before its first scored week (research R5).
3. **Players view** (US3): `playerContributions` on Embiid, Waiver Wire Warrior and Unethical; Jabari uncapped;
   a Teams/Players toggle in the existing in-place standings panel.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5), TypeScript (React + Vite)
**Primary Dependencies**: existing only — JdbcClient, Flyway; React. No new libraries.
**Storage**: Postgres; one migration `V38__conduct_entry_through_week.sql`
**Testing**: JUnit (pure unit tests for `AbsenceCost`, `computeUnethical`, contributions; ITs against 5433), Vitest
**Target Platform**: web (Railway prod), local Windows dev
**Project Type**: web application (backend + web)
**Performance Goals**: Awards payload stays one request; added rows ≤ a few hundred
**Constraints**: honesty rules — the Embiid figure stays labelled an estimate; the reversed rule is noted, not erased
**Scale/Scope**: 2 NBA + 3–4 NFL real leagues, 12 rosters each

## Constitution Check

| Rule | Status |
|---|---|
| Migrations append-only | V38, new file; V37 highest on every branch (research R6) ✅ |
| `api.ts` mirrors Java records | `AbsenceDetail`, `PlayerContribution`, conduct entry/list types change in the same commit as the Java ✅ (tasked) |
| No retuning constants to match guesses | `MIN_STARTED_WEEKS` untouched; R1's "about a quarter" is labelled an indicator, verification reports measured per-roster numbers ✅ |
| `Map.of` with nulls | conduct rows carry nullable `appliesThroughWeek`, `position`, `proTeam`, `fantasyTeam` → mutable maps only ✅ (contract note) |
| Ask before committing | commit/push/PR wait for Allan ✅ |
| Planning doc ≠ verified spec | spec's "What's there now" was read from code; R7 found a double count the spec didn't know about ✅ |

No violations.

## Project Structure

### Documentation (this feature)

```text
specs/040-superlatives-commissioner-ux/
├── spec.md, plan.md, research.md, data-model.md, quickstart.md
├── contracts/awards-api.md
├── checklists/requirements.md
├── plan-review.md        (adversarial review, next)
└── tasks.md, code-review.md, verification.md (later stages)
```

### Source Code (touched)

```text
backend/src/main/resources/db/migration/V38__conduct_entry_through_week.sql      new
backend/src/main/java/com/ballknowers/draftsim/engine/AbsenceCost.java           rule change (R2/R3)
backend/src/main/java/com/ballknowers/draftsim/engine/SeasonSuperlativesService.java
    absenceSuperlative/absenceWinners  – credited weekly means, new AbsenceDetail
    computeUnethical                   – through-week, distinct player-weeks (R7)
    new: contribution builders for 3 kinds; Jabari uncapped
    new: conductCharges(league) for the list's per-entry charges
backend/src/main/java/com/ballknowers/draftsim/store/LeagueConductRepository.java through-week, update(id)
backend/src/main/java/com/ballknowers/draftsim/store/RosterSeasonRepository.java  candidates (players[] + holder)
backend/src/main/java/com/ballknowers/draftsim/api/SuperlativesController.java   PUT, extended GET/POST, playerContributions
web/src/api.ts                                    types + updateConductEntry
web/src/pages/Superlatives.tsx                    remove bottom ConductListSection; card button; Embiid wording
web/src/components/CommissionerListPanel.tsx      new: list + picker + form (moved out of the page)
web/src/components/SuperlativeStandings.tsx       Teams/Players toggle
web/src/pages/Superlatives.test.tsx (+ new component tests)
tests: AbsenceCostTest, UnethicalAwardTest, SuperlativesStandingsIT, conduct-list IT
```

**Structure Decision**: existing web-application layout; the list UI is extracted into its own component because
it moves into the card and grows a picker — keeping it inside an ~900-line page file would worsen it.

## Design notes

### Embiid (US1)
- Value per week = existing `pointsPerGame` (amended 2026-10-20, review F1). No new query.
- Counted weeks: absence weeks (non-UNCLASSIFIED, window-bounded) minus `playedWeeksByPlayer[pid]`, ∩ rosteredWeeks.
- Preference-ordering tests (lesson class 1): zero-play week costs > 0; partial week costs exactly 0; a 4-missed-game
  zero-play week costs the same as a 1-missed-game zero-play week; two rosters ordered correctly when one has more
  zero-play weeks of a lower-scoring player.
- Card/standings copy: "N weeks missed · X pts/game (sat M games)"; How-this-works sentence naming the rule and
  that it is an estimate; `standingFigure` unchanged (points).

### Commissioner's list (US2)
- Card: `UNETHICAL` card gets a "Commissioner's list · N" button (stopPropagation so it doesn't toggle standings)
  that expands a panel in place, consistent with spec 013's in-place pattern.
- Panel season: the URL's league id (R5). When `requestedSeason != null`, header "2026 list" + note that the awards
  above show 2025. `onChanged` refetches superlatives only when the panel's season == the cards' season.
- Picker: text input filtering `candidates` by name (case/diacritic-insensitive), shows avatar/position/pro team/
  fantasy team; already-listed players show "on the list — edit" and jump to that entry.
- Remove asks for confirmation (inline "Remove? Yes / No", no browser `confirm()`).

### Players view (US3)
- `SuperlativeStandings` gets a two-option segmented control when `playerContributions.length > 0`. Players rows:
  rank, avatar/name link, team chip, figure (via the same `figure` fn for points; player-weeks for Unethical), note.
- Payload order is the ranking; never re-sorted client-side (spec 010 rule).

## Risks

- The Embiid holder may change on NBA 2025 — expected; verification names old vs new holder.
- R7 changes Unethical totals only when both sources hit the same week; currently 0 cases locally (no conduct
  entries, no rostered suspensions) — covered by a unit test.
- Concurrent sessions: work stays in this worktree; prod deploy is out of scope.

## Complexity Tracking

None.

## Amended after the adversarial plan review (2026-10-20)

`plan-review.md` read this plan cold against the code and the local DB. Each finding and what changed:

- **F1 (HIGH)** — the credited-weekly value moved NFL totals by up to ~11 pts per roster (SC-003 would fail by
  construction). Put to Allan; he chose the **per-game average**. FR-001/FR-003/SC-003, research R2, data model
  and contract amended in place. `weeksSampled` (review's suggestion) is not needed under this choice.
- **F2 (HIGH)** — `withUsernames` (`SeasonSuperlativesService.java:1550`) rebuilds every `Superlative` via the
  canonical constructor; a new field would be silently dropped by a defaulting overload. Tasks: copy
  `playerContributions` through `withUsernames` (usernames filled on each contribution's team), add it to the
  controller's hand-copied `superlativeRow` reusing `holderRow`, and an IT asserting it is non-empty for all three
  kinds. Contract Holder shape corrected (`teamName`/`username`).
- **F3 (MEDIUM)** — the card is a clickable `<article>`: the panel root stops click *and* keydown propagation
  (as `DetailList` does), and the list button renders on UNETHICAL regardless of `available`/empty/`openable`.
  Test: clicking in the panel leaves "See all"'s `aria-expanded` unchanged.
- **F4 (MEDIUM)** — (a) when no season has been played the page renders no cards: render the list button/panel in
  the `!data.available` branch too, addressed by the URL id. (b) `roster_season.players` is null for most local
  leagues: candidates fall back to the latest scored week's `players_points` for `fantasyTeam`; with zero
  candidates the picker says "rosters haven't loaded yet — refresh the league" rather than offering an id box.
- **F5 (MEDIUM)** — R5 deliberately reverses the 2026-09-23 code-review fix "never the URL's own id". Tasks
  rewrite (not delete) `Superlatives.test.tsx:1063` and `:1108` to the new assertion (addresses the URL id; header
  names its season when it differs), and the component comment gets a dated note. `canEdit`/`commissionerKnown`
  come from the conduct-list response alone.
- **F6 (MEDIUM)** — uncapping Jabari makes "N more players with K adds each" false; becomes "N more players",
  with a two-rank-group test.
- **F7 (MEDIUM)** — spec 038 (Season Wrapped, uncommitted) plans a per-roster top pickup on `Standing`. Coordination
  note: 038 should read it from `playerContributions` (one implementation of one rule); whichever merges second
  rebases the records. 038's docs are left untouched here (another session's worktree); flagged to Allan.
- **F8 (LOW)** — SC-002 replaced with falsifiable checks.
- **F9 (LOW)** — R7's distinct-player-week total extracted into a pure static method; `UnethicalAwardTest` helper
  points at it; new test with one player under both sources. Because totals dedupe across sources, an entry's
  own player-weeks can exceed what it adds to the award when Sleeper flags the same weeks; the entry row says
  "also flagged by Sleeper in weeks …" in that case.
- **F10 (LOW)** — POST upsert also sets `applies_through_week`; PUT/POST validate through ≥ from in the controller
  (400 not 500); POST/PUT return the plain row.
- **F11 (LOW)** — quickstart route is `/superlatives`; `SuperlativesStandingsIT`'s Embiid fixture comment
  ("10 ppg") is checked deliberately — under the per-game choice the figure stays per-game, so the fixture's
  expected number is re-derived, not assumed; test entries deleted after live checks.
- **F12 (LOW)** — adopted: the full view **opens on Players** for JOEL_EMBIID and WAIVER_WIRE_WARRIOR (the literal
  ask), Teams one click away; UNETHICAL and Jabari unchanged defaults (Teams / players-only).
