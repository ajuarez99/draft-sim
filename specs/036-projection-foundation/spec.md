# Feature Specification: Projection snapshots and a measured accuracy number

**Feature Branch**: `036-projection-foundation`

**Created**: 2026-10-14

**Status**: Draft

**Input**: Roadmap Phase 3.1 + 3.2 (`claude/competitor-gap-roadmap.md`, `claude/projection-tools.md`).
Allan's decision 2026-10-14: yes, measure RotoWire projection accuracy against these leagues.
Measuring an input is not backtesting the draft engine. The full brief is in the conversation
that started this spec; the parts that turned out to be wrong are amended below, not dropped.

## Background

### What the roadmap asked for, and what this spec found first

The roadmap's Phase 3 had two opening items: 3.1, a projection foundation for both sports, and 3.2,
"rate my team" for NBA on the Analysis page. Reading `main` (f4e3fa2) before writing this spec
changed both:

| Brief said | Found on `main` / measured 2026-10-14 | What this spec does about it |
|---|---|---|
| `SleeperProjectionClient.POSITIONS` is NFL-only | Spec 030 already split it per sport (`NFL_POSITIONS`, `NBA_POSITIONS`, `ingest/SleeperProjectionClient.java:31-36`). | Nothing to do. |
| Store projection stat lines, not points | Spec 030 already stores NBA **season** per-game stat lines (`player_season_projection`, V31) and scores them per league at read time. Weekly rows (`player_projection`, V15) are still points-only. | Weekly stat lines are still needed, for the measurement and for exact NFL scoring (US1, US3). |
| 3.2: Analysis page renders NBA roster projections | **Already built by spec 030** (`LeagueAnalysisService.basketballBlocks`, `:569-629`): a ranked best-lineup projection under league scoring, with Out/IR/Sus set aside. | Dropped from scope. The roadmap gets a note saying so. |
| `projectedWeek` = sum of a week's projected games | **Wrong for these leagues.** Sleeper credits an NBA starter with **one game per week**, verified 18 of 18 weeks (Jokić stored 58.5 against four games summing to 182.0; memory `project_nba_one_game_per_week_is_correct`). Spec 030 already values NBA at one game per week (`UNIT_PER_WEEK`). | Not built. Summing a week's NBA games would roughly triple every number. |
| Past projections can be fetched to measure accuracy | **Past weeks are served, but not provably pre-game.** All 583 of 583 NBA 2025 week-5 rows have `last_modified` after their game date (e.g. player 1535: game 2025-11-17, modified 2025-11-24, 20.73 pts). NFL 2025 week 5's latest `last_modified` is 2025-10-07, after that week's games. | Accuracy is measured only against snapshots this app captured itself before each game (US1). |

### Why the timing matters

The NBA 2026 regular season tips off on 2026-10-20. Every game played before the snapshot
capture is deployed is a game that can never be measured. NFL 2026 is mid-season, so it can only
be measured from the next unplayed week on.

## Clarifications

### Session 2026-10-14

- Q: Measure RotoWire accuracy against these leagues? → A: Yes (Allan). It measures an input. The
  draft engine stays un-backtested, and nothing in `config/weights.yml` is retuned to the result.
- Q: Use Sleeper's historic 2025 projections to get a number before 2026 samples build up? → A: No
  (see "Not being built"). They can't be shown to be pre-game, and a number that looks measured but
  might be graded on hindsight is worse than "too early to read".

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Keep the projection as it stood before the game (Priority: P1)

The app needs a record of what the projection said **before** each game was played, for every
player in both sports. Sleeper keeps revising a projection after the game. Without that record,
nothing can ever say how good the projections were.

**Why this priority**: everything else in this spec depends on it, and it can't be done after the
fact. Every NBA game before deploy is lost for good. It has to ship before 2026-10-20.

**Independent Test**: let the capture run across one game day. For every captured game, its stored
projection was taken before the game's cutoff. A later fetch after the cutoff doesn't change it,
and the fetch time and Sleeper's own last-modified time are both visible.

**Acceptance Scenarios**:

1. **Given** a player with a projected game tomorrow, **When** the capture runs today, **Then** that game's projected stat line is stored with its game, date, opponent, fetch time and Sleeper's last-modified time.
2. **Given** a stored pre-game projection, **When** the capture runs again before the cutoff with a changed line (late injury news), **Then** the stored line becomes the newer one, because the latest pre-game line is the fairest test of the source.
3. **Given** a game whose cutoff has passed, **When** the capture runs again and Sleeper serves a different line, **Then** the stored line doesn't change. The newer line may be recorded separately as post-cutoff, but it never takes the frozen line's place.
4. **Given** a game the capture first saw after its cutoff, **When** the capture runs, **Then** no pre-game projection is recorded for that game. A post-cutoff line is never relabelled as pre-game.
5. **Given** Sleeper is down or returns an error, **When** the capture runs, **Then** existing snapshots are untouched, the failure is visible in the refresh status, and the next run tries again.

