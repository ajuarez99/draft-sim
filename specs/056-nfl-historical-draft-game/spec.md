# Feature Specification: Football Time Machine ("Draft a random NFL year")

**Feature Branch**: `056-nfl-historical-draft-game`

**Created**: 2026-11-05

**Status**: Draft

**Input**: User description: "ok can you copy that basketball draft game and do it for football", with Allan's answers from 2026-11-05: seasons back to 2000 using nflverse, full PPR, no kickers or team defenses. This is the football version of spec 053 (`specs/053-historical-draft-game/`, PR #53, not yet merged). That spec listed football as out of scope. *(Amended after review 2026-11-05, F1: 053 is merged to main and closed PR #53, and this branch is built on main.)*

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Draft a random past NFL season and see how you would have done (Priority: P1)

A fan opens the game, chooses football, and gets a random NFL regular season from 2000
to 2025. They draft a team against computer opponents in a normal snake draft. Each
player shows the stats he put up **the season before**, which is what a real drafter
would have known. The season's name is shown up front, but its stats stay hidden until
the draft is over. Then the real season plays out week by week as best ball: each week,
every rostered player scores his real full-PPR points from that week's game, and the
strongest legal lineup is chosen automatically. The fan sees weekly head-to-head
results, final standings and where they finished.

**Why this priority**: This is the whole game. Nothing else is worth building without it.

**Independent Test**: Start a football game, finish a draft and check that the season
replays to a final standings table. Pick one week and one team and work out by hand
that the team's score equals the best legal lineup of that week's real PPR scores.

**Acceptance Scenarios**:

1. **Given** a signed-in user, **When** they start a new football game, **Then** they are told which season they're drafting (e.g. "2007"), their draft slot, the scoring (full PPR) and the roster, and the board shows every draftable QB, RB, WR and TE with prior-season stats only.
2. **Given** the draft is in progress, **When** it is a computer opponent's turn, **Then** the opponent picks without the user doing anything, as in the basketball game.
3. **Given** the draft is complete, **When** results are shown, **Then** every team has a week-by-week score, a win-loss record and a final standing, and the user's finish is highlighted.
4. **Given** a week where a rostered player's team had a bye, **When** that week is scored, **Then** he scores 0 and the lineup is filled from the bench if possible.
5. **Given** a week where three of a team's RBs and WRs outscore its TE, **When** the lineup is chosen, **Then** the FLEX takes the best remaining RB/WR/TE and the TE slot still holds a TE.

---

### User Story 2 - See how your draft compares (Priority: P2)

After the season replays, the user sees their result against the computer teams, the
best-scoring team anyone could have drafted from their slot with hindsight, their
biggest bust and the best steal they passed on, as in the basketball game.

**Why this priority**: This is the "how you would've done" payoff. The replay has to work first.

**Independent Test**: Finish one game. Check that the hindsight draft, bust and steal are each worked out from the real season data and can be checked by hand.

**Acceptance Scenarios**:

1. **Given** a finished game, **When** results are shown, **Then** the user sees their total points next to a hindsight-optimal draft from the same slot, and the gap.
2. **Given** a finished game, **When** results are shown, **Then** the biggest bust and the steal passed on are named with the numbers behind them.

---

### User Story 3 - Know where the numbers come from (Priority: P2)

The game says plainly that the stats come from nflverse, gives the attribution the data
licence requires, and says the computer opponents draft from last season's points, not
ADP. A season with a known data gap (e.g. 2000 is missing 2 of its 248 games) says so on
the draft screen.

**Why this priority**: This is the project's core rule: don't look more certain than the data allows. It doesn't block play.

**Independent Test**: Start a 2000 game. Confirm the missing-games note, the nflverse attribution and the "ranked by last season's points" label are all on screen, and the word ADP is not.

**Acceptance Scenarios**:

1. **Given** any football season, **When** the board is shown, **Then** it is labeled "ranked by last season's points over replacement" and the word ADP is not used for it. *(Amended after review 2026-11-05, F6: the ranking is now points over replacement.)*
2. **Given** a season where the loaded game count differs from the real schedule, **When** the draft screen is shown, **Then** it names how many games are missing.
3. **Given** any football screen that shows nflverse stats, **Then** the nflverse attribution is visible on that screen or one click away from it.

---

### User Story 4 - Revisit past football games (Priority: P3)

A user can see the football games they've already played, with season and finish, and
reopen any of them, alongside their basketball games and clearly marked by sport.

**Why this priority**: Nice to have. A single game is complete without it.

