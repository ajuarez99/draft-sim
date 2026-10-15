# Feature Specification: Rivalry schedule grid and rivalry week recommendation

**Feature Branch**: `034-rivalry-schedule-grid`

**Created**: 2026-10-14

**Status**: Draft

**Input**: User description: "i want a schedule grid but with head to head on opponents but
for the leagues history and then a section for a rivalry week recommendation and why", then
"for basketball and football".

**Clarified with Allan 2026-10-14, before writing:**

- The grid comes in **two views**. One is an all-time manager × manager head-to-head matrix
  across the league's history. The other is this season's schedule (manager × week), where
  each cell shows that week's opponent and the all-time record against them.
- The rivalry week recommendation is a set of **custom pairings**: for one chosen week,
  every manager is paired with a rival, and each pair gets its reason. It's for a
  commissioner to set by hand in Sleeper. It is not a pick of an existing week from the
  schedule.

## Background

What exists now (checked in code 2026-10-14, not taken from a planning doc):

- `HeadToHeadService` (spec 006) already computes per-sport H2H between **two chosen**
  managers across every league they shared, at `/managers/:a/versus/:b`. A meeting counts
  only when both sides have a stored score. Results are never summed across sports.
- `LeagueMatchupRepository.scheduledPairs` returns a season's scheduled pairings, including
  weeks that haven't been played.
- `/leagues/:id/schedule` is the NBA team-games grid (spec 017). It's about NBA teams, not
  managers, so it's a different grid.
- `claude/hall-of-fame-and-rivalries.md` (roadmap 4.1, design only, not built) proposes
  rivalry discovery: most-played, closest, nemesis/victim, and a minimum-meetings threshold
  N. It also says the regular-season/playoff split needs the league's playoff start week.
  **That week is not stored in any migration today** (grep 2026-10-14). Where it comes from
  is a plan-stage question, recorded under Dependencies. *(Wrong: see the amended note under
  Dependencies.)*
- **Thin data.** Each chain has 2–3 seasons, so most pairs have met 2–5 times. That's the
  central limit on everything below.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - All-time head-to-head matrix for my league (Priority: P1)

A manager opens their league's rivalry page and sees a grid. There's one row and one column
for each manager in the current season, and each cell is the row manager's all-time record
against the column manager in this league's history (W-L, or W-L-T when there are ties). The
cell also prints how many times the two have met. Clicking a cell opens the existing versus
page for that pair.

**Why this priority**: it's the base layer. The other two stories read from these same
numbers, and the matrix answers "who owns whom in this league" without the reader having to
pick two names first.

**Independent Test**: on "(Foot) Ball Knowers", every off-diagonal cell equals the record the
existing versus page shows for that pair, limited to this league chain. Assert it in a test
rather than eyeballing it.

**Acceptance Scenarios**:

1. **Given** a league with scored history, **When** the matrix is opened, **Then** cell (A, B)
   is A's wins, losses and ties against B across this league chain, and cell (B, A) is its
   mirror (A's wins are B's losses).
2. **Given** two managers who shared seasons but never played each other, **When** the matrix
   renders, **Then** their cell reads "never met", not "0-0".
3. **Given** a manager who is in the league this season but has no prior history in it,
   **When** the matrix renders, **Then** their row and column are present, and the cells say
   "no history" rather than being dropped.
4. **Given** a basketball league and a football league owned by the same managers, **When**
   either matrix is opened, **Then** it counts only that sport's league chain. Sports are
   never combined.
5. **Given** a pair with playoff meetings, **When** the cell is expanded or hovered, **Then**
   regular-season and playoff records are shown separately, and together they add up to the
   cell's total.

---

### User Story 2 - This season's schedule with history on every opponent (Priority: P2)

A manager looks at the current season as a grid: one row per manager, one column per week.
Each cell names that week's opponent and the row manager's all-time record against them. If
the week has been played, the cell also shows that game's result. The manager can scan their
own row to see which upcoming weeks are grudge matches.

**Why this priority**: it's the "schedule grid" the request names. It puts the history from
US1 where it matters week to week. It depends on the season's pairings being published, which
isn't guaranteed early.

**Independent Test**: for each scheduled week, the opponent in cell (A, week) matches the
stored pairing for that week. The record printed in the cell matches US1's (A, opponent) cell
before that week's game.

**Acceptance Scenarios**:

1. **Given** published pairings for the season, **When** the grid opens, **Then** every
   manager-week cell names exactly one opponent, or "bye" if there is none. Opponents are
   symmetric: if A plays B in week W, B's cell for week W names A.
2. **Given** a played week, **When** the cell renders, **Then** it shows that game's result
   (W/L/T and score) as well as the all-time record against that opponent.
3. **Given** the all-time record printed in a cell, **When** it's checked, **Then** it counts
   only meetings *before* that week. A week-3 cell never includes the week-3 game in its own
   "history" number.