---

### User Story 2 - How accurate are these projections, for this league? (Priority: P1)

A member looking at a projected number (the Analysis page's rankings, the matchup preview) wants to
know how much to trust it. Today the page says accuracy "hasn't been measured". Once enough games
have been scored, it shows how far off the projections have been **under this league's own
scoring**: the typical miss, whether they run high or low, broken down by position and by the size
of the projection, each with the number of games behind it.

**Why this priority**: it's the decision Allan made. It also replaces a caveat with a measured
number, which is the direction this project wants every caveat to go.

**Independent Test**: for one league and a fixed set of captured, scored games, compute the
typical miss and the bias by hand for one position. They match the page, and so do the sample counts.

**Acceptance Scenarios**:

1. **Given** fewer than N scored games with a pre-game snapshot for this league's sport and season, **When** a member opens the accuracy view, **Then** it shows the sample count and says it's too early to read, and it shows no accuracy figure. N is hand-set and labelled as such.
2. **Given** at least N such games, **When** the member opens it, **Then** it shows the typical miss (mean absolute error) and the lean (mean projected minus actual) in fantasy points under this league's scoring. Each figure has its sample count beside it, overall, by position, and by projection-size band.
3. **Given** an NBA league, **When** accuracy is computed, **Then** each projected game is matched to the same player's realized game by game identity, not by week. A player who didn't play is counted separately as a projected-but-did-not-play miss, not as a zero-point game in the error.
4. **Given** an NFL league, **When** accuracy is computed, **Then** each player-week's pre-game projection is compared with that week's realized points under the league's scoring, and did-not-play is handled the same way.
5. **Given** two leagues in the same sport with different scoring, **When** each opens the view, **Then** each sees figures computed under its own scoring, from the same shared snapshots.
6. **Given** the Analysis page's projected numbers, **When** accuracy has been measured, **Then** the provenance line says which source the projections come from, when they were fetched, and links to the measured figure in place of "hasn't been measured". Below N, it says "too early to measure" and gives the count.

---

### User Story 3 - Football projections under this league's exact scoring (Priority: P2)

A football member's projected numbers are currently Sleeper's precomputed PPR, half-PPR or standard
total, whichever is closest to the league's reception points. A league with any other custom
scoring (bonuses, a different passing-TD value) gets a projection under someone else's rules. With
stat lines stored, the projection can use the league's real scoring through the same rule that
already scores realized games.

**Why this priority**: it fixes an approximation in a shipped page. It's less urgent than US1,
which loses data every day it waits, and it's gated on a parity check that might fail.

**Independent Test**: run the parity check. Score each stored projection stat line with the app's
own scoring rule under full-PPR settings, and compare against Sleeper's own `pts_ppr` for the same
row. Report the mismatch count. Only switch if it's zero or every mismatch is explained.

**Acceptance Scenarios**:

1. **Given** stored NFL weekly projection rows with both a stat line and Sleeper's `pts_ppr`, **When** the parity check runs under a standard full-PPR scoring, **Then** it reports how many rows match (to the cent) and lists every mismatch with its stat line.
2. **Given** the parity check passes, **When** a football league's Analysis page is built, **Then** projected values come from the league's own scoring over the stat line, and the column-pick approximation is no longer used.
3. **Given** the parity check fails, **Then** football keeps the column pick, the spec records the mismatch count and its cause, and US1/US2 still ship.

---

### Edge Cases

- **A postponed game.** *Amended after plan review (S5):* this originally said the snapshot's cutoff "moves with its new date". That premise was false. Measured: NBA 2025's 3 postponed games have no realized rows under their original id, because the rescheduled game gets a **new** game id. A snapshot of a postponed or canceled game is counted as voided and excluded. The rescheduled game is captured fresh under its new id.
- **A player traded mid-week.** The projection and the realized game are matched by player and game, so a team change doesn't break the match.
- **Sleeper projects a player who is already ruled Out.** The snapshot keeps whatever Sleeper served. The accuracy view counts these in the did-not-play figure, which is a fact about the source, and doesn't hide them.
- **A stat correction after the game.** The realized side follows the existing re-runnable realized-game data, so accuracy is recomputed against the corrected line. The projection side never moves.
- **A brand-new league or sport with zero samples.** The view says "too early to read, 0 games", never an error and never a blank.
- **A double-header or a week with no games** (NBA All-Star break). There's nothing to snapshot and nothing to measure. The capture doesn't treat it as a failure.
- **An NBA league whose starters are credited one game per week.** Accuracy is per game, not per credited week, so the one-game rule doesn't distort it. The view must not imply that a week's projection is the sum of its games.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST capture, ahead of each game, the projected stat line for every projected player in both sports, with the game's identity, date, opponent, the fetch time, and the source's own last-modified time.
- **FR-002**: The system MUST treat a snapshot as pre-game only if it was fetched before that game's cutoff. The cutoff is defined per game from its date, hand-set, and labelled as an approximation of tip-off or kickoff if the source gives no start time.
- **FR-003**: Before a game's cutoff, a newer capture MUST replace the stored pre-game line. After the cutoff, the pre-game line MUST NOT change by any path, including re-ingest and admin endpoints.
- **FR-004**: A line first seen after the cutoff MUST NOT be stored or reported as pre-game.
- **FR-005**: The capture MUST run on a schedule without anyone triggering it, and its last run, success or failure, and row counts MUST be visible in the existing refresh status.
- **FR-006**: The system MUST compute accuracy per league under that league's own scoring, using the same scoring rule that scores realized games. There MUST be no second implementation.
- **FR-007**: Accuracy MUST report mean absolute error and mean signed error (projected minus actual), each with its sample count, overall, per position, and per projection-size band.
- **FR-008**: Projected games where the player recorded no game MUST be reported as a separate did-not-play count and rate, and kept out of the error figures.
- **FR-009**: Below a hand-set minimum sample count N, the accuracy view MUST show the count and "too early to read", and MUST NOT show an error figure. N MUST be configured in one place and labelled arbitrary.
- **FR-010**: Every projected number the app shows MUST name its source ("RotoWire projection via Sleeper") and when it was fetched. Where accuracy has been measured, it MUST link to it. Where it hasn't, it MUST say so with the sample count.
- **FR-011**: Projected and realized figures MUST never share a column or a color in the accuracy view.
- **FR-012**: The system MUST provide a repeatable football parity check that reports the mismatch count between the app's scoring over stored stat lines and Sleeper's own full-PPR total for the same rows.
- **FR-013**: If and only if the parity check passes, football projected values MUST come from the league's own scoring over stored stat lines in place of the precomputed-column pick.
- **FR-014**: The measurement MUST NOT feed any hand-set constant, model weight or ranking. It's displayed, never used to tune.
- **FR-015**: The roadmap MUST be amended in place: 3.2 is marked built by spec 030, and the "sum of a week's games" definition is marked wrong for these leagues, with the measured reason.

### Key Entities

- **Projection snapshot**: what the source projected for one player in one game (NBA) or one week (NFL), as it stood at one moment. It has the sport, season, week, player, game, game date, opponent, stat line, source, fetch time and source last-modified time, and it's either pre-game (frozen after cutoff) or post-cutoff (informational only).
- **Accuracy sample**: one pre-game snapshot paired with the same player's realized game. It's derived, not stored as truth, because the realized side can be corrected.
- **Accuracy summary**: per league, sport and season. It has the sample count, did-not-play count, MAE, mean signed error, and the same broken down by position and projection-size band, plus the N threshold it was judged against.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The capture is live in production before the first NBA 2026 regular-season game (2026-10-20). On the first game night, pre-game snapshots exist for at least 95% of players who play that night and had a projection the day before. The percentage is reported, not assumed.
- **SC-002**: Zero pre-game snapshots change after their cutoff, checked by test and by a production query after the first week of games.
- **SC-003**: For one league and position, the published MAE, lean and sample count equal a hand computation over the same rows exactly.
- **SC-004**: Below N samples, 100% of accuracy views read "too early" with a count, and none shows an error figure.
- **SC-005**: The football parity check reports a mismatch count over at least one full NFL week of stored rows. The number is recorded in this spec's verification notes whether it's zero or not.
- **SC-006**: Every projected number on the Analysis page, both sports, names its source and fetch time, checked on the live page.

## Assumptions

- Sleeper's weekly projection endpoint keeps serving per-game NBA rows with game identity and date, as measured on 2026-10-14. If it stops, the capture records failures and the measurement stalls visibly.
- The weekly NBA rows (not the season-average line spec 030 ranks with) are the projection to measure. The season-average line is snapshotted too, so the plan can decide whether to also report how well **it** predicts games, since that is the number the Analysis page actually shows.
- Realized games come from the existing realized-game store (V20 `player_game`), which already holds both sports and is refreshed by the spec 009 schedule.
- N and the projection-size bands are hand-set in the existing weights/config file and labelled arbitrary. Their initial values are a planning decision.
- A per-game cutoff based on the date alone is acceptable if Sleeper doesn't provide a start time. The plan measures whether it does.
- Storage is modest: about 1,000 NBA rows per week, and NFL roughly 500 real rows per week out of about 3,300 returned.

## Not being built

- **NBA "rate my team" (roadmap 3.2).** It already exists (spec 030).
- **A weekly NBA projection that sums the week's games.** It's wrong for these leagues (one credited game per week).
- **A historic 2025 accuracy figure from Sleeper's served past projections.** They can't be shown to be pre-game, and every one of the 583 checked NBA rows was modified after its game.
- **Start/sit, player comparison, waiver assistant, trade analyzer, trade finder** (roadmap 3.3–3.5). Those are later specs on top of this one.
- **Our own projection model, or blending or correcting RotoWire using the measured bias.** The measurement is shown, never applied (FR-014).
- **External trade values and expert consensus.**
