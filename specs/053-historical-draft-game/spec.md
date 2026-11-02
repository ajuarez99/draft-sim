# Feature Specification: Historical Draft Game ("Draft a random year")

**Feature Branch**: `053-historical-draft-game`

**Created**: 2026-11-02

**Status**: Draft

**Input**: User description: "/speckit-specify with the doc". The doc is `claude/historical-draft-game.md`, the design and data-availability pass from 2026-11-02, including Allan's decisions from that conversation.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Draft a random past season and see how you would have done (Priority: P1)

A fan opens the game and gets a randomly chosen NBA season from 1970-71 to the most
recent complete season. They draft a team against computer opponents in a normal
snake draft. Each player shows the stats that player put up **the season before** —
what a real drafter would have known going in. The drafted season's **stats** stay
hidden until the draft is over. The season's name is shown up front. *(Amended after
review 2026-11-02, F21: this said "the season stays hidden", which contradicted
scenario 1.)* Then the real season plays out week by week as best ball:
for every starter, the single best game he played that week counts, and the strongest
legal lineup is chosen automatically. The fan sees their weekly head-to-head results,
final standings, and where they finished.

**Why this priority**: This is the whole game. Without it nothing else is worth building.

**Independent Test**: Start a game, finish a draft, and check that the season replays
to a final standings table. Pick one week and one team and work out by hand that the
team's score equals the best single game of each chosen starter.

**Acceptance Scenarios**:

1. **Given** a signed-in user, **When** they start a new game, **Then** they are told which season they're drafting (e.g. "1995-96"), their draft slot, and which stats count that season, and the draft board shows every draftable player with prior-season stats only.
2. **Given** the draft is in progress, **When** it is a computer opponent's turn, **Then** the opponent picks without the user doing anything, as in the existing mock draft room.
3. **Given** the draft is complete, **When** results are shown, **Then** every team has a week-by-week score, a win-loss record and a final standing, and the user's finishing place is highlighted.
4. **Given** a week where a starter played three games, **When** that week is scored, **Then** only his highest-scoring game counts for him.
5. **Given** the drafted season is 2019-20, **When** it is replayed, **Then** it ends with the week of the March 11, 2020 suspension and no later games count.

---

### User Story 2 - See how your draft compares (Priority: P2)

After the season replays, the user sees how their draft compared with what was
possible. That means their result against the computer teams, the best-scoring team
anyone could have drafted from their slot with hindsight, the biggest steal they
passed on, and their biggest bust.

**Why this priority**: This is the "how you would've done" payoff that makes the game
worth replaying and sharing. The season replay has to work first.

**Independent Test**: Finish one game. Check that the hindsight comparison,
best-available-steal and bust are each worked out from the real season data and can
be checked by hand.

**Acceptance Scenarios**:

1. **Given** a finished game, **When** results are shown, **Then** the user sees their total points next to a hindsight-optimal draft from the same slot, and the gap between them.
2. **Given** a finished game, **When** results are shown, **Then** the user's "biggest bust" (the drafted player who finished furthest below where he was taken) and the "steal you passed on" (an undrafted-by-them player still available at one of their picks who outscored the player they took) are named with the numbers behind them.

---

### User Story 3 - Know what each season's numbers can and can't tell you (Priority: P2)

Older seasons didn't track every stat. Steals and blocks were only partly recorded
before the mid-1980s, turnovers came later, and the 3-point line didn't exist before
1979-80. The game only scores stats that were recorded for essentially the whole
season. It says plainly which ones count. When a season has no real draft-position
(ADP) data, it says the computer opponents are drafting from last season's rankings
instead.

**Why this priority**: This is the project's core rule: don't look more certain than
the data allows. It doesn't block a playable game, but if it's missing the early
seasons quietly penalize some players.

**Independent Test**: Start a 1975-76 game. Confirm steals and blocks are not scored
and that the screen says so. Confirm the opponents are labeled as drafting from last
season's points, not ADP.

**Acceptance Scenarios**:

1. **Given** a season where a stat wasn't tracked by the league, or is missing from more than 10% of played box-score rows, **When** the game is set up, **Then** that stat is not scored for that season, on the player cards or in the replay, and the draft screen lists the stats that do count with their coverage. *(Amended after review 2026-11-02 to match FR-007.)*
2. **Given** a season with real ADP, **When** the board is shown, **Then** it is labeled as ADP. **Given** a season without real ADP, **Then** the ordering is labeled "ranked by last season's points" and the word ADP is not used for it.

---

### User Story 4 - Revisit past games (Priority: P3)

A user can see the games they've already played: the season, their finish, and the
full results again.

