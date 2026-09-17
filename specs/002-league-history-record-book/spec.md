# Feature Specification: A league history worth scrolling

**Feature Branch**: `002-league-history-record-book`

**Created**: 2026-09-16

**Status**: Draft

**Input**: "https://ffwrapped.com/?leagueId=1346366555759341568&view=league-history lets start
getting this UI and history down and lets start with league history." (with a screenshot of
ffwrapped's League History page), followed by "also add in power rankings for league history".

## Problem

`/leagues/:id/history` today is a stack of plain standings tables — one `<h3>` per season over
W / L / T / PF / PA. Every season looks identical, nothing is ranked, and the only thing the page
can tell you is what Sleeper already told you.

The reference page does something the current one does not: it turns the same rows into a **record
book**. Extremes get names and dates — the highest week anyone has ever posted, the lowest, the
game decided by 0.16 points, the 118-point beating. Those are the facts a league actually argues
about, and they are already in this database.

### What the data will and will not support (measured 2026-09-16, local Postgres)

This is the part that decides scope, so it was measured rather than assumed:

| League | Season | `roster_week_points` | weeks | paired `league_matchup` | `power_ranking` entries |
|---|---|---|---|---|---|
| (Foot) Ball Knowers | 2025 | 204 | 17 | **8 (week 17 only)** | 12 (wk 0), 12 (wk 8), 12 (wk 17) |
| (Foot) Ball Knowers | 2026 | 12 | 1 | 168 (weeks 1–14, scheduled) | 12 (wk 0), 12 (COMMISSIONER wk 1) |
| Ball Knowers (NBA) | 2025 | 252 | 21 | **8 (week 21 only)** | **0** |
| Ball Knowers (NBA) | 2024 | 288 | 24 | **8 (week 24 only)** | **0** |
| West Coast Fantasy Football | 2025 | 216 | 18 | **0** | **0** |

Three consequences, and they are the whole shape of this feature:

1. **Per-week scores are complete for every played season.** Top-scores and low-scores tables can
   be built today with no ingest work at all.

2. **Pairings are missing for every past season.** `LeagueHistoryIngestService#ingestWeeklyPoints`
   skips any week already in `roster_week_points` (`if (stored.contains(week) && week !=
   lastScoredLeg) continue;`), and the `league_matchup` upsert lives *inside* that loop. Every
   season ingested before the fixture table was added (2026-09-14) therefore has complete scores
   and no pairings — and **re-running the ingest will never repair it**, because the skip gate is
   keyed on the table that is already full. So *Closest Matchups* and *Biggest Blowouts* are not
   buildable from stored data until that gate is fixed. This is the same class of bug as
   `adp_at_time` being wiped by a rebuild: a cache check standing in front of a column that did
   not exist when the cache was filled.

3. **Past seasons have no power rankings.** Only 2025 football was ever computed, because
   `POST /leagues/{id}/power/compute` is the only writer and nobody ever ran it against a
   predecessor season's Sleeper id. A "final rank" column would read `—` for three of the four
   played seasons. It is recoverable: `PowerRankingService#computeRealized` reads only
   `roster_week_points`, which is complete, so a season's final rank can be computed from data
   already stored.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Settle an argument about the biggest week ever (Priority: P1)

A manager opens League History and wants to know who has posted the highest single week in league
history, and when. Today the page cannot answer this; the number is in `roster_week_points` and
nothing reads it.

**Why this priority**: it is the whole point of a record book, it is buildable from complete data
with no ingest change, and it delivers value even if nothing else in this spec ships.

**Independent Test**: load the page for (Foot) Ball Knowers with only US1 built and confirm the
top-scores table lists real manager names, scores and week numbers drawn from all 17 stored weeks
of 2025 — not just the current season.

**Acceptance Scenarios**:

1. **Given** a league with several ingested seasons, **When** the manager opens League History,
   **Then** a *Highest weekly scores* list and a *Lowest weekly scores* list each show the top
   entries across **every** ingested season, each row naming the manager, the score, the season
   and the week.
2. **Given** a roster whose manager slot is unowned for that season, **When** it appears in a
   record row, **Then** the row still renders with the roster identified rather than being dropped.
3. **Given** a league whose seasons have not been ingested, **When** the manager opens the page,
   **Then** the existing "Load past seasons" affordance is what they see — no empty record book.

---

### User Story 2 — See where each season actually finished (Priority: P2)

A manager reading a past season's standings wants the app's own verdict — the end-of-season power
rank — next to the raw W/L, so the season row says something Sleeper did not.

**Why this priority**: it is the user's explicit follow-up request, and it makes each standings row
carry the app's opinion. It is P2 rather than P1 because it needs the backfill described above
before it shows anything for most seasons.

**Independent Test**: backfill one NBA season, reload, and confirm its standings rows show a final
rank while an un-backfilled season shows a stated reason rather than a blank.

**Acceptance Scenarios**:

1. **Given** a completed season with a stored final `COMPUTED_REALIZED` snapshot, **When** its
   standings table renders, **Then** each row shows that roster's end-of-season power rank.
2. **Given** a completed season with no stored snapshot, **When** its standings table renders,
   **Then** the rank cell says *why* it is empty rather than showing a bare `—`, and the page
   offers the action that computes it — never printing the endpoint for the reader to run.
3. **Given** the season currently in progress, **When** its standings table renders, **Then** the
   rank column distinguishes "this season is not over" from "never computed". A week-0 preseason
   baseline is **not** a final rank and MUST NOT be shown as one.