4. **Given** a season whose pairings Sleeper hasn't published yet (e.g. pre-draft), **When**
   the grid opens, **Then** the page says the schedule isn't published yet. It doesn't render
   an empty grid.
5. **Given** a playoff week, **When** the grid renders, **Then** that column is visibly marked
   as playoffs. Weeks without scheduled pairings aren't padded with invented opponents.

---

### User Story 3 - Rivalry week: recommended pairings and why (Priority: P3)

A commissioner opens the rivalry week section and sees a recommended full set of matchups for
one week. Every manager is paired with exactly one rival (in a league with an odd number of
managers, one manager is left unpaired, and the page names them). Each pair has a one-line
reason drawn from their history, such as "closest all-time record (4-4 in 8 meetings)", "most
meetings in league history", "met in the 2025 championship", or "B has won the last 3". The
commissioner can use this to set a rivalry week by hand in Sleeper.

**Why this priority**: it's the novel part, and it's built entirely on US1's numbers. It's
only worth something if the pairings it picks are believable, and that's hardest with thin
history.

**Independent Test**: on a league with known history, the recommendation pairs every manager
exactly once, and every reason's numbers match US1's matrix for that pair.

**Acceptance Scenarios**:

1. **Given** an even number of managers, **When** the recommendation is generated, **Then**
   every manager appears in exactly one pair.
2. **Given** an odd number of managers, **When** it's generated, **Then** exactly one manager
   is left unpaired, and the page says who and why (e.g. "fewest qualifying rivalries").
3. **Given** a pair, **When** its reason is shown, **Then** the reason names a concrete fact
   from their shared history (a record, a meeting count, a playoff game, a streak) with the
   numbers printed. No generic copy like "a great matchup".
4. **Given** a pair with fewer meetings than the stated minimum N, **When** it's shown,
   **Then** it's labelled as a thin-history pairing. N is printed on the page as a hand-set
   threshold, not presented as principled. A 2-0 record is never called a "rivalry" without
   that label.
5. **Given** the same league data, **When** the recommendation is generated twice, **Then**
   it's identical. It's deterministic, not random.
6. **Given** a recommendation, **When** the page renders, **Then** it states plainly that
   Sleeper's schedule isn't changed by this tool, and that a commissioner has to set the
   matchups themselves.
7. **Given** a league with no prior seasons (first year), **When** the section opens, **Then**
   it says there isn't enough history to recommend rivalries yet, instead of pairing people
   on nothing.

---

### Edge Cases

- **Manager turnover**: a seat that changed owners between seasons. History follows the
  *manager*, not the roster slot. A new owner of roster 5 doesn't inherit the old owner's
  record.
- **A manager who left the league**: their history still counts toward other managers'
  records, but they get no row in the matrix (rows are current-season managers only). That
  rule is printed on the page.
- **Ties**: shown as a third number, and only when nonzero.
- **Unscored or postponed weeks**: never count as a meeting (the existing rule).
- **A league chain that changed size** (e.g. 10 → 12 teams): the matrix reflects whoever was
  present in each season. Pairs who only overlapped for one season say so through their small
  meeting count.
- **Scheduled-but-unplayed playoff weeks**: Sleeper's bracket pairings for future playoff
  weeks may not exist yet. Those columns show "TBD", never a guessed opponent.
- **Both sports**: football (one game per week) and basketball (one matchup per week, also
  one meeting per week) count meetings the same way. A basketball week is one meeting, not
  several games.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST show, for a league, an all-time manager × manager head-to-head
  matrix covering that league's own history (its chain of seasons), for both football and
  basketball leagues.
- **FR-002**: Each matrix cell MUST show wins, losses, ties (when nonzero) and meeting count,
  and MUST distinguish "never met" and "no history" from a 0-0 record.
- **FR-003**: Matrix records MUST agree with the existing two-manager head-to-head comparison
  for the same pair, limited to the same league chain.
- **FR-004**: Each pair's record MUST be splittable into regular season and playoffs, and the
  two parts MUST add up to the total.
- **FR-005**: Records MUST never combine different sports or different league chains.
- **FR-006**: The system MUST show the current season as a manager × week grid where each cell
  names that week's opponent (or bye/TBD) and the row manager's all-time record against that
  opponent as of before that week.
- **FR-007**: Played weeks in the season grid MUST also show that game's result.
- **FR-008**: The system MUST produce a rivalry week recommendation: a full set of pairings
  for one week in which each manager appears at most once, and at most one manager is
  unpaired (only when the count is odd).
- **FR-009**: Every recommended pair MUST carry a reason stating a concrete historical fact
  with its numbers, drawn from the same data as the matrix.
- **FR-010**: The recommendation MUST be deterministic for the same input data.
- **FR-011**: Pairs below a minimum meeting count N MUST be labelled as thin-history. N MUST
  be shown on the page and labelled as a hand-set threshold.
