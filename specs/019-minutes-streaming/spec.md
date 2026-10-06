# Feature Specification: NBA minutes trends and streaming candidates

**Feature Branch**: `019-minutes-streaming`

**Created**: 2026-10-06

**Status**: Draft, amended after adversarial review 2026-10-06 ([plan-review.md](plan-review.md); dispositions in [plan.md](plan.md#amended-after-review-2026-10-06))

**Input**: User description: "keep it going". The next items on `claude/competitor-gap-roadmap.md`
Phase 2 after spec 018: **2.3** NBA minutes and role trends ([claude/nba-minutes-trends.md](../../claude/nba-minutes-trends.md))
and **2.4** streaming candidates ([claude/nba-schedule-grid-and-streaming.md](../../claude/nba-schedule-grid-and-streaming.md)
view 4). Both should be live before the NBA season starts on **2026-10-20**, when week-1 pickups happen.

## Background

Measured 2026-10-06 against the local DB (research R1–R6):

- **Minutes are stored.** Every NBA `player_game` row has `sp`, and it's seconds: Jokić 2025-10-23
  vs GSW stores 2,450 s = 40:50, which matches the published box score (40:50, 21 pts, 13 reb, 10 ast).
- **`player_game` also holds 30 `TEAM_*` rows per game date** (2,462 in 2025): team box-score totals
  (`sp` 14,400 = 240 team-minutes, more in overtime). Averaging `sp` without excluding them gives a
  nonsense 41 "minutes" per game. They also make a **true usage rate** computable. The design doc's
  "true usage needs team totals Sleeper doesn't give" is wrong, and that gets an amended note.
- **Game count matters far less than the streaming design assumed.** In these leagues a starter's week
  is credited one game. Spec 018 measured that, and this spec found that it's **the player's best game
  of the week in 69%** of multi-game weeks (1,507 of 2,171). *Amended after review (F6):* for the same
  player, a week where his team had **4 scheduled games** gave **+4.4%** more credited points than a
  2-game week in 2025 (−1.6% vs 3 games). That's not "~2×". The design doc's "unrostered players on teams with ≥ 4 games" filter would
  throw away the best streamers for a small edge. Streaming here ranks by season-to-date form (F7), and shows games
  this week beside it as context.
- **"Rostered" is unknown between the draft and the first scored week.** Spotlight (spec 014) reads
  ownership from the latest stored scoring week. NBA "Ball Knowers" drafts on 2026-10-10 and scores
  from 2026-10-20, so for that whole window every drafted player would read as a free agent. That's
  exactly when week-1 streaming matters. The league refresh already fetches `/league/{id}/rosters` on
  every run and keeps only the record. It throws away each roster's `players` list.
- With a ±6-minute threshold (median of the last 3 games vs. the season average), 30–58 players rise
  and 16–70 fall at points across 2025. So the useful surface is a ranked list, not a flag.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See whose minutes are changing (Priority: P1)

A manager in a basketball league opens a Trends page and sees the players whose minutes have
moved the most recently: risers and fallers, each with recent minutes, season minutes and the
change. Each list is split into players rostered in this league and free agents. Next to minutes
it shows a usage rate and league points per minute, each labelled with what it is.

**Why this priority**: minutes come before points. A bench player going from 18 to 32 minutes
is the pickup, and his points haven't caught up yet.

**Independent Test**: open `/leagues/{nba 2025 id}/trends` and see risers and fallers computed from
the end of the 2025 season, with every number labelled with its window.

**Acceptance Scenarios**:

1. **Given** 10 games at 18 min then 3 at 32, **When** computed, **Then** he's a riser (+14).
2. **Given** 10 games at 30 and one injury-shortened game at 8 among the last three, **When**
   computed, **Then** he's not a faller (the recent window is a median).
3. **Given** missed games (absences), **When** computed, **Then** they're in neither window.
   `player_game` holds only played games.
4. **Given** the `TEAM_*` rows, **When** any list is built, **Then** they never appear as players.
5. **Given** a league whose season has no games yet, **When** the page is opened, **Then** it shows
   the previous season's data, labelled as that season ("2025 season, 2026 hasn't started").

---

### User Story 2 - Streaming candidates for this week (Priority: P1)

The same page lists free agents in this league ranked by season-to-date points per game
(amended after review F7: it predicted next week better than last-5 form, r 0.517 vs 0.476), with
last-5 form beside it. Each row also shows his team's games this week and next, his minutes
trend, and a one-line note on how much game count is worth in this league.

**Why this priority**: it's the week-to-week decision basketball managers make most, and week 1
(2026-10-20) is when it matters most.

**Independent Test**: on the NBA 2026 league after its draft, no drafted player appears, and the
games column matches the schedule grid.

**Acceptance Scenarios**:

1. **Given** the league's stored current rosters, **When** the list is built, **Then** no
   rostered player appears (cross-checked against Sleeper `/rosters`).
2. **Given** the league is drafted but no week is scored yet, **When** the page is opened,
   **Then** the drafted players are rostered, not free agents.
3. **Given** a player whose team has 4 games and one with 2, **When** shown, **Then** both show
   their game counts. The order is by form, and the page states the measured value of extra games.
4. **Given** the league's status is `pre_draft` or `drafting`, **When** opened, **Then** the list
   says the league hasn't drafted, rather than presenting a waiver wire (F5).
5. **Given** a completed season, **When** opened, **Then** there's no streaming list, and the page
   says it's for the current season. No refresh is promised (F3).
6. **Given** a player whose last game is months old, or who has no NBA team, **When** listed,
   **Then** he's excluded, and the excluded count is shown (F2).

---

### User Story 3 - Correct "rostered" between draft and week 1 everywhere it's read (Priority: P2)

The current roster players from the refresh are stored, so "rostered by" is right from the moment
the draft ends.

**Why this priority**: US2 needs it. It also fixes the same pre-week-1 gap for any later reader.
Spotlight keeps its own source in this spec, and switching it is a named follow-up.

**Independent Test**: after a refresh of a drafted league, the stored players per roster equal
Sleeper's `/rosters`.

### Edge Cases

- A player traded mid-season: team is Sleeper's current `player.team`, refreshed daily, so it
  lags by up to a day (amended after review F10). Players with no team are excluded.
- All-Star game rows are not games (F9).
- Early in a season the lists read the previous season, labelled, until half the teams have
  played each list's minimum (F1).
- Overtime: team minutes > 240. Usage uses the actual team minutes from the `TEAM_*` row.
- A player with fewer than 5 games this season: no season baseline, and he's not in the role-change
  lists. He can still stream once he has 3 games in the form window. Hand-set, labelled.
- A game with a `TEAM_*` row missing: usage is null for that game, and the others still count.
- Football league: no Trends destination, and the API answers "unavailable" with a reason.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Minutes MUST be `sp / 60` from `player_game`, excluding every `TEAM_*` id.
- **FR-002**: Role change MUST compare the **median** of the last 3 games played with the mean of
  the season's games played. The ± threshold is hand-set in `config/weights.yml` (ARBITRARY). The
  season baseline needs ≥ 5 games.
- **FR-003**: Usage rate MUST be the standard formula,
  `100 · ((FGA + 0.44·FTA + TOV) · (TmMIN/5)) / (MIN · (TmFGA + 0.44·TmFTA + TmTOV))`, from the
  player's game and his team's `TEAM_*` row in the same `game_id`, averaged over the window. It's
  labelled "usage rate".
- **FR-004**: League points per minute MUST use `GameScoringService` with the league's scoring.
- **FR-005** *(amended after review F7)*: Streaming MUST rank free agents by season-to-date mean
  league points (min 3 games), show last-5 form beside it, and MUST NOT filter by game count. Games this week and next come from `sport_schedule`
  (spec 017), using the same counting rule as the grid.
- **FR-006**: "Rostered" MUST come from the stored current roster players (V28), written by the
  existing refresh from the `/rosters` response it already fetches. No new Sleeper call.
- **FR-007** *(amended after review F8)*: The page MUST state the value of game count only when
  this league's own stored weeks show one-game crediting (≥ 90%), citing the measurement's scope.
- **FR-008** *(amended after review F1)*: Each list MUST read the previous season, labelled, until
  half the teams have played that list's minimum games in the new one.
- **FR-009**: Basketball-only destination via `destinations.ts` `sports: ['nba']`. The API answers
  `available: false` with a reason code for football.
- **FR-010**: `web/src/api.ts` mirrors the records in the same change. Responses are records.
- **FR-011**: Phone width with no page-level horizontal scroll.

## Success Criteria *(mandatory)*

- **SC-001**: Jokić 2025-10-23 shows 40.8 min (box score 40:50).
- **SC-002**: The role-change tests in US1 pass, and no `TEAM_*` id appears in any response.
- **SC-003**: On NBA 2026 after the 10-10 draft, 0 drafted players appear among the free agents
  (checked against Sleeper `/rosters`).
- **SC-004**: The games column equals the schedule grid's for the same team and week.
- **SC-005**: Live in production before 2026-10-20.

## Assumptions

- No projections. This is realized data only. Projections are roadmap Phase 3.
- No snap share for football (not stored).
- The player-card minutes sparkline from the design doc isn't built. The Trends page is the
  surface. It's a named follow-up.
