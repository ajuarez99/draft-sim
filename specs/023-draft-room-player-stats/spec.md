# Feature Specification: Player stats in the draft room, with stats you choose

**Feature Branch**: `023-draft-room-player-stats`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "now that we have the player stats page i want that on the live draft when im drafting to see upcoming players. i want to be able to pick and choose stats i also care about that i think is good to make a decision"

## Context: what exists *(read first)*

### What the live draft room shows about a player today

The live room (`/drafts/:draftId/live`) is board-first. Players still on the board are listed in
a floating **available-players sheet**. It groups them into tiers by ADP and shows how likely each
one is to survive to your next picks. It also tags players who fill one of your open starting
slots. The sheet shows a player's face, name, position, ADP and survival. It shows **no
real-basketball stats**: no points, rebounds, shooting or minutes, and no fantasy points per game
under this league's scoring.

### What spec 022 built that this feature reuses

Spec 022 (branch `022-player-stat-analysis`, built and verified 2026-10-08, **not yet merged or
deployed**) added:

- a league-scoped **player page** for NBA players; and
- a **stat leaderboard** whose columns come in groups (Basic, Shooting, Advanced, Fantasy, Draft
  value). It has season / last 10 / last 5 windows, per-game / totals / per-36 modes, a
  minimum-minutes rule, and worded reasons wherever a number is missing.

When the requested season has no games yet, the leaderboard already falls back to the most recent
season with games, and says so (amended 2026-10-08). That is exactly the state of every preseason
draft. This feature is a second place to read those same numbers. It does not compute any stat of
its own.

### Why the season matters here

A draft happens **before** the season it is for. During the "Ball Knowers" NBA 2026 draft
(scheduled 2026-10-10), the 2026–27 season has no games, so every stat in the room is from
**2025–26**. Rookies have no NBA games at all. The room must say which season it is showing, and
must never show a rookie as a row of zeros.

## Clarifications

### Session 2026-10-08

- Q: Must this be live for the "Ball Knowers" NBA draft on 2026-10-10? → A: Yes, aim for it.
  Merge spec 022 and this feature and deploy both, **if they are verified working live before
  the draft**. If they aren't, the draft goes ahead without them. This overrides the merge freeze
  that was planned to run until 10-10, for 022 and 023 only.
- Q: Do chosen stats only show and sort, or also feed a personal weighted ranking? → A: Option C.
  Show and sort now. Weighting is spec'd as a later story (US4, P3) and not built in this round.
  The stat picker is a **modal** that pops up over the room. The user named usage rate as an
  example of a stat they want to add, so it must be offered there.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See real stats for the players still on the board (Priority: P1)

