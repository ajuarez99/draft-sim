# Time Machine: the bots were too weak

Written 2026-11-11, after the fact: the measurement came first, then the change. Branch
`062-time-machine-bot-strength`, off `origin/main` 8c0c457. Nothing here is committed.

## What was wrong

An audit of the Time Machine (specs 053 and 056) played one auto-finished game for every offered
season. A user who only presses "Auto-finish the draft" is a drafter who always takes the
best-ranked player who would start. Against twelve even teams that drafter should win about 1 league
in 12. Measured against real imported seasons, three draft slots each:

| Sport | Games | Won the league | Top 3 | Average finish |
| --- | --- | --- | --- | --- |
| Basketball, 56 seasons | 168 | 39.9% | 71.4% | 2.96 |
| Football, 26 seasons | 78 | 35.9% | 60.3% | 3.83 |
| Even teams, for comparison | | 8.3% | 25.0% | 6.50 |

The bots have no hindsight. They read the same board the user sees, built from prior seasons only
(`HistoricalBoardBuilder.build`), and the drafted season's lines are read once, after the last pick
(`HistoricalGameService.finishDraft`).

## Why

A bot's pick is a softmax draw over the 30 best-ranked available players
(`PickDecider.choose`, `PickScorer.score`). With the shared values in `config/weights.yml`
(`adpScale` 12, `temperature` 1.0) a player 12 places down the board is only e times less likely
than the top one, so the best-ranked player is taken about 8% of the time. In one 2020 football game
the bots left the board's first and second players available at pick 5.

Those shared values are labelled guesses, made for seats that stand in for real managers on a real
ADP board. A Time Machine bot has the neutral profile, uniform positional priors and a board that is
last season's points. The file's own comment calls 1.0 "realistic variance"; nothing measured backs
that for this game.

## What changed

The Time Machine now drafts its bots at its own temperature. `config/weights.yml` is not touched, so
the real-league simulations, the mock room and `EngineOutputFrozenTest` see exactly what they did.

- `historical/BotTuning.java`: one nullable number, the bot temperature. `NONE` returns the shared
  block itself.
- `config/HistoricalBotProperties.java`, bound from a new `historical-bots` block in
  `application.yml`: `nba.temperature` and `nfl.temperature`, both **0.25**, hand-set.
- `HistoricalSport.botTuning()`, default `NONE`; both sports return their configured value.
- `DraftContextFactory.build(..., UnaryOperator<SportScoring>)`: an overload that passes the sport's
  scoring block through a function. The existing six-argument `build` delegates with identity.
- `HistoricalGameService.context` uses the overload with the game's sport.

A game already in progress keeps the picks it has; only bot picks made after the deploy use the new
temperature, because recorded picks are replayed from the pick table, not re-decided.

## The measurement

`TimeMachineBotStrengthHarnessIT` (skips unless `TM_BOT_HARNESS=NBA|NFL` is set). Every offered
season, draft slots 1, 6 and 12, auto-finished. `reach` is the mean distance between a bot pick's
board rank and its pick number over rounds 1 to 3. `rank 1 by pick 3` is how often the board's top
player was gone after three picks, when the user did not pick first.

Basketball, 168 games per row:

| Bot temperature | Won | Top 3 | Average finish | Reach | Rank 1 by pick 3 |
| --- | --- | --- | --- | --- | --- |
| 1.0 (shared) | 39.9% | 71.4% | 2.96 | 8.6 | 23% |
| 0.6 | 28.0% | 53.0% | 4.17 | 6.2 | 46% |
| 0.4 | 25.0% | 50.6% | 4.56 | 4.3 | 48% |
| **0.25** | 11.3% | 38.7% | 5.38 | 2.8 | 70% |
| 0.15 | 12.5% | 29.8% | 6.13 | 1.6 | 95% |

Football, 78 games per row:

| Bot temperature | Won | Top 3 | Average finish | Reach | Rank 1 by pick 3 |
| --- | --- | --- | --- | --- | --- |
| 1.0 (shared) | 35.9% | 60.3% | 3.83 | 8.7 | 23% |
| 0.6 | 19.2% | 42.3% | 5.17 | 6.2 | 42% |
| 0.4 | 10.3% | 34.6% | 5.26 | 4.4 | 50% |
| **0.25** | 9.0% | 34.6% | 5.79 | 2.8 | 75% |
| 0.15 | 7.7% | 23.1% | 6.38 | 1.7 | 94% |
| 0.05 | 10.3% | 21.8% | 6.26 | 0.6 | 100% |

How to read these. 78 and 168 games are small samples: a win rate near 10% carries roughly plus or
minus 7 points for football and 5 for basketball, so 0.25 and 0.15 are not separable on win rate
alone. Average finish and reach move steadily and are the columns to trust.

Why 0.25 and not 0.15. At 0.25 the auto-drafter is a little better than an average team (finish
5.4 to 5.8 against 6.5), which is what always taking the best-ranked starter should earn against
opponents who sometimes reach. At 0.15 the bots take the board almost in order (the top player is
gone by pick 3 in 95% of drafts), so two games of the same season would look alike. That is a
judgement, not a finding. **The number is Allan's to change**; it is one line per sport.

## Measured and not adopted

A second knob, the weight on roster need (shared value 0.5), was tried at 2.0 and 5.0 in football at
three temperatures. It removed the last bot teams with no quarterback or no tight end (0.7% of teams
at temperature 0.4, 0.1% at 0.25, none with the higher weight) and moved nothing else outside the
noise. It was removed again so the game carries no setting without a measured effect.

## Not fixed here

- **Lopsided bot rosters.** Lowering the temperature took football bot teams with no starter at some
  position from 4.0% to 0.1%, but one position still reaches 10 or 11 players on some roster. The
  cause is in the ranking and the need term, not the noise: late in a draft a bench player's need
  score is the floor whatever his position.
- The other audit findings (landing page, four basketball stub weeks, hindsight picks not shown,
  no rate limit on game creation). See the session notes of 2026-11-11.

## Verification

- Unit: `BotTuningTest` (5 tests), including the direction test: mean early reach must fall from
  temperature 1.0 to 0.4 to 0.15.
- Suites run with a database: historical, api.Historical, engine, config and mock packages, 1,631
  tests, 0 failures, 73 skipped (integration tests that need a real league database, and the
  harness).
- Live: see the end of this file.

### Live verification, 2026-11-11

The harness builds its services by hand, so it does not prove that Spring binds `historical-bots`.
This does: `bootRun` from this branch against the audit database `tma_nfl` (real nflverse seasons
2000 to 2025), one auto-finished game per season through `POST /api/history/games` and
`/auto`, with the same invariant checks as the audit sweep.

| | Shared 1.0 (audit sweep, main) | 0.25 (this branch) |
| --- | --- | --- |
| Seasons played, failures, invariant violations | 26, 0, 0 | 26, 0, 0 |
| Auto-drafter won the league | 3 of 26 | 1 of 26 |
| Auto-drafter in the top 3 | 12 of 26 | 3 of 26 |
| Mean bot reach, rounds 1 to 3 | 8.7 (harness) | 2.9 |
| Board's top player gone by pick 3 | 23% (harness) | 19 of 26 |
| Bot teams with no quarterback | 5 of 286 | 1 of 286 |

Not run: basketball through the live server (its numbers are from the harness and from the
Spring-wired `HistoricalRealSeasonIT`, which passed), the browser, and production.
