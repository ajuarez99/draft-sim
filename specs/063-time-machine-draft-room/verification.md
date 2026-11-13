# Verification: spec 063 (Time Machine draft room and playoffs)

**Date**: 2026-11-13. **Tree**: worktree `063-time-machine-draft-room`, uncommitted, base `origin/main` 1369668.

**How it was run**: the real backend (`bootRun` from this worktree, port 8163, classpath checked by the new fields appearing in responses) and the real web app (`vite dev`, port 5263) in the browser pane, against a throwaway Postgres 14 cluster on port 5433, database `draftsim063`. Docker Desktop would not start on this machine today (Windows reported insufficient memory), so the usual scratch database was not available; the historical data was imported fresh through the app's own import endpoint (KAGGLE, SLEEPER, and NFLVERSE 2019-2021). Signed in as a made-up local test identity written to the browser's storage, not a real Sleeper account.

Everything below says whether it was **run** or **not run**.

## Run, and passed

| Check | Result |
|-------|--------|
| SC-001 pick pacing at Normal | 22 consecutive computer picks in a 12-team NBA draft, timed in the page from the live-region updates: 1013 to 1040 ms each. Never two in one frame. |
| FR-001 opening picks paced | A new game opened with Team 3 on the clock and "Your next pick in 9 picks"; the picks before the first turn arrived one at a time. |
| FR-002 nothing taken early | Mid-reveal, two queued players that were about to be picked were still in the list, starred, with no Draft button. By eye in the browser; the every-tick check is the automated test. |
| FR-004 Skip | Pressed mid-reveal; the room went to the fan's turn and the next picks were accepted. |
| FR-005 speed persists | Set to Instant, reloaded: Instant still selected. |
| FR-007 reload mid-reveal | Reloaded three seconds into a reveal: the room showed the true state with the fan on the clock. |
| FR-031 to FR-033 queue | Queued three players; the banner read "Auto-pick: Julius Erving"; Auto-pick took him and the label moved to the next; the other two left the queue when their picks were revealed ("Queue (0)"). |
| FR-026 roster panel | Erving seated at SF, Adams at C, later picks filling SG, PF, UTIL as drafted. |
| FR-025 / PR-12 board | Board tab takes the full width with the banner kept; cells match the picks. |
| CR-01 Auto-finish during the final reveal | Drafted the fan's last pick (14.01), pressed Auto-finish three seconds into the last eleven computer picks: no error, results appeared. |
| SC-004 bracket, by hand (NBA 1977-78, game 1) | Seeds 1 to 6 match the regular-season table. Round one 3 v 6 (187.0 v 213.5) and 4 v 5 (216.5 v 188.0); semifinals seed 1 v seed 6 (the lowest left) and seed 2 v seed 4; final seed 2 beat seed 6, 252.0 to 184.0. Final places: champion, runner-up, then semifinal losers by seed (3rd, 4th), round-one losers by seed (5th, 6th). Standings order checked: 9-9-1 sits above 9-10-0, equal records split by points for. The fan finished 9th and the page said "You missed the playoffs (9th of 12)". |
| SC-004 bracket, independently re-derived (95 real games) | A script created 95 games through the running API (83 NBA, 12 NFL; 44 distinct seasons), auto-finished them, recomputed the standings from the regular-season matchups and the whole bracket from the playoff weeks' scores, and compared with the stored result: 0 mismatches in records, points for, standings order, seeds, pairings, bracket scores against the week list, final places, champion, `userFinish`, `userOutcome`, and which weeks have a fan lineup. Outcomes seen: all five. |
| FR-009 to FR-011 formats on real seasons | NBA 25-week season: 19 + playoffs 20-22, 3 unused. 24-week: 2 unused. 26-week: 4 unused. 2011-12 (19 weeks): 16 + 17-19, shortened. 2019-20 and 2020-21 (21 weeks): 18 + 19-21, shortened. NFL 2020 (17 weeks): 14 + 15-17, 0 unused. NFL 2021 (18 weeks): 14 + 15-17, 1 unused. |
| Shortened-season wording | 2011-12 results: "16 weeks. The real season was only 19 weeks long, so the regular season was cut short to fit the playoffs. 16 weeks over an 11-week cycle: you play five teams twice and six once." |
| List rows | `GET /api/history/games` after the sweep: 96 complete rows, every one with `formatVersion` 2 and an outcome. |
| SC-007 at 1366×768 | Banner, pick rail, roster panel and six player rows visible without scrolling. |
| SC-007 at 375 px | No sideways scroll (`scrollWidth` 375 = `clientWidth` 375) on the football room and on the results page; Players, Queue, Board, Picks, Roster are one tap each. Bracket stacks. |
| SC-009 both sports | An NBA game drafted by hand and finished; football games created and finished through the API and one opened in the room at phone width. |
| Shared rooms untouched | `git diff` is empty for `DraftRoomLayout`, `DraftBoard`, `useRevealedBoard`, `TurnIndicator`, `RoomControls`, `teamNeeds`, `TeamStrip`, `lineup`. |
| Web suite (after the fix pass) | `tsc -b` clean; vitest 172 files, 2933 passed, 0 failed, 0 skipped; `npm run build` built. |
| Backend, Time Machine tests | `SeasonFormatTest` 6, `SeasonReplayPlayoffsTest` 18, `SeasonReplayStandingsOrderTest` 5, `SeasonReplayTest` 8, `ComparisonsTest` 12, `HistoricalGameMvcIT` 15 (1 skipped: needs real seasons in the IT database), `NflGameFlowIT` 8: 0 failures. |
| Mutation checks | Inverting wins, inverting the points-for tie-break, and counting a tie as a full win each fail at least one new test. Passing regular-season weeks to the comparisons fails the whole-season test. Ignoring the server's status in Auto-finish fails its test. |