During a live basketball draft, the member switches the available-players sheet from its usual
tier list to a **stats view**. It is a table with one row per player still undrafted and one
column per stat, with the player-name column pinned. The table names the season its numbers come
from (for example, "2025–26 stats, regular season"). Each row also keeps the facts the sheet
already shows (position, ADP and, once the member's seat is known, survival to their next pick),
so switching views doesn't lose them.

When a pick lands, that player leaves the table at the same moment he leaves the board. The
member can narrow the table to the players likely to still be there at their next pick, which is
what "upcoming players" means at the moment of choosing. Clicking a name opens that player's full
page from spec 022 without leaving or losing the draft room.

**Why this priority**: This is the core request. Without it, deciding between two players means
leaving the draft to look them up somewhere else while the clock runs.

**Independent Test**: Open the live room for a basketball draft in progress, or a replayed one.
Switch to the stats view and check five players' values against the same players on the spec 022
leaderboard for the same season and window. Land a pick and check that the player leaves both the
board and the table.

**Acceptance Scenarios**:

1. **Given** a live basketball draft, **When** the member opens the stats view, **Then** every
   undrafted player is listed once with the default stat columns, and the table names the season
   and window its numbers come from.
2. **Given** a draft for a season that has no games yet, **When** the stats view is shown,
   **Then** it shows the most recent season with games, and states that this is last season's
   play and not a projection.
3. **Given** a pick lands, **When** the board updates, **Then** the picked player is gone from
   the stats view in the same update. The member's sort, filters and scroll position are kept.
4. **Given** a rookie or any player with no games in the shown season, **When** he appears in the
   table, **Then** his row says why there are no numbers ("No NBA games in 2025–26"; amended
   at planning, because the room can't tell a rookie from a veteran who didn't play, research R4), he sorts after every player with a value, and no cell shows 0 for a stat he never had a
   chance to record.
5. **Given** the member's seat is known, **When** they turn on "likely there at my next pick",
   **Then** only players whose survival to that pick meets a stated threshold are shown. The
   threshold and the pick number are both printed. **Given** the seat is not known yet, **Then**
   the filter is unavailable and the room says why, as the sheet already does for survival.
6. **Given** any value in the table, **When** the same player's page (spec 022) is opened for the
   same season and window, **Then** it shows the same value.
7. **Given** a name in the table, **When** it is clicked, **Then** the player's page opens
   without ending the member's connection to the live draft, and returning shows the room in its
   current state, including picks that landed meanwhile.
8. **Given** a rate stat for a player below the minimum-minutes rule, **When** it is shown,
   **Then** it carries the same small-sample label the leaderboard uses.
9. **Given** the stats cannot be loaded (for example, spec 022's data isn't set up on the server,
   or the request fails), **When** the stats view is opened, **Then** it says so in words, and the
   tier list, board and pick feed keep working.
10. **Given** a phone-width screen of 375 pixels, **When** the stats view is shown, **Then** the
    name column stays pinned, the other columns scroll sideways within the table, and the page
    itself never scrolls sideways.

---

### User Story 2 - Choose the stats that matter to me (Priority: P1)

From the stats view, the member opens a **stat picker** in a modal over the room. It lists every stat available to show,
grouped as the leaderboard groups them (Basic, Shooting, Advanced, Fantasy). Each stat has its
short label and its plain-language name. The member turns stats on and off and puts them in the
order they want. The table updates immediately. They sort the table by any shown stat. Their
choice is remembered, so the next time they open a draft room (live or tomorrow's), the table
shows the same stats in the same order. A "reset to default" option restores the starting set.

**Why this priority**: The user asked for this in so many words: "pick and choose stats i also
care about". A fixed set of columns would answer someone else's question. It shares P1 with US1
because US1 without it is only the default view.

**Independent Test**: Choose a set of four stats in a chosen order, for example FP/G, MIN, USG
and TS%. Sort by one of them and check the order against the leaderboard. Close the browser, open
a different basketball draft room, and check that the same four stats appear in the same order.
Reset, and check that the defaults return.

**Acceptance Scenarios**:

1. **Given** the stat picker, **When** it opens, **Then** it appears as a modal over the room
   without pausing live updates behind it. It lists every stat from the leaderboard's Basic,
   Shooting, Advanced and Fantasy groups, including usage rate. It shows which ones are on, and
   gives each stat's full name beside its short label.
8. **Given** the modal is open, **When** a pick lands, **Then** the modal stays open, the
   member's unsaved toggles are kept, and the table behind it updates.
2. **Given** the member turns a stat on or off, or moves it, **When** they do, **Then** the table
   reflects it at once, without reloading the draft or interrupting live updates.
3. **Given** a chosen set of stats, **When** the member opens any later basketball draft room on
   the same device, **Then** the same stats appear in the same order.
4. **Given** the member turns every stat off, **When** the table is shown, **Then** it still
   shows player names and says no stats are chosen, and it offers the reset.
5. **Given** a column header, **When** it is chosen, **Then** rows sort by that stat in its
   natural direction (best first, where turnovers and turnover rate count fewer as better).
   Choosing it again flips the direction. Ties and missing values sort exactly as they do on the
   leaderboard.
6. **Given** a saved choice that names a stat the app no longer offers, **When** the room loads,
   **Then** that stat is skipped without error, and the rest of the choice is kept.
7. **Given** "reset to default", **When** it is chosen, **Then** the default set and order
   return, and the reset is what is remembered from then on.

---

### User Story 3 - The same view in a mock draft (Priority: P2)

A member practising in a basketball mock draft gets the same stats view and the same stat
choice, as in the live room. A choice made in either place shows in both.

**Why this priority**: The live room is where the decision counts. The mock room is where a
member finds out which stats they want before it counts. The available-players sheet is the same
component in both rooms, so this is mostly a matter of turning it on. It can still ship after US1
and US2 without them losing value.

**Independent Test**: Start a basketball mock draft, open the stats view and check that it
matches US1's acceptance scenarios 1–4 and US2's scenario 3. Make a choice in the mock room, then
open a live room and check that the choice carries over.

**Acceptance Scenarios**:

1. **Given** a basketball mock draft, **When** the member opens the stats view, **Then** it
   behaves as in the live room. The "likely there at my next pick" filter is not offered, because
   a mock room has no survival numbers, and the room says so.
2. **Given** a stat choice made in a mock room, **When** a live room is opened on the same
   device, **Then** it uses that choice.

---

### User Story 4 - Weight my chosen stats into my own ranking (Priority: P3, not in this round)

The member gives each chosen stat a weight, and the table gains a "my score" column that ranks
the pool by that formula. The column is labelled as the member's own formula, not a projection,
and the weights are shown beside it.

**Why this priority**: The user chose to have this spec'd but built later (clarification
2026-10-08). In a points league, fantasy points per game already weights the stats by the
league's scoring, so this adds the most for stats that scoring ignores, such as usage rate or
true shooting.

**Independent Test**: Deferred to the round that builds it. Before that round starts, it needs
its own clarification of how to combine stats on different scales.

**Acceptance Scenarios**: Not written in this round, on purpose. Writing them now would present
an unresolved design (the scaling rule) as decided.

---

### Edge Cases

- **Football drafts**: spec 022's stats are basketball-only. In a football room the stats view is
  not offered. The available-players sheet is unchanged, so there is no empty table and no
  basketball layout with blank cells.
- **Traded players**: a player who played for two teams in the shown season shows one season
  line across both, as on the player page, and the row notes he played for more than one team.
- **Players with no position on file**: they still appear, with position shown as unknown. A
  position filter can't hide every such player without saying so.
- **The draft finishes**: once every pick is made, the stats view shows an empty pool with a line
  saying the draft is complete. It does not show an error.
- **A pick is undone or corrected upstream**: if a player returns to the pool on the board, he
  returns to the stats view in the same update.
- **A player missing from the stats data** (on the board but with no stored games, and not
  flagged a rookie): he is listed with a worded reason, never dropped silently. The table's row
  count matches the board's undrafted count.
- **Fantasy points from last season under this season's scoring**: the drafting league's scoring
  can differ from last season's league. Whichever scoring is used, the table names it (see
  FR-006).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The live draft room for a basketball draft MUST offer a stats view of the players
  still undrafted. The member reaches it from the available-players sheet, alongside the existing
  tier list.
