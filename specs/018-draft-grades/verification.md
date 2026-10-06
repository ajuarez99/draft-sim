# Verification: spec 018

Each check is marked **run** (with output) or **not run** (and why). Filled in as tasks
complete.

## T001: database

**Run, 2026-10-06.** Postgres on 5433 was down. Docker Desktop failed with "Insufficient
system resources" mounting its WSL data disk. `com.docker.backend` held ~7 GB while failed,
leaving 683 MB free of 16 GB. `docker desktop restart` brought the daemon up (29.7.2), with
~5.1 GB free afterwards. `docker compose up -d postgres` then started `draftsim-pg`
(postgres:17-alpine, `./pgdata`, 5433→5432). `select count(*) from league` → 9. The
unrelated 5432 cluster wasn't touched.

## V0: DB-only research claims (T002, T003)

**Run, 2026-10-06.**

| Claim | Result |
|---|---|
| Final weeks per league (`ScoredWeeks` rule, read in SQL: `loaded_complete` or no POINTS rows → all stored, else `league_week_fetch` final) | NBA 2024 1–24, **NBA 2025 1–21** ✅, NFL 2025 (5) 1–17, NFL 2025 (9466) 1–18, **NFL 2026 (4) 1–3** ✅, NFL 2026 (9465) 1–2, NFL 2026 (3) 1–2 |
| `adp_at_time` coverage (R10) | NBA 2024 0/168, NBA 2025 0/168, NFL 2025 0/180 and 0/180, NFL 2026 180/180, 210/210, 210/210 ✅ |
| D/ST rows in NFL `player_game` | 672 ✅ |
| R1 for all five NFL leagues (SQL re-run) | 418/0/2 (3), 360/0/0 (4), 2,023/0/16 (5), 413/0/7 (9465), 2,120/0/38 (9466): matched/mismatched/no-row, unchanged ✅ |
| R6: NBA 2025 picks mapped to a roster | 168/168. No manager has more than one roster row in any league ✅ |
| R9: graded players' season rows (NBA 2025) | 9,775 rows, 45 ms (22 ms on 10-05; same order) |
| `sport_week_stats` covers every final week (F7) | NBA 2024 1–25, NBA 2025 1–25, NFL 2025 1–18, NFL 2026 1–4. **No gap** against any league's final weeks. So F7's intersection removes nothing today |

New observation: two NFL 2026 leagues (9465, 3) have only weeks 1–2 final, while (Foot)
Ball Knowers (4) has 1–3. That's per-league refresh timing (spec 009), not a grades issue.
Grades for those leagues count 2 weeks until their next refresh.

## T004

**Run.** Highest migration is still `V27__sport_schedule.sql`. `origin/main` hasn't moved
past `c450f67`.

## V1: NFL scoring parity in Java (US1, T010–T013)

**Run, 2026-10-06.** `NflScoringParityTest` ran 3 tests, `NflScoringParityIT` 1,
`DraftGradePropertiesTest` 3 and `HealthControllerTest` 1. All passed: **0 skipped, 0
failures** (read from the XML reports). The fixture holds 55 real starter-weeks from league 5
(7 QB, 10 RB, 10 WR, 9 TE, 11 K, 8 DEF, including 12 no-game-row 0.0 and 7 negative). The
mutation test (halving `rec`) fails at least one entry, as intended.

IT over every NFL league, run through `GameScoringService` (matched / mismatched / no game
row): 3: 418/0/2, 4: 360/0/0, 5: 2,023/0/16, 9465: 413/0/7, 9466: 2,120/0/38, 460: 0/0/0
(no scored weeks). Every no-row starter was credited 0.0. **Roadmap 2.1 is closed by the
Java.**

Not run yet: booting the app with and without the `draft-grades` block (the properties test
checks the record, not YAML binding). That's covered in T026 / V5.

## Baseline change during build (T026a, Allan's choice)