**Independent Test**: Play one football and one basketball game, leave, come back and open both from the list.

**Acceptance Scenarios**:

1. **Given** a user with finished games in both sports, **When** they open the game's page, **Then** each past game shows its sport, season and finish, and reopens with its results unchanged.

---

### Edge Cases

- **Players with the same name** (there were 2 such pairs in 2000 and 6 in 2005, measured): kept apart by the source's player id, never merged.
- **Source rows with no player id** (17 in 2005, measured): they can't be tied to a person, so they are left out. *(Amended after review 2026-11-05, F7: in 2001 and 2021 every such row is a team-total row (`player_name = 'Team'`, penalties and punts only), not a missing player. A data note counts only blank-id rows that carry a skill position or a scored stat, expected 0. The raw count goes in the import report only.)*
- **Rookies and players with no prior NFL season**: draftable. The card says "no NFL stats last season" and they rank after everyone with a prior line.
- **Players who changed teams mid-season**: all their games count.
- **A player listed at a position the game doesn't use that season** (e.g. a fullback, or a player who moved from WR to TE): the position comes from the drafted season's own data before the draft. A player whose only listed position is outside QB/RB/WR/TE is not draftable.
- **Season length**: 16 games and 17 weeks through 2020, 17 games and 18 weeks from 2021. The replay uses every regular-season week the source has.
- **2001**: week 2 was postponed after September 11 and played at the end of the season. The replay follows the source's week numbers, so the season still has 17 weeks.
- **Byes and weeks with no game**: a player scores 0 that week.
- **A drafted player who never plays**: zero every week. A slot left empty scores 0.
- **The user leaves mid-draft**: the game can be resumed or abandoned, and is never counted as finished.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST offer NFL regular seasons 2000 through 2025 and pick one at random when a football game starts. The user chooses the sport before the season is drawn. Basketball keeps working exactly as spec 053 defines it.
- **FR-002**: The draft MUST be a plain snake draft (no reversal round): 12 teams, 15 rounds, starters QB, RB, RB, WR, WR, TE, FLEX (RB/WR/TE) and 8 bench. There are no kickers and no team defenses. Team count, rounds and bench size are hand-set and MUST be labeled as hand-set defaults on the start screen.
- **FR-003**: Scoring MUST be full PPR: passing 0.04 per yard, 4 per TD, −1 per interception *(amended after review 2026-11-05, F13, Allan: was −2, nflverse's value; −1 is Sleeper's and his league's)*; rushing and receiving 0.1 per yard, 6 per TD, 1 per reception; −2 per fumble lost; 2 per 2-point conversion; 6 per special-teams (return) TD. *(Amended at planning 2026-11-05, research R2: without return TDs the formula matched the source's PPR on 99.6–99.9% of rows, and with them on 100% in all four measured seasons.)* The system MUST compute points from the box-score stats with these values, and MUST NOT just copy the source's own fantasy-points column. That column is used only as a cross-check (SC-005).
- **FR-004**: Player cards and the board MUST show only information from before the drafted season: the prior season's games played, totals and per-game numbers for the scored stats, prior-season fantasy points, team, and position. Nothing from the drafted season may appear before the draft is complete.
- **FR-005**: Players' real names MUST be shown. Blind mode is out of scope.
- **FR-006**: Computer opponents MUST draft with the existing mock-draft opponent behavior, ordered by the fallback ranking (Assumptions, as amended after review: points over a positional replacement level, with rookies placed by real NFL draft slot). The board MUST label the ordering "ranked by last season's points over replacement", never "ADP".
- **FR-007**: After the draft, the system MUST replay the drafted season week by week. Each week, a rostered player scores his PPR points from that week's regular-season game, or 0 if he had none. The best legal lineup is chosen automatically.
- **FR-008**: Results MUST show each team's weekly scores, win-loss record and final standing, with the user's team highlighted. Head-to-head matchups follow a round-robin schedule over the season's weeks, the same way basketball does it.
- **FR-009**: Results MUST show the user's total points against a hindsight-optimal draft from the same slot, their biggest bust, and the best steal they passed on, each with the numbers behind it.
- **FR-010**: A game's draft and results MUST be saved to the user and reopen unchanged. Reopening MUST NOT re-run the replay on different data.
- **FR-011**: Two different people MUST never be merged into one player, even if they share a name.
- **FR-012**: Every football season MUST use the one nflverse source for its stats. Sleeper data MUST NOT be mixed in for any season.
- **FR-013**: The nflverse attribution required by its CC-BY-4.0 licence MUST be shown wherever football stats are shown (on screen or one click away).
- **FR-014**: Each season's data notes (missing games against the real schedule, source rows dropped for having no player id) MUST be recorded when the season is loaded and shown on the draft screen when nonzero.
- **FR-015**: Past games MUST show their sport, and the basketball and football lists MUST NOT mix up one sport's game with the other's.