- **FR-002**: Every stat value in the stats view MUST equal the value the spec 022 leaderboard
  shows for the same player, season, window and mode. The room MUST NOT compute stats of its own.
- **FR-003**: The stats view MUST name the season and window its numbers cover. When the draft's
  own season has no games, it MUST show the most recent season with games and say it is past
  play, not a projection.
- **FR-004**: A player picked on the board MUST leave the stats view in the same update. A player
  returned to the pool MUST reappear. The member's sort, filters and chosen stats MUST survive
  each update.
- **FR-005** _(amended after live verification, 2026-10-09; see verification.md, US1 finding 1)_:
  The stats view MUST list every undrafted player **in the board's top 400** (the server's
  existing pool cap; the 2026 draft has 168 picks). A mock room lists its own full undrafted pool.
  A player with no stats in the shown season MUST appear with a worded reason, sort after players
  with a value, and show no zero for a stat he had no chance to record. _Was_: "every undrafted
  player on the board". The first build read the room's survival list, which only holds players
  the simulation surfaced near the member's picks, so it was not the undrafted pool at all.
- **FR-006** _(amended after planning, 2026-10-08; see plan.md)_: Fantasy figures (fantasy
  points per game, league ranks, value over replacement) MUST be computed under one stated
  scoring, and the view MUST name it. If that scoring differs from the drafting league's current
  scoring, the view MUST say so. _Was_: "default to the drafting league's current scoring applied
  to last season's games". That's not what spec 022's leaderboard does: it scores with the
  fallback season's league. For "Ball Knowers", the 2025 and 2026 scoring was measured identical,
  so the numbers are the same either way.
- **FR-007**: The member MUST be able to choose which stats are shown, from the leaderboard's
  Basic, Shooting, Advanced and Fantasy groups, and in what order. Changes MUST apply immediately
  without disrupting live updates.
- **FR-008**: The member's stat choice and order MUST be remembered on the device, MUST be shared
  by every basketball draft room (live and mock), and MUST be resettable to the default.
- **FR-009**: The default stat set MUST be: games played, minutes, fantasy points per game,
  points, rebounds, assists, steals, blocks, three-pointers made, turnovers, field goal % and
  free throw %. This set is chosen for a points league like "Ball Knowers" and is labelled a
  starting point, not a recommendation.
- **FR-010**: The member MUST be able to sort by any shown stat. Direction, tie-breaking and the
  placement of missing values MUST match the spec 022 leaderboard.
- **FR-011**: When the member's seat is known, the stats view MUST offer a filter to players
  likely to be available at the member's next pick, using the room's existing survival
  projection. The threshold and pick MUST be printed. When the seat is unknown, or in a mock
  room, the filter MUST be unavailable, with a stated reason.