**Why this priority**: This is nice to have. A single game is complete without it.

**Independent Test**: Play two games, leave, come back and open both from a list.

**Acceptance Scenarios**:

1. **Given** a user who has finished games, **When** they open the game's page, **Then** they see a list of past games with season and finish, and can open any of them to see its results unchanged.

---

### Edge Cases

- **Players with the same name**: the historical data identifies players by name only. Two different players with the same name in the same season (or father and son across seasons) must never be merged into one card or one season line.
- **Rookies or players with no prior NBA season**: they have no prior-season stats. They are still draftable, the card says "no NBA stats last season", and computer opponents rank them using the fallback rule (see Assumptions).
- **Players who changed teams mid-season**: their games for every team in the drafted season count toward their weekly scores.
- **Short seasons** (1998-99, 2011-12 lockouts, 2019-20 suspension): fewer weeks. The game still completes and standings are still produced.
- **A drafted player who never plays**: zero for every week. The lineup is filled from the bench where possible, and a slot left empty scores zero.
- **The most recent seasons**, where the historical source and the app's existing Sleeper data overlap: one source is used per season, never both mixed.
- **A season too incomplete to score fairly** (before 1970-71): never offered.
- **The user leaves mid-draft**: the in-progress game can be resumed or abandoned. It is never counted as finished.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST offer NBA regular seasons from 1970-71 to the most recent complete regular season, and pick one at random when a game starts.
- **FR-002**: The draft MUST be a plain snake draft (no reversal round) in the app's standard NBA shape: 12 teams × 14 rounds, PG SG G SF PF F C UTIL UTIL + 5 bench. That is the shape the app's basketball lineup rules are proven against. Scoring MUST use the Legacy Ball Knowers league's values: pts 0.5, reb 1, ast 1, stl 2, blk 2, to −1, 3PM 0.5, dd 1, td 2, 40/50-point bonuses 2/2, 20-reb and 15-ast bonuses 1/1, tf/ff −2. The start screen MUST say where the settings come from. *(Amended after review 2026-11-02, F13 + O1: it said "Sleeper's defaults, read not guessed", but Sleeper's API exposes no product defaults. Allan chose the Legacy scoring.)*
- **FR-003**: Player cards and the draft board MUST show only information available before the drafted season: the prior season's stats (games, per-game and total), plus team, age and position where known. Nothing from the drafted season may appear before the draft is complete.
- **FR-004**: Players' real names MUST be shown ("named mode"). An anonymised "blind mode" is out of scope for this feature.
- **FR-005**: Computer opponents MUST draft using the existing mock-draft opponent behavior, ordered by real historical ADP where it exists for that season and by the prior-season fallback ranking otherwise.
- **FR-006**: After the draft, the system MUST replay the drafted season as weekly head-to-head best ball. Weeks run Monday to Sunday from opening night. Each rostered player's score for a week is his single highest-scoring game that week. The best legal lineup is chosen automatically from those scores.
- **FR-007**: A stat MUST count in a season only if the league tracked it that season (the season-total data has it) **and** it is present in at least 90% of that season's box-score rows where the player played. A counted stat missing from an individual box score scores 0, and the draft screen shows the stat's coverage (e.g. "assists: 94.6% of box scores"). Stats that don't count MUST be excluded from scoring, cards and results alike. The 90% is a hand-set threshold and MUST be labeled as one. *(Amended after review 2026-11-02, O2 + F7: it was 95% over all rows. Allan chose to count stats the league tracked with gaps as 0 and disclosed, so the early 1970s keep rebounds and assists. The denominator is now the played rows.)*
- **FR-008**: The draft screen MUST list the stats that count for the drafted season. When any league-default stat is excluded, it MUST say so and why.
- **FR-009**: The board ordering MUST be labeled by source: "ADP" only when real historical ADP was used, otherwise "ranked by last season's points". ADP generated by the simulator MUST NOT be presented as ADP.
- **FR-010**: The 2019-20 season MUST end with the week of the 2020-03-11 suspension. Later games do not count.
- **FR-011**: Results MUST show each team's weekly scores, win-loss record and final standing, with the user's team and finish highlighted.
- **FR-012**: Results MUST show the user's total points against a hindsight-optimal draft from the same slot, their biggest bust, and the best steal they passed on, each with the numbers behind it.
- **FR-013**: A game's draft and results MUST be saved to the user so they can be reopened unchanged later (User Story 4). Reopening a game MUST NOT re-run the replay with different data.
- **FR-014**: Historical player data MUST never merge two different people into one player, even if they share a name.
- **FR-015**: Each season MUST use exactly one data source for its box scores. Seasons covered by the app's existing Sleeper data use it. Earlier seasons use the historical public-domain dataset.
- **FR-016**: Data from sources whose license forbids this use (e.g. bulk copies of Basketball-Reference) MUST NOT be used.