- **FR-012**: The page MUST state that the tool doesn't change the Sleeper schedule.
- **FR-013**: When there's no history (first season) or no published schedule, the relevant
  section MUST say so in words instead of rendering an empty or invented grid.
- **FR-014**: The views MUST follow the league's existing scoping. A user sees only leagues
  they already have access to.

### Key Entities

- **Pair record**: two managers in one league chain and one sport. It holds wins, losses,
  ties and meeting count, split regular season/playoffs, plus the list of meetings (season,
  week, scores, playoff flag).
- **Season schedule cell**: one manager, one week of the current season, an opponent (or
  bye/TBD), the prior record against that opponent, and the result if played.
- **Rivalry pairing**: two managers (or one unpaired manager), a reason kind (closest record,
  most meetings, playoff history, streak, etc.), the numbers behind it, and a thin-history
  flag.
- **Rivalry week recommendation**: the full set of pairings for one league, the threshold N
  used, and any unpaired manager with the reason they were left out.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For "(Foot) Ball Knowers" and NBA "Ball Knowers", 100% of matrix cells match the
  existing versus comparison for that pair within the same chain (asserted by test, not by
  eye).
- **SC-002**: For every pair, regular-season and playoff records add up to the total, in 100%
  of cases.
- **SC-003**: In the season grid, 100% of opponents are symmetric across the two managers'
  rows for every scheduled week, and no cell's "prior record" includes that week's own game.
- **SC-004**: The rivalry recommendation pairs every manager exactly once (or all but one when
  odd) on both real leagues. Each reason's numbers match the matrix.
- **SC-005**: A manager can find their all-time record against their next opponent within one
  click of the rivalry page opening.
- **SC-006**: No rivalry is presented without its meeting count beside it, and no pair under N
  is shown without the thin-history label (asserted by test).
- **SC-007**: Both views render for both sports on live data, checked in a real browser, not
  only by the test suite.

## Assumptions

- **"League history" means this league's own chain of seasons** (the league plus its previous
  seasons as Sleeper links them), not every league two managers have shared. Two managers who
  meet in two different leagues have two separate rivalries (same rule as the hall-of-fame
  doc).
- **Matrix rows are current-season managers.** Former managers' games still count toward the
  current managers' records.
- **"Rivalry week" picks one week's pairings, not which week.** The commissioner chooses the
  week. The tool doesn't recommend a week number, because a commissioner-set week is a league
  decision, not a data question.
- **Pairing objective**: favor pairs whose history makes the best story: close records with
  many meetings first, then playoff history and streaks. The exact scoring and how it trades
  off against a full matching is a plan-stage decision. The scoring must come with a test that
  asserts *preference ordering* (a 5-5 pair in 10 meetings beats a 1-0 pair), per the repo's
  recurring sign-inversion bug class.
- **N defaults to a hand-set value** (the hall-of-fame doc's "≥ N meetings"). It's chosen at
  plan time, labelled arbitrary, and never retuned to make output look better.
- **It lives on its own league page** (a "Rivalries" page reached from the league's history
  area), not on the NBA team-games schedule grid. That grid is about NBA teams, and mixing the
  two would put two different "schedule grids" under one name.
- **No new data is fetched for the records.** They come from matchups and scores that are
  already stored.

## Dependencies

- **Playoff start week per league-season** is needed for FR-004 and US2's playoff column, and
  it isn't stored today. The plan has to decide whether to store it (a new append-only
  migration, filled at ingest) or read it some other way. Until then the playoff split can't
  be claimed as verified.

  > **Amended at plan stage, 2026-10-14 ([research.md](research.md) R1, R2):** the line above
  > is wrong. The playoff start week **is** stored, in `league.settings_json`, and
  > `LeagueRepository.playoffFormat` already reads it. The grep looked for a column and missed
  > the JSON. The real gap is a different one, found by measurement: about half of a playoff
  > week's games are **consolation** games, and only the champion's placement is stored. So
  > splitting playoffs from consolation needs Sleeper's winners bracket stored (new table V32).
  > FR-004's split is therefore regular / playoff / consolation, plus "unsplit" for a season
  > with no bracket, and the parts still add up to the total.
- Overlaps with roadmap item 4.1 (`claude/hall-of-fame-and-rivalries.md`): the nemesis/victim
  and "closest" ideas there are the same computation as this spec's reasons. The plan should
  build one implementation that both can use, not two (the two-implementations-of-one-rule
  bug class).

## Not building

- Changing Sleeper's schedule. Sleeper's API is read-only for this app.
- Recommending which week should be rivalry week.
- Cross-league or cross-sport rivalries.
- The rest of the hall-of-fame doc (trophy case, champions list). It stays roadmap 4.1.