- **FR-012**: The position filter and the "fills an open slot" tag the sheet already has MUST
  work the same way in the stats view.
- **FR-013**: Each player name MUST open that player's spec 022 page for the drafting league. It
  MUST NOT end the member's live connection to the draft or lose the room's state.
- **FR-014**: The stats view MUST keep the window (season, last 10, last 5) and mode (per game,
  totals, per 36) controls of the leaderboard. The defaults are season and per game.
- **FR-015**: If stats cannot be loaded, the stats view MUST say why in words. The rest of the
  room MUST keep working.
- **FR-016**: The stats view MUST be usable at 375 pixels wide, with a pinned name column,
  sideways scrolling inside the table only, and no sideways page scroll.
- **FR-017**: Football draft rooms MUST NOT offer the stats view, and MUST otherwise be unchanged.
- **FR-018** _(US3)_: Basketball mock draft rooms MUST offer the same stats view and stat choice,
  without the next-pick filter.
- **FR-019**: The stat picker MUST be a modal over the draft room. Opening it MUST NOT pause or
  drop live pick updates.

### Key Entities

- **Stat choice**: one member's ordered list of chosen stats, by the leaderboard's stat ids. It is
  stored on the device and shared across all basketball draft rooms. It can be reset to the
  default.
- **Draft-room stat row**: one undrafted player, with his leaderboard numbers for the shown season
  and window, joined to the facts the room already holds about him (position, ADP, survival to
  the next pick, open-slot tag). It also carries a worded reason wherever a number is absent.
- **Shown season**: the season the numbers come from, plus whether it is a fallback from the
  draft's own season, and the scoring used for the fantasy figures.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: During a live basketball draft, a member can compare any two available players on
  their chosen stats without leaving the draft room, in under 10 seconds from opening the sheet.
- **SC-002**: For a sample of 10 players, every value in the stats view equals the spec 022
  leaderboard's value for the same season, window and mode: 10 of 10, with no tolerance.
- **SC-003**: After a pick lands, the stats view reflects it no later than the board does, in 20
  of 20 observed picks.
- **SC-004**: A stat choice made in one draft room appears unchanged in the next basketball room
  opened on the same device, in both directions between live and mock rooms.
- **SC-005**: The stats view never shows an empty cell, "NaN" or a zero for a stat a player had
  no chance to record. Checked against every rookie in the 2026 pool.
- **SC-006**: Opening the stats view doesn't slow the room's existing pick announcements or
  re-projection. Measured before and after on the same replayed draft, the time from a pick
  landing to the board updating changes by no more than 10%.
- **SC-007**: At 375 pixels wide, the page never scrolls sideways with the stats view open, and
  the name column stays visible while the stat columns scroll.

## Assumptions

- **Timing (clarified 2026-10-08).** The target is the 2026-10-10 draft. The build order is
  US1, then US2, then US3. Only work that has passed live verification by draft day merges. If US3
  isn't verified in time, it ships later. Spec 022 merges and deploys with this feature, or ahead
  of it, under the same rule.
- **Depends on spec 022.** This feature reads the spec 022 leaderboard and links to its player
  page. Spec 022 is built but unmerged, so this branch starts from `022-player-stat-analysis`,
  and cannot ship before 022 does.
- **Basketball only.** Spec 022 has no football stats. Basketball is the project's real target,
  and this season's football drafts are over.
- **"Upcoming players"** means the players still on the board, with an optional narrowing to
  those likely to be there at the member's next pick. It does not mean a projected stat line for
  the coming season. The app has no basketball projection source (spec 016), and this feature
  doesn't invent one.
- **Remembered per device, not per account.** Draft rooms already keep the sound and pick-card
  preferences per device, and this follows them. Following the member across devices would need
  account-level storage. That is out of scope here.
- **The Draft value column group is excluded** from the stat picker. Its draft pick and value vs.
  draft cost columns describe a completed draft, which in a preseason draft room would mean last
  year's draft. The room already shows ADP.
- **Survival threshold**: the "likely there at my next pick" filter uses one threshold, set by
  hand and labelled as such in the room. The plan stage picks its value.
- **No new stats.** Every stat offered is one the leaderboard already defines and verifies.

## Not being built, and why

- **A personal weighted ranking** built from the chosen stats. It is spec'd as US4 (P3) and
  deferred by the user's choice (clarification 2026-10-08).
- **Projections for the coming season**: there is no source (spec 016), and inventing one would
  make the room look more certain than it is.
- **Football stats**: there is no football box-score analysis to reuse.
- **Saving the stat choice to the member's account**: see the assumptions above.
