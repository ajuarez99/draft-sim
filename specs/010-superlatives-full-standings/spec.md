# Feature Specification: Superlatives full standings

**Feature Branch**: `010-superlatives-full-standings`
**Created**: 2026-09-29
**Status**: Planned. Nothing built.
**Input**: Allan: "on the topic of superlatives i want to click on modal and see where everybody else ranked not just the winner"
**Design source**: `claude/superlatives-full-standings.md`. That doc is the full design. This spec restates it as user stories and requirements, and records where review changed it.

## User Scenarios & Testing

### User Story 1: Where every team finished for a team award (Priority: P1)

A league member opens Superlatives and clicks any award card that has a winner. A modal
lists **every team** in the league, ranked for that award:

- each team's figure, printed exactly;
- one line of context, such as "week 7" or "vs Team X, week 3".

Teams the award couldn't measure are listed last, with the reason why. They aren't shown
as zero.

**Why this priority**: This is the request. It covers 12 of the 13 awards.

**Independent Test**: Open the 2025 season of "(Foot) Ball Knowers", click Highest week,
and check the following:

- The modal lists 12 teams, highest to lowest.
- Rank 1 is the card's winner.
- One team's figure matches its best week on the Weekly report.

**Acceptance Scenarios**:

1. **Given** a season with scored weeks, **When** the user clicks Lowest week, **Then**
   rank 1 is the lowest single-week score, and the list goes up from there.
2. **Given** two teams tied for the lead, **When** the modal opens, **Then** both show
   rank 1 and the next team shows rank 3.
3. **Given** a team with no wins, **When** Biggest blowout is opened, **Then** that team
   is listed last as "no wins yet", not as a 0-point margin.
4. **Given** the season is early (the card shows "early — this is mostly noise"),
   **When** the modal opens, **Then** the modal shows the same warning.
5. **Given** the user expands a card's "Games" list, **When** they click it, **Then** the
   modal does not open.
6. **Given** an award is unavailable or has no winner, **Then** its card is not clickable.

### User Story 2: The most-added players (Priority: P2)

Clicking the Jabari Smith Jr. card opens the same modal, but it ranks **players** by adds.
It shows the top 10, each with their add count and how many different teams added them.

**Why this priority**: This is the one award whose "everybody" is players rather than
teams. It's useful, but it's separate from the rest.

**Independent Test**: On a season with transactions, open Jabari Smith Jr. Check that
rank 1 matches the card's player(s) and that the subtitle says the award ranks players.

### Edge Cases

- **A roster with no games at all.** It's listed with `hasValue=false` for margin kinds
  **and** close-game count kinds (`"no games yet"`, R12). It's a real 0 for conduct games,
  waiver points and absence cost.
- **JOEL_EMBIID and unclassified weeks.** A roster with no counted absence cost is 0. But
  if it has weeks whose absences couldn't be classified, its row says how many (FR-010),
  exactly as the card already says for the winner.
- **CLOSEST_GAME ranks each team's closest game, win or lose.** The loser of the closest
  game therefore shares rank 1 with the winner, even though only the winner is in
  `holders`. See the amendment in `plan.md`.
- **Fallback season.** The resolver may walk back to an earlier season
  (`requestedSeason` is non-null). The modal describes the season the cards describe.
  T030 checks this live on "Ball Knowers" (NBA) 2026, which has been created but not yet
  played, so its Superlatives walks back to 2025. If 2026 has scored weeks by then, it's
  recorded as not exercised.

## Requirements

- **FR-001**: Every available `Superlative` whose kind is headed by teams carries
  `standings`. That's one row per roster in the league-season, each roster exactly once.
- **FR-002**: Ranks are competition ranks, so ties share a rank (1, 1, 3). A row with
  `hasValue=false` has `rank = null` and sorts last.
- **FR-003**: The rank-1 rows equal `holders`, for every kind except CLOSEST_GAME, where
  `holders` ⊆ rank-1 rows.
- **FR-004**: Each kind's figure comes from **the same row population that picks its
  winner**.
  - For the four record kinds (HIGHEST_WEEK, LOWEST_WEEK, BIGGEST_BLOWOUT, CLOSEST_GAME),
    the loaded `leagueBreakdowns` and `games` are that population. This currently holds
    by construction; see plan amendment 1.
  - It is guarded on real data by the rank-1 = holders assertion (FR-003, T017).
  - *(Amended by `/speckit-analyze` F1: the first version forbade those lists, based on
    an unrun and incorrect claim.)*
- **FR-005**: Each kind's direction is as follows. It is tested per kind.
  - Low to high: LOWEST_WEEK, CLOSEST_GAME, UNLUCKIEST.
  - High to low: every other kind.
- **FR-006**: JABARI_SMITH_JR carries `playerStandings`.
  - It holds the top 10 players by adds, counting only players with at least
    `MIN_ADDS_TO_NAME` (2) adds, the same floor the card uses to name a winner.
  - It **includes every player tied with the 10th**, so it can run past 10 rather than
    cutting a tie silently.
  - Adds are counted by exactly the same rule as the card: WAIVER/FREE_AGENT only, the
    week window, and team defenses excluded.
  - Its `standings` is `[]`.
- **FR-007**: `web/src/api.ts` mirrors every new field in the same change.
- **FR-008**: The modal prints each exact value through **one per-kind formatter**,
  `standingFigure` (plan amendment 10):
  - `formatCloseGameCount` for close-game kinds;
  - a signed "wins vs expected" figure for luck;
  - `formatValue` otherwise.

  It carries the card's `early` warning and its `coverage` line. It is opened by a real
  "See all" button (keyboard and screen reader) or a mouse click on the card
  (amendment 12).
- ~~**FR-009**: highlight the signed-in user's row.~~ **Removed after T000 review (R6).**
  The two usernames it compared are different Sleeper fields, so it would never have
  fired. It wasn't requested.
- **FR-011** (R4): WAIVER_WIRE_WARRIOR and JOEL_EMBIID show their empty state when the best
  value in their per-roster map is ≤ 0. There's no winner who is merely level with the
  teams that did nothing.
- **FR-012** (R2): the roster universe is every `roster_season` row for the league-season,
  **including an orphaned roster** with no manager, which is shown as `"Roster N"`.
- **FR-010**: A figure that may be an undercount says so on its own row. For JOEL_EMBIID,
  every row's note carries that roster's count of weeks whose absences couldn't be
  classified (plan amendment 4). A caveat that today appears only for the winner
  applies to every row.

## Success Criteria

- **SC-001**: For both leagues' 2025 seasons, every clickable card opens a modal that
  lists every roster once, and rank 1 matches the card.
- **SC-002**: One figure per checked award matches a figure on another page, checked by
  hand. Highest week is checked against the Weekly report, and Unluckiest against
  Expected wins.
- **SC-003**: The modal is usable at 375px wide, with no horizontal scroll.

## Assumptions

- The per-roster maps the builders already compute are correct. This feature exposes
  them and doesn't re-derive them.
- There's no new endpoint and no migration. The fields are added to the existing
  `GET /api/leagues/{id}/superlatives` payload.
