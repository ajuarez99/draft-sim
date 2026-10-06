# Quickstart: validating spec 018

Each check is recorded in `verification.md` as **run** (with the actual output) or **not
run** (and why). A green suite alone doesn't count as verified here.

## Prerequisites

- Postgres on `localhost:5433` (db/user `draftsim`) with the NBA 2024/2025 and NFL
  2025/2026 leagues ingested. Not the unrelated cluster on 5432.
- This worktree's backend running (`bootRun` via a `.claude/launch.json` entry pointing at
  **this** worktree. Memory: a worktree preview can serve main's checkout. Check the
  classpath in `preview_logs` before trusting a live check).
- `config/weights.yml` contains the `draft-grades` block.

## V0. Re-run the DB-only research claims (review N16)

The review couldn't reach the DB, so these were never independently checked. Re-run them
and record the numbers:
- `ScoredWeeks` gives 21 final weeks for NBA 2025 and 3+ for NFL 2026;
- `adp_at_time` coverage per draft (R10), and D/ST rows in `player_game`;
- R1 for leagues 9466, 9465 and 3;
- R9's timing, and R6's 168/168 mapping;
- `sport_week_stats` has a row for every final week of NBA 2024/2025 and NFL 2025/2026. If
  not, F7's intersection drops those weeks. Say so, rather than calling it a bug in grades.

## V1. NFL scoring parity, Java (US1, SC-001)

```bash
cd backend && ./gradlew test --tests '*NflScoringParity*'
```

Expected: `NflScoringParityTest` passes, and **not skipped**. `NflScoringParityIT` passes
and logs `matched / mismatched / noGameRow` per league. Today's SQL gives 2,023 / 0 / 16
(league 5), 2,120 / 0 / 38 (9466), 360 / 0 / 0 (4), 413 / 0 / 7 (9465), 418 / 0 / 2 (3).
2026 counts grow as weeks score. Mismatch must be 0. Check the report's skipped count is 0
for the IT.

Mutation check: change one multiplier in the fixture's scoring (e.g. `rec` 1.0 → 0.5).
The pure test must fail. Revert.

## V2. Unit and ordering tests (SC-003)

```bash
cd backend && ./gradlew test --tests '*DraftGrades*'
```

Must include: pick 10 outscoring picks 1–9 → top steal. The positional ordering test (F1).
Self excluded from its own baseline. Windows shifted inward at both ends, and the even
median. Bye weeks not counted (F3), unknown weeks counted (F10), final weeks without game
data excluded and named (F7), `countedForYou <= production` (F2), centred team values sum to
~0 (F4), busts disjoint from steals (N8). Null `countedForYou` for an unmapped manager. Tied
team values share a grade. NBA basis averages a 3-game week, NFL basis takes the one game.
Weeks outside `finalWeeks` ignored.

## V3. The endpoint, NBA 2025 (US2, SC-002)

```bash
curl -s -H "X-Sleeper-User: <a member's sleeper id>" localhost:8080/api/drafts/1229352720230514688/grades
```

Expected: `available: true`, `productionBasis: WEEKLY_AVERAGE_GAME`, `weeksCounted: 21`,
`weeksMissingGameData: []`, `picks.length + excludedPicks = 168`, `neighborsPerSide: 3`.
Injury seasons (Kessler 57, Trae Young 9, Sabonis 8) should still be among the busts. The
steals will differ from research R4's prototype, because the baseline is now positional.
List the new top 5 and explain them rather than matching the old list. List every pick with
`weeksPlayed: 0` by name (N17: pick 122 per the review) and confirm each really didn't play.
Check that picks 1–6 no longer average far above the middle (F9). Time the request (SC-005)
and report the number.

NFL 2025 (`1254190894563729408`): record the top 5 steals and the positional mix of the
top 20. If late QBs still dominate, F1 isn't fixed (SC-003).

## V4. NFL 2026, early (US2 scenario 4)

Same call for `1346366555776126976`. Expected: `productionBasis: WEEKLY_GAME`,
`gradesEarly: true` while `weeksCounted < earlyThresholdWeeks` (4), 180 picks.

## V5. Unavailable states (FR-010)

- A `pre_draft` draft (`1339351318128517120`): `available: false`,
  `DRAFT_NOT_COMPLETE`.
- Remove the `draft-grades` block from a scratch copy of weights.yml (`WEIGHTS_FILE` env)
  and restart: `NOT_CONFIGURED`, and `/api/health` has `draftGradesLoaded: false`.
- A draft not visible to the user: 404.

## V6. Counted for you (US3, SC-004)

Assert `countedForYou <= production` over every pick in V3 and V4. Take one pick with
`countedForYou` far below `production` and check by hand in SQL that he started for another
roster (research R6 found 57 such picks in NBA 2025). Take one pick who started in a
playoff bye week (F3: NBA 2025 weeks 19/21) and check that the week didn't count. On the
PlayerCard, production, counted for you and (NBA) Sleeper's credited points are each
labelled, and credited is marked as a different scale.

## V7. The real UI

`npm run dev`, open `/drafts/1229352720230514688/board`:

1. "How it played out" chip appears. "Steals & reaches" doesn't (no ADP).
2. Turn it on: cells show signed value over slot, with tints that vary (not all at the cap,
   F8). The grade strip shows grades beside numbers "vs. the average team". The legend says
   "season points, counting each week's average game" and states one-game crediting as
   measured.
3. Open Kessler's card: production, weeks played (2), counted-for-you, positional ranks.
4. NFL 2026 board: the segmented control shows both views, and only one is on at a time
   (F8). The early badge is shown.
5. Phone width (375): no page-level horizontal scroll. The strip scrolls inside itself.
6. Console has no errors.

Screenshot 2 and 5 for the record.

## V8. Regression

Full `./gradlew test` (report total and **skipped**) and `npx tsc -b && npm run build` plus
`npx vitest run`. `LeagueControllerRealBoardTest` is unchanged: `/board` didn't change.
