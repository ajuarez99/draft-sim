# Feature Specification: Compare two players

**Feature Branch**: `049-player-compare`

**Created**: 2026-10-25

**Status**: Built and live-verified locally (2026-10-26)

**Renumbered 2026-10-26**: specified, built and reviewed as spec 048. While it was in progress,
`048-weekly-high-score-ledger` merged to main (PR #48) under the same number, so this spec became 049 before
its first commit. The worktree folder is still `.claude/worktrees/048-player-compare`.

**Input**: User description: "whats next" → chosen 2026-10-25 from the open roadmap items: spec 042's deferred
User Story 4 ("Compare two players side by side"), which is also `claude/competitor-gap-roadmap.md` item 3.3
(start/sit and player comparison), in its no-verdict form.

## Context: where this comes from, and what changed since 042 wrote it

Spec 042 (merged, main `ec72031`) wrote this story and deferred it (`specs/042-fantasypros-design-features/spec.md`
US4, FR-011, FR-012). It is FantasyPros' most-promoted free tool ("Who Should I Start?"). Ball Knowers can't
honestly issue the verdict, because the accuracy of the projections it would rest on is still being measured
(spec 036). So the comparison shows the numbers and marks the better value per row. It never says who to start.

Three things in 042's wording don't match the code as it stands, so this spec corrects them in place rather than
copying them:

- **The windows.** 042 says "last 7/14/30 days". The player page's windows are **season, last 10 games and last 5
  games** (`PlayerWindowKind = 'SEASON' | 'LAST_10' | 'LAST_5'`, `web/src/api.ts:3214`). The comparison uses the
  page's windows, because the independent test is "both columns show the same stats each player's own page shows".
- **Football.** 042's FR-014 asks for both sports or a stated gap. The player page itself refuses football
  (`PlayerStatsReason` `NOT_BASKETBALL`, `api.ts:3213`), so there are no football figures to compare. This spec is
  basketball-only and says so on the page.
- **"Games this period" is not a value to mark.** Measured in spec 019/030 and recorded since: Sleeper credits a
  starter **one game per week** in these NBA leagues (540/540 starter-weeks). ~~A player with four games this
  week is not worth more this week than one with two.~~ So the comparison shows the count as context and does
  **not** mark the larger one as better. Marking it would be the "correct number, wrong thing to display" bug
  class (`AGENTS.md` recurring bug #5).
  > **Amended after review (2026-10-26, plan-review F2):** the struck sentence was wrong. These leagues use
  > lock-ins (spec 044), where the credited game beat the week's average game by ~14–19% (quoted from 044), so
  > more games is worth *something* (more chances at a good one), just not proportionally. Zero games is worth
  > zero. The row stays unmarked because nothing has measured how much an extra game is worth.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Compare two players side by side (Priority: P1)

A manager in a basketball league picks two players and sees them in two columns: who owns each one in this league
(marked if it's the manager's own), fantasy points per game under this league's scoring for each window (season,
last 10, last 5), season fantasy total, the per-game box-score line for the chosen window, shooting, minutes, and
games played. In each row, the better value is marked. No row and no headline says "Start X".

**Why this priority**: This is the whole feature. Everything else is a way into it.

**Independent Test**: Open the comparison for two NBA players with games this season. Each column's numbers equal
the same numbers on that player's own page, for the same league and season, and each comparable row marks exactly
the higher (or, for turnovers, the lower) value.

**Acceptance Scenarios**:

1. **Given** two basketball players in the same league, both with games this season, **When** the comparison
   opens, **Then** both columns show owner, fantasy points per game per window, season total, the per-game line,
   shooting and minutes, and each comparable row marks the better value.
2. **Given** any comparison, **When** it renders, **Then** no text on the page recommends one player over the
   other ("Start", "Sit", "Better pick", or an overall winner tally).
3. **Given** the turnover-rate row, **When** compared, **Then** the lower value is the one marked. Turnovers per
   game are shown unmarked. *(Amended after review 2026-10-26, F5, Allan's decision.)*
4. **Given** a row where the two values are equal, **When** compared, **Then** neither is marked.
5. **Given** one of the two players is the manager's own, **When** compared, **Then** that column's owner is
   marked as theirs the same way the player page marks it.

---

### User Story 2 - Small samples are shown, not judged (Priority: P1)

A player who has played only a few games, or none in a window, still gets a column, but his thin numbers are
labelled with their game count and are not marked better or worse.

**Why this priority**: Early in the season (this spec is written five days after NBA tip-off) almost every
"last 10" window is a small sample. Marking a 2-game average as "better" than a 10-game one is the exact
over-confidence this project avoids.

**Independent Test**: Compare a player with fewer games than a window's length against one with a full window.
The short window's cells show the actual game count and carry no better/worse mark; the full player's cells in
that row are also unmarked.

**Acceptance Scenarios**:

1. **Given** a player whose window the player page already labels as a small sample, **When** compared, **Then**
   that window's cells show his game count and the row is not marked for either player.
2. **Given** a player with no games in a window, **When** compared, **Then** his cell says "no games" and the row
   is not marked.
3. **Given** a player with no games at all this season, **When** compared, **Then** his column still shows his
   name, team and owner, and says he has no games this season.

---

### User Story 3 - Start a comparison from where you already are (Priority: P2)

From a player's page, the manager can start a comparison with that player already in the first column and choose
the second by name. The comparison has its own address, so it can be reloaded, bookmarked or shared with a
leaguemate, and either player can be swapped without starting over.

**Why this priority**: Without an entry point the page is unreachable in practice. It is P2 only because the
comparison itself (US1) has to exist first.

**Independent Test**: From a player page, start a comparison, pick a second player by typing part of his name,
copy the address, open it in a new tab: the same two players appear in the same columns.

**Acceptance Scenarios**:

1. **Given** a basketball player's page, **When** the manager chooses to compare, **Then** a comparison opens
   with that player in the first column and a way to choose the second.
2. **Given** the player chooser, **When** the manager types part of a name, **Then** matching players from this
   league's sport appear, each with team and owner, and picking one fills the column.
3. **Given** an open comparison, **When** the manager swaps one player, **Then** the other column is unchanged
   and the address updates.
4. **Given** a comparison address opened by another member of the same league, **When** it loads, **Then** it
   shows the same two players; ownership marking is from that viewer's point of view.

---

### Edge Cases

- **The same player twice**: the chooser does not offer the player already in the other column; an address that
  names the same player twice shows one column and asks for a second player.
- **A football league**: the comparison page says comparison is available for basketball leagues only, and the
  player-page entry point is absent (the football player page has no figures today).
- **An unknown or never-stored player id in the address**: that column says the player couldn't be found; the
  other column still renders.
- **A season fallback**: if the player page would show an earlier season for this league (its existing
  `requestedSeason` disclosure), both columns use the same season and the page states which season it is. The two
  columns never show different seasons.
- **One player's figures fail to load**: the other column still renders; the failed column says it couldn't load.
  No row is marked while one side is missing.
- **A player who changed teams this season**: the column shows his current team and the same "teams this season"
  disclosure the player page shows.
- **Ownership unavailable or stale**: the column shows the same owner wording and "as of" the player page shows.
- **Games this scoring period**: shown per player as context (played so far and left this week, from the stored
  schedule), never marked, with the note FR-008 specifies. If the schedule isn't stored, the cell says so.
- **Signed-out user or a non-member**: the same access rule as the player page; nothing new is exposed.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST let a league member compare exactly two basketball players from the same league,
  shown in two columns.
- **FR-002**: Every figure in a column MUST equal the same figure on that player's own page for the same league
  and season (same windows, same league scoring, same rounding). The comparison introduces no new statistic.
- **FR-003**: Each column MUST show the player's name, positions, current team, owner in this league (marking the
  viewer's own players as the player page does), fantasy points per game for each window, the season box-score
  fantasy total labelled "box-score total, not the league's credited points", games and minutes per game per
  window, the per-game box-score line, turnover rate, and shooting percentages with made-attempted beneath each.
- **FR-004**: Only these rows are marked: fantasy points per game (each window), minutes per game (each window),
  points, rebounds, assists, steals, blocks and threes made per game (higher is better), and turnover rate (lower
  is better). Every other row MUST NOT be marked: team, positions, owner, games played (any window), the season
  box-score total, FG%/3P%/FT%, turnovers per game, personal fouls, and games this scoring period.
  *(Amended after review 2026-10-26, F4/F5/F6, Allan's decisions: percentages would mark 1-of-1 over 38-of-100;
  per-game turnovers mostly reward a smaller role; games and the season total follow schedule and health, and the
  total isn't what the league credited.)*
- **FR-005**: A row MUST NOT be marked when the values are equal, when either value is missing, or when either
  player's window is a small sample by the player page's existing rule. The game count MUST be shown in those
  cells. *(Amended during planning 2026-10-25: that existing rule is minutes-only — under 100 minutes in the
  window — so a last-10 / last-5 row is also left unmarked unless both players have the full number of games in
  it. US2's independent test already assumed this. See plan decision 2, research R2.)*
  *(Amended after review 2026-10-26, F7/F10: the last-10 / last-5 guard uses the server's own window
  qualification — withheld when the window is short of its games or stale (last game more than the recency
  rule's days before the newest) — not a client copy of it. "Equal" means equal as displayed.)*
- **FR-006**: The page MUST NOT state a verdict: no "start/sit", no recommended player, no count of rows won, and
  no projected points (projections stay off until spec 036 reports their accuracy, per 042 FR-012).
- **FR-007**: Both columns MUST show the same season. When that season is a fallback from the league's own, the
  page MUST say so.
- **FR-008**: Games this scoring period MUST be shown as played and remaining, unmarked, with a note that the
  league credits one game per player per week, so more games means more chances at a good one, not more games'
  points. Zero games MUST read "No game this week". The schedule's as-of time MUST be shown beside it.
  *(Amended after review 2026-10-26, F2/F13.)*
- **FR-009**: The comparison MUST have its own address carrying both players, so reloading or sharing it shows
  the same comparison.
- **FR-010**: A basketball player's page MUST offer a way to start a comparison with that player in the first
  column.
- **FR-011**: The second player MUST be chosen by searching names among this league's basketball players with a
  stored player record; each result shows team and owner, and the player already in the other column is
  excluded. *(Amended during planning 2026-10-25: the chooser lists players with at least one game this season;
  a player with none can still be compared by address. Research R4.)*
- **FR-012**: One column failing to load (unknown id, error) MUST NOT prevent the other from rendering, and MUST
  leave every row unmarked.
- **FR-013**: In a football league the page MUST say comparison is available for basketball leagues only, and the
  entry point MUST NOT appear on football player pages. Both MUST read the one existing per-destination sport
  rule, not a new check. *(Amended after review 2026-10-26, F8/F11.)*
- **FR-014**: Access MUST follow the player page's existing rule (league members, signed in); a comparison address
  MUST NOT reveal anything a non-member couldn't already see.
- **FR-015**: The page MUST be usable at phone width (375 px) without horizontal page scroll, keeping the two
  columns side by side.

### Key Entities

- **Comparison**: a league, a season, and two players (first, second). It has no stored state of its own; it is
  fully described by its address.
- **Player column**: the subset of one player's existing page figures that the comparison shows, plus the
  viewer-relative owner.
- **Row mark**: for one comparable row, which column (if either) has the better value, under the direction and
  small-sample rules above.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For at least 10 player pairs in the real NBA league, every figure in both columns equals the same
  figure in each player's own page response, formatted with the player page's own formatter (0 mismatches).
  Turnovers and threes made per game are in that response but not displayed on the player page.
  *(Amended after review 2026-10-26, F10.)*
- **SC-002**: Across those pairs, every marked row marks the better value under FR-004's direction, and no row is
  marked where FR-005 forbids it (0 wrong marks).
- **SC-003**: A manager can go from a player's page to a complete comparison with a second player in under 15
  seconds, in 3 actions or fewer after the page loads (open compare, type a name, pick).
- **SC-004**: A shared comparison address opened in a fresh tab shows the same two players 100% of the time.
- **SC-005**: The comparison is fully visible at 375 px width with no horizontal page scroll.
- **SC-006**: No page text contains a start/sit verdict or an overall winner (checked by a test over the rendered
  page, with the whole-word patterns and positive controls in `contracts/ui.md`).
- **SC-007**: The two columns load in under 2 seconds for two warm players on the deployed site (measured, not
  assumed). This excludes the chooser's player list, which loads only when the chooser opens.
  *(SC-006/007 amended after review 2026-10-26, F1/F15.)*

## Assumptions

- Basketball only, because the player page it mirrors is basketball-only today. A football comparison waits on
  football per-player figures, which don't exist in the app.
- The figures come from what the player page already computes; this spec adds no new statistic, no new data
  source, no new ingest, and (expected) no schema migration. The planning stage checks this against the code.
- "Small sample" is the player page's existing per-window rule, not a new threshold.
- The comparison is within one league: owner and scoring are that league's. Cross-league comparison is not built.
- Two players, not three or more. FantasyPros' tool is two; more columns don't fit a phone.
- No verdict, no projected points, no "win count". Revisit only after spec 036 measures projection accuracy, and
  as a new spec.
- Ownership wording, "as of" labels and the "teams this season" disclosure are reused from the player page as-is.

## Not being built, and why

- **A start/sit recommendation.** No measured basis for one yet (spec 036). A confident verdict on thin data is
  the thing this project exists to avoid.
- **Football.** No per-player football figures in the app; building them is its own feature.
- **Three or more players, or cross-league comparison.** No league needs it, and it breaks the phone layout.
- **Multi-league home summary (042 US5 / roadmap 4.2).** Separate spec; lower priority because most users are in
  one or two leagues.