**Run, 2026-10-06.** The positional-neighbours rule was built as specified and passed 20 unit
tests. On real data it made the first player at each position a steal by construction:
NBA 2025 picks 1–6 averaged +106.7, and Jokić (#1) and Dončić (#2) were in the top 4 steals.
A rank-matched alternative flipped the bias (NBA last 12 +227). The per-position log fit was
chosen and rebuilt. See research R5's note and `claude/lessons.md` #32.

## V3–V5: the endpoint, live

**Run, 2026-10-06**, against this worktree's backend on :8085. The java command line
contains `worktrees\018-draft-grades\backend` (checked), and `/api/health` returns
`draftGradesLoaded: true`, so the real YAML binding works. Called as member
`1122386008709910528`.

| Draft | HTTP / time | Result |
|---|---|---|
| NBA 2025 `1229352720230514688` | 200 / 0.46 s (first call) | available, `WEEKLY_AVERAGE_GAME`, 21 weeks, `weeksMissingGameData` [], 168 picks, 0 excluded/unmapped/unpositioned, Σ team draftValue −0.01 |
| NFL 2025 `1254190894563729408` | 200 / 0.13 s | available, `WEEKLY_GAME`, 17 weeks, 180 picks |
| NFL 2026 `1346366555776126976` | 200 / 0.07 s | available, 3 weeks, `gradesEarly: true`, `earlyThresholdWeeks` 4 |
| NBA 2026 `1339351318128517120` | 200 / 0.01 s | `available: false`, `DRAFT_NOT_COMPLETE`, weeksCounted 0, lists empty |
| NBA 2025, no header / stranger | 404 / 404 | ✅ |

NBA 2025 steals: Jalen Johnson (25, PF) +237.4, Clingan (114, C) +214.2, Knueppel (168, SF)
+206.5, Kawhi (51, SF) +201.8, Murray (43, PG) +196.1. Busts: Trae Young (9) −405.6, Sabonis (8)
−308.9, Kessler (57) −299.7, Kyrie (122) −263.8, Lively (126) −251.8. That's the same 10 players
as an independent SQL fit. Zero-week picks: **122 Kyrie Irving** (N17, matches the review).

NFL 2025 steals: JSN (25, WR) +164.8, J. Taylor (29) +157.6, Javonte Williams (139) +148.4, CMC
(12) +147.2, Pickens (48) +137.3. **No QB in the top 5** (SC-003 / F1). Top-20 mix: RB 8, WR 6,
QB 5, TE 1. Busts: Kyler Murray, Nabers, Mixon, Barkley, Conner. Zero-week picks: Mixon (63),
Aiyuk (125), Amari Cooper (159), Tyler Bass (178).

Edge means (from the service, via the IT):

| Draft | 1–6 | 1–12 | 61–108 | last 12 |
|---|---|---|---|---|
| NBA 2025 | +14.7 | −46.7 | +10.4 | +62.1 |
| NFL 2025 | −38.5 | +0.9 | +12.6 | −21.8 |

Not balanced to zero at every end, but no end is favoured by construction any more.

NFL 2026 zero-week pick Josh Jacobs (22): 4 `ENTRY_WITHOUT_PLAY` absences in `player_absence`,
so he really didn't play. ✅

**Not run:** `NOT_CONFIGURED` against a live server (needs a restart with a stripped
`WEIGHTS_FILE`). It's covered by `DraftGradesControllerTest` and
`DraftGradePropertiesTest` only.

## V6: counted for you (US3)

**Run.** NBA 2025 and NFL 2025: no pick has `countedForYou > production`. In NBA 2025, 76 of
168 picks have counted < half of production, `weeksUnknownForYou` totals 0, and
`unmappedPicks` is 0 (backend agent's IT output). The playoff bye check: Jokić is in roster
3's lineup in 17 counted weeks, but week 19 had no matchup, so `weeksStartedForYou` = 16. The
IT asserts started = lineup weeks − no-game weeks.

**Found and amended (contract invariant 8):** NFL 2026 pick 129 (Tyrone Tracy) has production
**−0.3** and counted 0.0. He never started for his drafter, and his only week-1 game was
−0.6. A subset sum can exceed the total when weeks are negative, and NFL 2025 has 139
negative-scoring games. The code is right and the invariant as written was wrong. The contract
now carries a dated note.

## V7: the real UI

**Run**, Vite on :5185 proxied to :8085, signed in at the local gate as `popsharky`.

1. NBA 2025: only "How it played out" shows (no ADP), and there's no "Steals & reaches". ✅
2. On: legend "Season points, counting each week's average game. In the basketball leagues
   measured so far, Sleeper credited one game per starter per week. Counts 21 weeks." The
   grade strip shows "−233.6 C+ vs. the average team in this draft". The steals & busts panel
   matches the API. Cells show signed points with varying tints. ✅ (screenshot taken)
3. Kessler's card: production 50.8 "season points, counting each week's average game",
   weeks played 2 of 21, baseline 350.5 "vs. what a C taken at pick 57 scored in this draft
   (fitted)", value −299.7, "14th C drafted, finished 44th", counted for jstrobe 50.8 (2
   weeks started), credited by Sleeper 71.0 "a different scale". ✅
4. NFL 2026: the segmented control works. Steals & reaches → `aria-pressed` on that one only.
   "How it played out" → only that one, 180 cells with point deltas, early badge shown. ✅
5. 375px: document width 375 = viewport, so no page scroll. The strip is `overflow-x: auto`,
   2,108px of content in 303px. ✅ (screenshot taken)
6. Console: two 403s, both `POST /api/leagues/1339351318115946496/refresh`, the existing
   visit-refresh, which needs a refresh secret locally. They predate this branch and aren't
   related. No errors from this feature.

**Design note, not fixed:** with grades on, the strip and the steals & busts panel push the
board below the fold. `CompletedDraftBoard` was deliberately board-first. Worth a look
before merge (collapse the panel, or put it after the board).

## V8: regression

**Run on the final tree:** backend `./gradlew test` gives **1,182 tests, 0 skipped, 0
failures, 0 errors**. Web: `tsc -b` clean, `vitest run` 82 files / **1,030 passed**,
`npm run build` OK. `LeagueControllerRealBoardTest` is unchanged (`git diff` empty).

## Code review fixes (T036a/T036b), re-verified live

**Run, 2026-10-06.** [code-review.md](code-review.md) found 3 medium and 3 low issues, and no
blocker. Fixed: B1 (basketball fits one curve over the whole draft, because Sleeper sorts NBA
eligibility alphabetically and NBA lineups are 4/9 flexible slots; football stays per position
via the no-default `SportRules.draftGradeGroup`), B2 (missing-game-data sentence), B3 (count a
week only when `sport_week_stats` has it final), B4 (rounded positional-finish ties). B5 and B6
were resolved as dated doc amendments. The copy notes are fixed too.

Restart gotcha: stopping the Gradle task left the old JVM (PID 11072) serving :8085, and the
health check answered from stale code. It was killed after confirming its command line was
this worktree's, then the new process (PID 29516, started 10:13:20, worktree classpath) was
checked before re-measuring.

| Draft | unpositioned / null value | 1–6 | 1–12 | 61–108 | last 12 |
|---|---|---|---|---|---|
| NBA 2025 | 0 / 0 | +11.3 | −50.2 | +11.5 | +43.9 |
| NBA 2024 | 0 / 0 | −24.5 | −16.0 | +17.6 | −38.7 |
| NFL 2025 | 0 / 0 | −38.4 | +0.9 | +12.6 | −21.8 |
| NFL 2026 (4) | 0 / 0 | −0.2 | −6.7 | +0.8 | −1.1 |

NBA 2025 steals now: Jalen Johnson, Clingan, Kawhi, Okongwu, Murray. Busts: Trae Young,
Sabonis, Kessler, Kyrie, Beal. On the live card, Anthony Edwards reads "drafted 7th, finished
14th among this draft's picks" and "vs. what a player taken at pick 7 scored in this draft
(fitted)". The cell hover uses the fitted wording.

Regression after the fixes (fix agent's run): backend **1,187 tests, 0 skipped, 0 failures**.
Web `tsc -b` is clean, vitest **1,036 passed**, and the build is OK. One full vitest run had a
single failure in `LeagueHome.test.tsx` (spec 017, untouched here), which passed alone and on
the rerun. That's recorded as a probable flake, not investigated.

## Not run

- `NOT_CONFIGURED` against a live server. It's covered by unit tests only.
- Production deploy (T038 waits on Allan).
- The board-below-the-fold design note (V7) isn't addressed.