## Run, and NOT clean

- **Full backend suite**: 13 failures on this machine's empty throwaway IT database, in `AccessControlMvcIT` (1), `AdminDigestControllerIT` (1), `TargetControllerIT` (8), `LeagueRepositorySuccessorIT` (2) and one league-data test. Untouched `main` run against the same database also fails 13: the same twelve plus `DraftGradesReadIT` where this branch had `NflScoringParityIT`. Both odd ones depend on league data the empty database does not have. None is a Time Machine test. This is a property of the throwaway database, not of the feature, but it means **a fully green backend suite was not observed**; it should be rerun where the normal local database exists.

## Not run

- **1998-99** (14 weeks, the season that sits exactly on the one-round-robin floor) did not come up in the random sweep. Its format (11 + 3) is covered by `SeasonFormatTest` only.
- **A game completed before this change, opened in the browser** (SC-006). No such game exists in this fresh database. Covered by an integration test that stores a 062-shaped result and reads it back as an equal JSON tree, and by web tests rendering that fixture; not seen by eye.
- **The mock draft and live draft rooms** were not opened (this database has no league). The claim that they are unchanged rests on the empty diff above.
- **A real screen reader**, and a keyboard-only pass in the browser. Tests assert the controls are real buttons and that the end-of-draft message stays in the live region.
- **Reduced motion** was not toggled in the browser.
- **Slow and Fast speeds** were not timed; Normal and Instant were used.
- **Deploy**: nothing was deployed. Order when it ships is web first, then backend.

## Observations for Allan (not defects against the spec)

- On the results page the "Stats that count" panel still sits above the finish, so the headline is half a screen down. The spec left the results page's disclosure unchanged on purpose; given the "ease and fun" note, collapsing it there too is an obvious next step.
- The reveal intervals (2000 / 1000 / 400 ms) are hand-set guesses. Normal felt right in this run; that is one person's eye, not a measurement of anything.
- On a phone the banner and tabs take the top half of the first screen.

## Final full backend run (after the fix pass)

2,827 tests, 13 failed, 54 skipped. The 13 are exactly the classes and counts that fail on untouched `main` against the same empty database (2,792 tests, 13 failed, 54 skipped): `AccessControlMvcIT` 1, `AdminDigestControllerIT` 1, `DraftGradesReadIT` 1, `LeagueRepositorySuccessorIT` 2, `TargetControllerIT` 8. The branch adds 35 tests and no failure.
