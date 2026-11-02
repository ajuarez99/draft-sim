# Live verification: spec 053 Historical Draft Game (Time Machine)

**Date**: 2026-11-02.

**Setup**:
- Backend: launch entry `053-api`, port 8153, bootRun from the **053 worktree's** classpath. I confirmed this from the process command line, because project memory warns that a worktree preview can run main.
- Database: the isolated scratch Postgres `draftsim-pg-053` on 5438, fully imported (job 58 Sleeper, job 59 Kaggle, after the code-review fixes).
- Web: launch entry `053-web`, port 5253, in the built-in browser pane.
- Identity: a synthetic test user `verify-053`, seeded in localStorage. No real account was used.

Everything below was **executed**. Anything not executed is listed as such at the end.

## Driven in the real UI

| Check | Result |
|---|---|
| `/history` renders; rail shows "Time Machine"; settings source line shown | ✅ |
| "Draft a random year" creates a game and navigates to `/history/119` | ✅ The season was 1993-94, user slot 2, and the bot had already made pick 1 |
| Season disclosure: counted stats with coverage, excluded stats with reasons, ranking label | ✅ "ranked by last season's points". FR-008, FR-009 |
| Player cards show only the prior line, with the season and "excludes bonuses" | ✅ e.g. Olajuwon "1992–93 · 82 G · 26.1 pts …" |
| User pick → bots advance → next turn at pick 23 (2.11), the correct snake turn for slot 2 of 12 | ✅ |
| Auto-finish → season replays → results page | ✅ 4th of 12, 15-10 |
| Standings ordered W-L-T then PF; ties shown | ✅ (19-5-1 row) |
| Comparison panel: hindsight, bust, steal, with the numbers behind them | ✅ Hindsight 7,572.5 vs 5,979.5. Bust: Larry Nance (23rd overall, finished 146th). Steal: Chris Webber at pick 146 |
| Week-by-week matchups and your lineup, with each starter's locked game date | ✅ |
| Past-games list with Resume / Abandon | ✅ |
| Console errors | ✅ none |
| Phone width (375 px): no horizontal scroll (`scrollWidth` 375) | ✅ |

## FR-003: no drafted-season data while drafting (live JSON)

The DRAFTING state of game 119 was inspected in the page with `fetch`:
- The only occurrence of `1993-94` in the whole payload is the season label.
- `ADP` appears nowhere.
- Card prior lines come only from 1992-93 (290), 1991-92 (22) and 1990-91 (7). 82 players have no prior line.
- Card keys are `id, name, positions, team, age, rank, positionalRank, prior`, with nothing from the drafted season.

## SC-003: hand-checked scoring, three seasons, both sources

Each starter's points were recomputed by hand from `historical_player_game` under the Legacy scoring and the season's counted stats. The week-1 lineup total was compared with the matchup score.

| Season (source) | Player | Week-1 games, hand-scored | Locked by the game | Lineup total vs matchup |
|---|---|---|---|---|
| 1993-94 (Kaggle) | Hakeem Olajuwon | 55.0 (11-05), 32.5 (11-07) | 55.0, 1993-11-05 ✅ | 223.5 = 223.5 ✅ |
| 1993-94 (Kaggle) | Michael Adams | 9.0 (11-05), 28.0 (11-06) | 28.0, 1993-11-06 ✅ | |
| 1973-74 (Kaggle) | Dave Cowens | 37.5 (10-12), 26.5 (10-13) | 37.5 ✅ | 217.5 = 217.5 ✅ |
| 2018-19 (Sleeper) | James Harden | 34.5, 30.5 (−2 technical foul), 37.5 | 37.5, 2018-10-21 ✅ | 281.0 = 281.0 ✅ |

Cowens' stored double-double flag (1) is correctly **not** scored, because 1973-74 doesn't count dd (steals and blocks weren't tracked). The hand score of 37.5 = 25 × 0.5 + 16 + 9 matches.

## Other measured results (from earlier stages, re-confirmed after fixes)

- **SC-002 game counts**: 1970-71 = 697, 1975-76 = 738, 1990-91 = 1107, 1998-99 = 725, 2010-11 = 1230, 2011-12 = 990. All match the real schedule. 2019-20 is 971 games through 2020-03-11, with the bubble excluded (FR-010).
- **SC-005 parity, 2018-19**: 523 of 527 players (99.2%) agree within 0.5 pts between Kaggle and Sleeper. The totals are 350,563 vs 350,564. It was 90.7% before the All-Star/Cup fix. The 4 residual players are listed in research-notes.md.
- **SC-001 timing**: create 91–123 ms. The final pick plus replay plus hindsight takes 252–776 ms, measured server-side for 1971-72, 1995-96 and 2023-24.
- **Random season spread**: 25 games created through the API gave 21 distinct seasons from 1973-74 to 2020-21.
- **Bots**: in game 119 the first-round bots took ranks 12, 10, 13, 26, 2, 3, 5, 8, 4, 11, 15. That is noisy but pointed the right way. It is the same neutral-profile engine as the mock room; I didn't measure whether it's noisier here.

## Not verified

- **Production**: not deployed. No Railway volume check (O3 says to check before the first prod import), and no prod import run.
- The 25 games I created as `verify-053` are on the scratch DB only.
- Concurrent users, and the import running while games are DRAFTING. HANDOFF says not to do that; the replay fails loudly if identity drifted (code-review finding 8).
- Whether the shared `weights.yml` Cup-final addition (`20231209_LAL_IND`) changes any existing league feature. Allan's leagues are 2024 and later, so I expect no change, but I didn't run those features to check.
- The full backend suite against the shared 5433 DB. Runs were against 5438, where 5 non-053 ITs fail because they hardcode 5433 or need league rows; see research-notes.

## Copy issues found while driving it (fixed after this pass)

- Raw keys (`bonus_pt_40p`, `tf`, `ff`) showed in the stat chips. They now get fan-friendly labels.
- A finished game's header still said "You're drafting 1993-94". It now says "You drafted 1993-94".
