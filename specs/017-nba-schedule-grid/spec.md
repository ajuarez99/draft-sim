# Feature Specification: NBA schedule grid and next opponent for basketball

**Feature Branch**: `017-nba-schedule-grid`

**Created**: 2026-10-05

**Status**: Draft

**Input**: User description: "start on phase 1". That's Phase 1 of
`claude/competitor-gap-roadmap.md`: 1.1 store the NBA schedule, 1.2 schedule grid +
playoff-weeks view, 1.3 NBA next opponent on league home. It has to ship before the NBA
season starts on **2026-10-20**.

## Background

Measured 2026-10-05 (research R1):

- `GET /schedule/nba/regular/2026` returns 1,200 games over weeks 1–25, all `pre_game`.
  **Every team has 80 games, not 82.** The design doc's acceptance criterion #1 ("every
  team's season total = 82") is wrong for a pre-season fetch. The 2025 schedule ends
  with 1,235 games, so Sleeper adds games during the season. That pattern fits the NBA
  setting each team's last two games after the NBA Cup group stage, but that's an
  inference, not a measurement.
- 2025's schedule keeps **postponed games and their makeups as separate rows**: six
  teams show 83 entries. It also has a **canceled All-Star exhibition** (`STP` vs `STR`).
  NYK and SAS also show 83. That fits the Cup final being in the schedule (inferred).
  So counting raw rows gives wrong numbers both ways.
- Week 1 of 2026: 5 teams play 2 games, 24 play 3, 1 plays 4. That matches the design
  doc. Week 8 (Dec 12–13) has 28 teams playing once.
- NBA "Ball Knowers" 2026 (`1339351318115946496`) is `pre_draft`, `leg` 1,
  `playoff_week_start` 20, 6 playoff teams, `playoff_round_type` 0. Its week-1 matchups
  come back empty, so no pairings are published yet.
- The schedule is already fetched on every per-game refresh (`PlayerGameIngestService`
  step 1) and then thrown away.

**Correction to the design doc (shown, not hidden):** `claude/nba-schedule-grid-and-streaming.md`
acceptance #1 gets an amended note: 80 per team on 2026-10-05, and the count changes during the season.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See how many games each NBA team plays each week (Priority: P1)

A manager in a basketball league opens the schedule grid and sees, for every NBA team,
how many games it plays in each upcoming fantasy week. The number is printed in each
cell. They can sort teams by games over the next few weeks to pick who to start or
pick up.

**Why this priority**: in a points league with one matchup per week, a player's week
is about per-game value × games. A 4-game team is worth roughly twice a 2-game one.
Every basketball competitor has this grid, and it's worth most in week 1.

**Independent Test**: open `/leagues/{nba id}/schedule` for the NBA 2026 league. The
week-1 column shows 5 teams at 2, 24 at 3 and 1 at 4.

**Acceptance Scenarios**:

1. **Given** the NBA 2026 schedule is stored, **When** the grid is opened, **Then**
   there's one row per NBA team and one column per week from the league's current week
   to the last scheduled week. Each cell prints that team's game count, and its color
   encodes the same count.
2. **Given** the grid, **When** the reader sorts by "next N weeks" (N = 1–4), **Then**
   teams order by their summed games over weeks *current* … *current+N−1*, most first,
   with ties broken by team code.
3. **Given** a postponed or canceled game, **When** counts are computed, **Then** it
   isn't counted, and the makeup game counts in the week Sleeper schedules it.
4. **Given** a football league, **When** the rail is shown, **Then** it has no schedule
   link. A direct API call gets an "unavailable" answer with a reason, not an error.
5. **Given** the schedule has never been stored for this season, **When** the grid is
   opened, **Then** it says so ("not loaded yet") rather than showing an empty grid as
   if no team played.

---

### User Story 2 - See games during your league's playoff weeks (Priority: P2)

The same grid, limited to the league's own playoff weeks, with a total per team. Teams
sort by playoff-week games. This is the most-asked planning question in basketball
leagues.

**Why this priority**: it costs almost nothing once US1 exists. It matters later in the
season, but trades for playoff schedules start early.

**Independent Test**: for NBA 2026, the playoff view shows weeks 20–22 (start 20,
6 teams = 3 rounds, one week per round).

**Acceptance Scenarios**:

1. **Given** `playoff_week_start` 20, 6 playoff teams and `playoff_round_type` 0, **When**
   the playoff view is opened, **Then** it shows weeks 20, 21 and 22 and a per-team
   total, sorted by that total.
2. **Given** a league with a `playoff_round_type` other than 0, or no
   `playoff_week_start`, **When** the playoff view is opened, **Then** it says the
   playoff weeks can't be worked out for this league's format. It doesn't guess.
3. **Given** a playoff week the NBA hasn't finished scheduling, **When** counts are
   shown, **Then** the page still notes that the schedule changes during the season and
   when it was last fetched.

---

### User Story 3 - Basketball league home shows your next opponent (Priority: P3)

A member opening an NBA league's home page sees who they play this week. Today that
block is football-only, because it comes from the projection payload.

**Why this priority**: it's small, and the season start makes it visible. But it shows
nothing until Sleeper publishes pairings (after the draft).

**Independent Test**: for a league with stored pairings for its current week, the home
block names the opponent from `league_matchup`. That matches Sleeper's
`/league/{id}/matchups/{week}` for the same week.

**Acceptance Scenarios**:

1. **Given** an NBA league with stored pairings for the current week, **When** a member
   opens league home, **Then** "Next opponent" names their opponent for that week.