### Key Entities

- **Historical season**: one NBA regular season. Holds its label (e.g. 1995-96), its list of weeks, the stats that count under the era rule, and whether real ADP exists.
- **Historical player**: one real person, distinguished from anyone else with the same name. Holds the name. *(Amended after review 2026-11-02, F5: positions vary by season in the source data, so they live on the prior-season line, not on the person.)*
- **Player game**: one player's box score in one regular-season game, with date, team and the recorded stats. Unrecorded stats are kept as unrecorded, not as zero.
- **Prior-season line**: a player's totals, per-game numbers, team, age and positions from the season before the drafted one, as shown on the card.
- **Draft ranking**: the order opponents draft from for a season, tagged with its source (real ADP, or prior-season fallback).
- **Game**: one user's play of one season. Holds the season, draft slot, all picks, weekly results, standings and the comparison figures. Belongs to that user.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user can go from "start game" to a finished season's results in under 10 minutes for a default-size draft. Computer picks and the replay add no more than a few seconds of waiting in total.
- **SC-002**: For at least one season in each data era (1970s, 1980s–2010s, Sleeper-era), the number of regular-season games loaded equals the real schedule's game count. The one exception is a documented known gap.
- **SC-003**: For at least one week in at least three different seasons, a team's weekly score worked out by hand from the box scores (best single game per starter, best legal lineup) matches the game's number exactly.
- **SC-004**: In 100% of offered seasons, no stat that fails the era rule appears in scoring, cards, or results.
- **SC-005**: For one season where the historical dataset and Sleeper overlap, each player's season fantasy points from the two sources agree within rounding for at least 95% of players. Any disagreements are listed rather than hidden.
- **SC-006**: No board shows the word "ADP" for a season that has no real ADP.
- **SC-007**: Zero cases of two different players merged into one, checked against a list of known same-name pairs.

## Assumptions

- The game is for signed-in users of the existing site. No league is needed to play, because it uses Sleeper's default settings rather than the user's league.
- Real ADP exists for 2019-20 onward (Sleeper's season ADP, measured present 2019–2025). Before relying on it, the plan must confirm this ADP reflects preseason drafting and isn't contaminated by in-season drafts. If it is contaminated, those seasons use the fallback.
  - *Amended at planning, 2026-11-02:* the plan could not confirm it. The evidence (research R4) points to hindsight contamination: Sleeper ranked Dončić 4th against Yahoo's 17.8 for 2019-20, and in 2 of 7 seasons Sleeper's ADP correlates better with the drafted season than with the prior one. Yahoo's public history answers only for 2019-20. **So every season in v1 uses the fallback ranking**, as this assumption already allowed.
- Fallback ranking: prior-season fantasy points per game times games played, under that season's era scoring. Rookies and players with no prior season rank after everyone with a prior season, ordered by draft position in that year's NBA draft if available, otherwise last.
  - *Amended after review 2026-11-02, F6, Allan's decision:* season totals made every star who was hurt the previous year a late-round gift (Jordan drafting 1995-96 had 17 prior games, Kobe drafting 2014-15 had 6). The ranking is now **fantasy points per game × a standard season length, using the better of the last two seasons, with a minimum-games floor**. Both numbers are hand-set and labeled. It counts only counting stats, no bonuses (F12). Rookies and ABA arrivals in 1976-77 still have no NBA line and rank last, because no draft-order source with a compatible licence exists. The card says so, and that limitation is accepted.
- The historical source is the public-domain (CC0) Kaggle dataset "NBA Player Box Score Data" (every regular-season game 1946-47 to early April 2023, measured 2026-11-02). Sleeper covers 2017-18 onward. Which source owns which seasons is settled in the plan under FR-015. *Amended at build 2026-11-02:* window widened to the last 3 seasons (config `rank-lookback-seasons`), because 2 seasons left Jordan 1995-96 (17 G, then retired) unranked — the exact case F6 was chosen to fix. *Amended again 2026-11-02 (Allan):* the newest qualifying season in the window is used, not the best, so declining veterans are judged on their latest full season.
- Player positions are not in the box-score dataset. A position source with a compatible license is a dependency the plan must find and check. Older eras may have only guard/forward/center, mapped onto Sleeper's NBA slots.
- Playoffs, trades, waivers, blind mode, daily shared challenges, leaderboards, crowd-sourced ADP and opponents modeled on the user's real league-mates are out of scope.
- Football is not part of this feature.