### Key Entities

- **Football season**: one NFL regular season. Holds its year, its weeks, its real game count against the loaded game count, and its data notes.
- **Football player**: one real person, identified by the source's stable player id. Holds the name.
- **Player week**: one player's stats from one regular-season game, with week, team and opponent.
- **Prior-season line**: a player's games, totals, per-game numbers, PPR points, team and position from the season before the drafted one.
- **Game**: one user's play of one season, now tagged with its sport. Holds the season, slot, picks, weekly results, standings and comparison figures. Belongs to that user.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user can go from "start game" to a finished football season's results in under 10 minutes. Computer picks and the replay add no more than a few seconds of waiting in total.
- **SC-002**: For every offered season, the loaded regular-season game count is checked against the real schedule (248 for 2000–2001 with 31 teams, 256 for 2002–2020, 272 from 2021, except 2022 = 271 because Bills–Bengals was cancelled; amended after review, F15). Every season matches, or its gap is recorded and shown (FR-014).
- **SC-003**: For at least one week in at least three different seasons (one before 2010, one 2010–2020, one 2021 or later), a team's weekly score worked out by hand from the box scores matches the game's number exactly.
- **SC-004**: For at least 99% of player-weeks in three checked seasons, the computed PPR points equal the source's own PPR column within 0.01. Any disagreements are listed rather than hidden.
- **SC-005**: For one season from 2015 onward, each player's season totals of passing yards/TDs/INTs, rushing yards/TDs, receptions and receiving yards/TDs from nflverse are compared with Sleeper's for players with 50+ PPR points. The share that match exactly is reported as measured, and disagreements are listed. *(Amended after review 2026-11-05, F5: this compared PPR points within 1 point at ≥95%. Sleeper's `pts_ppr` uses different scoring, so FR-003 applied to Sleeper's own stats came within 1 point for only 53% of players. Comparing points would invite retuning FR-003 to agree.)*
- **SC-006**: No football board shows the word "ADP".
- **SC-007**: Zero cases of two different people merged into one, checked against the same-name pairs found in the data.
- **SC-008**: Basketball games started before and after this feature behave the same: an existing basketball game reopens with identical results.

## Assumptions

- The game is for signed-in users of the existing site, with no league needed, as in basketball.
- **Data source**: nflverse weekly player stats (the `stats_player` release, one file per season), licensed CC-BY-4.0. Measured 2026-11-05: present for 1999–2025, keyed by a stable player id. 2000 has 246 of 248 regular-season games, and 2005 has 17 rows with no player id. Sleeper's NFL weekly stats only start in 2009 (measured), so it can't cover the early seasons and is used only as a cross-check.
- **Fallback ranking**: prior-season PPR points per game × a standard season length (17), with a minimum-games floor. Players under the floor use the newest season within a short lookback window that meets it, the same rule basketball settled on. Both numbers are hand-set and labeled. Rookies rank last, with no draft-order source.
  - *Amended after review 2026-11-05 (F6, F14, Allan):*
    - **Points over replacement.** A veteran's ranking score is his PPR per game × 17, minus the score of the Nth-best veteran at his position. N = 12 teams × that position's dedicated starter slots: QB 12, RB 24, WR 24, TE 12. Raw points put 7 QBs in 2001's top 24, so the bots would have hoarded QBs.
    - **Rookies by real NFL draft slot.** A rookie taken in NFL rounds 1–2 is valued like the veteran at positional rank 12 + his order among that position's early rookies. The 12 is hand-set. nflverse `draft_picks`, CC-BY. Without this, 207 of 533 players on the 2001 board, Tomlinson and Brady among them, could never be drafted by a bot. Later picks and undrafted rookies still rank last.
    - All of these values are hand-set and labeled.
- **Positions**: taken from the source data. A player's draftable position for a season comes from his prior-season line where he has one, else from his first row in the drafted season, which is a known small leak of the drafted season (position only, no stats).
- **The 2-point conversion and fumble-lost values** follow common PPR defaults, since no specific league's scoring was asked for.
- Out of scope: kickers, team defenses, IDP, half-PPR or league-specific scoring, playoffs, trades, waivers, blind mode, leaderboards, real-ADP sources, and seasons before 2000.