2. **Given** pairings not yet published (NBA 2026 today), **When** league home is
   opened, **Then** the block says pairings for week N aren't out yet. That isn't an
   error.
3. **Given** an NFL league, **When** league home is opened, **Then** the opponent comes
   from the same endpoint, and the "Projected X to Y" line still shows when the
   analysis payload is valuing the same week.
4. **Given** a reader with no roster in the league, or a bye, **When** league home is
   opened, **Then** the existing contract holds: no "you" block and no error, or "you
   have a bye in week N".

### Edge Cases

- **Schedule changes in season** (Cup games added, postponements): the stored schedule
  is replaced on each refresh, so a week's counts can change. The page shows when the
  schedule was fetched.
- **A fetch that returns no games** (outage, non-list body): stored rows are kept, not
  wiped. An empty answer isn't "the season has no games".
- **Non-NBA team codes** (the All-Star `STP`/`STR` exhibition): they're excluded when
  the game is canceled, as in 2025. If one ever appears un-canceled, it's shown as
  scheduled rather than silently filtered out (research R4).
- **Season over** (`leg` past the last scheduled week, or league `complete`): the grid
  shows no upcoming weeks and says the season is over. The playoff view still works
  for looking back.
- **The league's current week** comes from its stored `settings.leg`, so it's as fresh
  as the league's last refresh.
- **NFL schedules** get stored too (the fetch already happens). Nothing reads them yet.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST store each sport-season's regular-season schedule (game,
  week, date, home, away, status) from the fetch the per-game refresh already makes,
  replacing that season's stored rows in one transaction.
- **FR-002**: An empty or unparseable schedule answer MUST NOT replace stored rows.
- **FR-003**: The week of a game MUST be Sleeper's `week` field, never derived from
  dates.
- **FR-004**: A team's game count for a week MUST count only games whose status isn't
  `postponed` or `canceled`. This rule MUST live in one place, shared by the grid and
  by anything else that counts games.
- **FR-005**: There MUST be one parser for Sleeper's schedule rows (including the
  sport-dependent home/away shape), shared by the per-game ingest and the stored
  schedule.
- **FR-006**: `GET /api/leagues/{id}/schedule` MUST return, for a basketball league,
  the per-team per-week counts from the league's current week onward, the full
  season's weeks with their date ranges, the playoff weeks (or why they can't be
  computed), and when the schedule was fetched. It's scoped like every other league
  route.
- **FR-007**: The playoff weeks MUST be `playoff_week_start` … `playoff_week_start +
  rounds − 1`, where rounds = ⌈log₂(playoff_teams)⌉, and only when `playoff_round_type`
  is 0. Any other value MUST give a stated reason, not a guess.
- **FR-008**: A `schedule` destination MUST be offered to basketball leagues only,
  through the existing `sports` gate in `LEAGUE_DESTINATIONS`. The rule isn't restated
  anywhere else.
- **FR-009**: `GET /api/leagues/{id}/next-matchup` MUST return the reader's pairing for
  the league's current week (`settings.leg`) from stored `league_matchup` rows, for
  both sports. "You" MUST be resolved the way the other blocks do it (Sleeper user →
  manager id → roster), never by a name match.
- **FR-010**: League home MUST take "next opponent" from FR-009 for both sports. It
  shows the projected line only for football, and only when the analysis payload's
  matchup week equals FR-009's week.
- **FR-011**: `web/src/api.ts` types MUST mirror the new response records field for
  field, in the same change.

### Key Entities

- **Scheduled game**: one game in a sport-season. It has a Sleeper game id, the
  fantasy week Sleeper assigns, a date, home and away team codes, a status, and when it
  was fetched.
- **Team-week count**: derived. A team's counted games in one week (FR-004).
- **Playoff window**: derived from league settings (FR-007), or a reason it can't be
  derived.
- **Next matchup**: derived. The league's current week, the reader's roster, and the
  opponent roster (or a bye) from stored pairings.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After a refresh of NBA 2026, the stored schedule holds exactly the games
  Sleeper returned that day (1,200 on 2026-10-05), and each team's counted total
  equals the count from a fresh fetch.
- **SC-002**: The grid's week-1 column for NBA 2026 shows 5×2, 24×3, 1×4, or the doc is
  amended with Sleeper's new numbers.
- **SC-003**: For NBA 2025 (stored from its finished schedule), the counted season
  total is 82 for every team except NYK and SAS (83), and STP/STR don't appear. That
  proves the postponed/canceled rule on real data.
- **SC-004**: The NBA 2026 playoff view shows weeks 20–22. Checked the same way, the
  NBA 2025 view shows 19–21, and that matches the league's measured
  `last_scored_leg` of 21.
- **SC-005**: NFL league home shows the same next opponent before and after the change
  for the current week (measured: `leg` 4 = last stored + 1 on 2026-10-05).
- **SC-006**: Live check in a real browser before 2026-10-20 on the NBA "Ball Knowers"
  2026 league, through the real `X-Sleeper-User` header path (bug class #6).

## Assumptions

- `playoff_round_type` 0 means one week per round. Measured once: NBA 2025 (6 teams,
  start 19) ended with `last_scored_leg` 21 = 19 + 3 − 1. Other values aren't
  interpreted (FR-007).
- Sleeper's schedule `week` equals the league's matchup `leg` for basketball. That's
  reasoned from the per-game ingest already relying on it (absence classification,
  and memory's reconciliation to Sleeper's fpts). quickstart has a direct check.
- Out of scope: "your roster's games this week" (roadmap 4.x), streaming candidates
  (2.4), an NFL bye grid, category-league tools, and daily lineups.