4. **Given** a season whose stored snapshot is `COMMISSIONER` rather than `COMPUTED_REALIZED`,
   **When** the rank is chosen, **Then** the computed mode wins — a commissioner's opinion is not
   the season's result.

---

### User Story 3 — Relive the closest game and the worst beating (Priority: P3)

A manager wants the *Matchup Margins* pair from the reference page: the tightest wins and the
biggest blowouts, each showing both teams and both scores.

**Why this priority**: highest delight per pixel, but it is **blocked** — it needs pairings that
are not in the database and that the current ingest cannot backfill. It is specified here so the
ingest fix is scoped against a real consumer rather than done speculatively.

**Independent Test**: repair the ingest gate, re-run the league-history ingest for (Foot) Ball
Knowers, and confirm `league_matchup` holds 12 paired rosters for weeks 1–17 of 2025 rather than 8
rows in week 17; then confirm the margins panel populates.

**Acceptance Scenarios**:

1. **Given** a league whose past seasons have scores but no pairings, **When** the league-history
   ingest is re-run, **Then** the missing pairings are fetched and stored for those weeks — the
   week-points cache MUST NOT suppress a fixture fetch for a week that has no stored pairing.
2. **Given** stored pairings and scores for a week, **When** the margins panel renders, **Then**
   *Closest matchups* and *Biggest blowouts* each show the margin, the season, the week, and both
   sides with their scores.
3. **Given** a week where a roster has no opponent (bye, odd roster count), **When** margins are
   computed, **Then** that roster contributes no margin rather than a margin against itself or
   against zero.
4. **Given** a league with no pairings at all after ingest, **When** the page renders, **Then** the
   margins panel states that pairings are unavailable for those seasons rather than rendering
   empty cards.

### Edge Cases

- A season ingested but never played (2026 NBA: 0 weeks stored) contributes nothing to the record
  book and MUST NOT render as a zero-score record. The existing page already learned this lesson
  for standings; the record book must not relearn it.
- Ties in a record list (two identical scores) must both appear rather than one silently winning.
- A manager who changed display name across seasons is one `manager_id`; records should attribute
  to the identity the rest of the app uses.
- NBA and NFL leagues have different week counts (24 vs 17) and different "season is over"
  definitions. Nothing here may hardcode a football week count — the multi-sport rule this repo has
  broken three times is an optional parameter that silently encodes a sport's rule.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The League History page MUST show highest and lowest weekly scores aggregated across
  every ingested season of the league's chain, each entry attributed to a manager, season and week.
- **FR-002**: Record aggregation MUST read stored data only. The page MUST NOT call Sleeper.
- **FR-003**: The number of entries shown per record list MUST be bounded and consistent between
  the high and low lists.
- **FR-004**: Each season's standings rows MUST carry that season's end-of-season power rank when
  one is stored, sourced from the `COMPUTED_REALIZED` snapshot at that season's last scored week.
- **FR-005**: An absent final rank MUST be rendered with its reason distinguished: season still in
  progress, versus never computed. Week 0 MUST NOT be treated as a final rank.
- **FR-006**: Where a final rank is missing but computable, the page MUST offer a control that
  performs the computation, following the existing convention that the app never prints an endpoint
  for the reader to run.
- **FR-007**: The league-history ingest MUST be able to backfill `league_matchup` pairings for
  weeks whose `roster_week_points` are already cached.
- **FR-008**: The page MUST show closest matchups and biggest blowouts once pairings exist, each
  naming both sides, both scores, the margin, the season and the week.
- **FR-009**: Rosters without an opponent in a given week MUST be excluded from margin records.
- **FR-010**: Every new panel MUST degrade to a stated reason when its inputs are missing, never to
  an empty container or a zero.
- **FR-011**: Nothing added here may assume a sport's week count, season length, or playoff start.

### Key Entities

- **Weekly score record**: one roster's scored total in one (season, week), attributed to a
  manager. Sourced from `roster_week_points.starters_points`.
- **Margin record**: two rosters paired by `matchup_id` within one (season, week), with both totals
  and their absolute difference.
- **Season final rank**: a roster's `rank` in the `COMPUTED_REALIZED` `power_ranking` snapshot at a
  season's last scored week.

## Success Criteria *(mandatory)*

- **SC-001**: Opening League History for (Foot) Ball Knowers shows records drawn from all 17 stored
  weeks of 2025, verified against a direct query of `roster_week_points`.
- **SC-002**: The top score shown by the page equals `max(starters_points)` for the league chain —
  checked by query, not by eye.
- **SC-003**: After the ingest fix and a re-run, (Foot) Ball Knowers 2025 holds paired rosters for
  every scored week, not one week.
- **SC-004**: No season renders a panel that is simultaneously empty and unexplained.
- **SC-005**: The page issues no Sleeper request on load.

## Assumptions

- `roster_week_points.starters_points` is the roster's real scored total for the week. The ingest
  comments record this as verified live against Ball Knowers 2025 week 1; this spec takes it as
  given rather than re-verifying.
- The league chain walked by `LeagueRepository#chainBySleeperId` is the correct scope for "league
  history" — records span the chain, not a single Sleeper league id.
- The reference screenshot shows only part of ffwrapped's page. Only the sections visible in it
  (score extremes, matchup margins) are specified here; anything below the fold is out of scope.

## Out of Scope

- Rank-over-time charting on this page. The user chose final-rank-in-the-row; the bump chart work
  on `001-readable-bump-chart` stays where it is.
- Any change to the standalone Power Rankings page.
- Playoff/championship history beyond the trophy already rendered.
- Multi-user or cross-league record books.
