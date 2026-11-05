# NBA 2026 score drift — stored vs Sleeper vs official

Investigation, 2026-11-03 (~15:10 UTC). This is a diagnosis only. Nothing was changed in code, in prod, or in any DB.
League: NBA "Ball Knowers" 2026, Sleeper `1339351318115946496`. At measurement time `leg = 3`,
`last_scored_leg = 2`, `last_report = 2`, `league_average_match = 1`, `game_mode = 1`.

All reads were read-only: the prod API (`/weekly-report/{1,2,3}`, `/analysis`), the Sleeper REST API
(`/league`, `/rosters`, `/matchups/{1,2,3}`), and Sleeper per-game stats for all 202 players on
those matchups. Raw responses and scripts are in the session notes folder,
`nba-2026-score-drift-snapshot-2026-11-03/`, so a later session can diff against them. No prod DB
access: the Railway CLI login has expired. No local DB was used.

Labels: **[measured]** means executed and read today. **[code]** means read from `origin/main` 0446524.
**[inferred]** means a reasoned guess I did not test.

## Short answer

1. **We store exactly what Sleeper's public `/matchups` served at our last fetch.** We do not
   recompute anything. [code + measured]
2. **Sleeper's official standings (`/rosters` `wins`, `fpts`, `fpts_against`) are not computed from
   the numbers `/matchups` publishes**, and this already holds for the live season. That covers both
   today's `/matchups` and the snapshot we took at week close. The official per-week scores cannot be
   recovered from the public REST API. [measured]
3. Part of this matches the 2025 finding: `/matchups` keeps changing after a week closes. Part of it
   is **new**: in 2025 our at-close snapshot matched the official record to within about 1% per
   season. In 2026 it is about 8% low after two weeks. **This is not an ingest bug in our code.**
   [measured; the cause on Sleeper's side is inferred]
4. Every NBA 2026 page that reads `roster_week_points` shows Sleeper's public numbers, not the
   official ones (list below). Separately, the weekly report's W-L ignores the median game. That is a
   real bug of ours, independent of the data question. [code + measured]

## Q1 — where `roster_week_points.starters_points` comes from [code]

- It comes from `LeagueHistoryIngestService.ingestWeeklyPoints`
  (`backend/src/main/java/com/ballknowers/draftsim/ingest/LeagueHistoryIngestService.java:261`).
  It reads `starters_points = /matchups/{week}.points` (`:299`), verbatim, and stores
  `players_points` and `starters` the same way. It is the only Sleeper writer. ESPN has its own path.
  No per-game recompute is involved.
- Refetch rule: `WeekFinality` (`refresh/WeekFinality.java`).
  - A week is refetched on every ingest while `week >= last_scored_leg`.
  - It freezes at the first fetch after `last_scored_leg > week` (`:288`, `:318`).
  - So **week 1 is frozen** at a fetch made sometime after `last_scored_leg` reached 2. I could not
    read the timestamp (no DB access).
  - **Week 2 still tracks Sleeper live** until week 3 is scored.
- Consistent with that [measured]: stored week 2 equals Sleeper `/matchups/2` `points` for
  **12/12** rosters.
  - Stored week 1 differs from `/matchups/1` for **11/12**, by −32.5 to +45.0 (roster 3: 220.0 stored
    vs 175.0 now; roster 12: 202.5 vs 235.0).
  - The parent session measured 22/24 differing earlier today. That matches week 1. On week 2, prod
    has probably refreshed since then [inferred].

## Q2 — which numbers Sleeper's official standings use [measured, and bounded]

Official after 2 weeks (`/rosters` settings), median ON, so 4 games each:

| roster | W-L | fpts | fpts_against | ppts |
|---|---|---|---|---|
| 1 | 2-2 | 475.5 | 508.0 | 491.0 |
| 2 | 2-2 | 499.5 | 479.5 | 536.0 |
| 3 | 0-4 | 466.0 | 549.0 | 511.5 |
| 4 | 0-4 | 400.0 | 515.0 | 454.0 |
| 5 | 2-2 | 508.5 | 480.5 | 539.0 |
| 6 | 4-0 | 506.5 | 422.5 | 528.0 |
| 7 | 2-2 | 461.5 | 473.0 | 495.5 |
| 8 | 4-0 | 533.5 | 428.5 | 537.0 |
| 9 | 2-2 | 454.5 | 475.5 | 486.5 |
| 10 | 2-2 | 474.5 | 476.5 | 508.0 |
| 11 | 0-4 | 464.0 | 515.0 | 506.0 |
| 12 | 4-0 | 529.5 | 450.5 | 544.5 |

What I measured:

- **Totals.** Σfpts = Σfpts_against = **5773.5** for weeks 1–2. Against that:
  - stored scores sum to **5319.5** (−454, about −7.9%);
  - Sleeper `/matchups` now sums to **5306.0** (−467.5, about −8.1%).
  - Neither source matches any roster's `fpts` exactly (0/12 each), or any `fpts_against` (0/12).
  - Both are low for 11/12 rosters. Roster 4 is the exception: official 400.0, both sources 407.0.
- **`fpts` does not include live week 3.** Adding `/matchups/3` gives impossible implied week-1
  scores (8.0 for roster 4), and `fpts_against` stops matching.
- **Credits are real games.** Every per-player credit in today's `/matchups` 1–3 equals one real game
  of that week, rescored with the league's `scoring_settings`. Week-1 starters: 72 last game, 16
  first, 18 another game. So this is lock-in data, not the 2025 pattern of zeroing players who played.
- **`ppts` is best game per player.** Σ(best single game per listed starter) equals `ppts` exactly for
  8/12 rosters, and is within bench reach for the rest.
- **No credit rule I tried reproduces `fpts`.** I tried "credit as served", "lock else best game",
  "best game" and "max(credit, last game)". The official total sits between "as served" (5306) and
  "best game" (6076.5).
- **Official per-week scores cannot be recovered.** Treat the 24 roster-week scores as unknowns.
  `fpts` + `fpts_against` give a consistent linear system of **rank 22**: the two pairing groups
  {1,3,4,9,10,11} and {2,5,6,7,8,12} each leave one free level. Adding the W-L constraints narrows
  that range but does not pin it.
- **Forced games.** For a 4-0 or 0-4 team, the official winner of each of its head-to-head games is
  forced. That gives **9 games**:
  - stored scores pick the official winner in **8/9**;
  - Sleeper-now picks it in **7/9**.
  - Week 1, 7 v 11: officially 7 won. Stored says 209.0–174.5 for 7 (right). Sleeper now says
    193.5–196.0 for 11 (wrong). So `/matchups/1` changed after we captured it, and changed toward
    *wrong*.
  - Week 2, 11 v 12: officially 12 won (12 is 4-0). Both stored and Sleeper-now say **244.5–236.0
    for 11**. So even the current, still-live `/matchups/2` contradicts the official result.
- **The authoritative numbers exist, but not publicly.** Sleeper's GraphQL
  (`sleeper.com/graphql`) has `matchup_legs` with `points` and a per-starter `starters_games` field,
  but it answered `unauthorized` without a user token. I did not try any credentials.

**Inferred:** the official weekly score most likely uses lock and lineup information that the public
`/matchups` payload does not carry. That payload has one end-of-week `starters` array and one credited
game per player. `starters_games` is the likely carrier. Not tested.

## Q3 — 2025 degradation, or a new ingest bug?

- **Not an ingest bug in our code** [measured]: stored week 2 matches what Sleeper serves 12/12, and
  the code stores `points` verbatim.
- **Same class as 2025** [measured]: `/matchups` for a closed week keeps changing (week 1, 11/12
  rosters), and one forced game flipped to the wrong winner.
- **New** [measured]: in 2025 our at-close snapshots matched official `fpts` to within 0–44 per
  team-season. Here the snapshot is about 8% low after two weeks, and one forced game is wrong even
  live (week 2, 11 v 12).
  - Possible causes [inferred, untested]: a 2026-season change on Sleeper's side (median games are
    new to this league), or lock-ins that the public payload loses as soon as they are made.
  - Also possible: 2025 matched on season sums because per-week errors cancelled out. I never measured
    2025 per week against an official per-week source, because there isn't one.

## Q4 — affected pages [code]

Everything that reads `roster_week_points` / `LeagueMatchupRepository.pairedWithScores` for NBA 2026:

- weekly report (scores, records, awards);
- League analysis scores and matchups;
- power rankings REALIZED;
- Luck / expected wins (`ExpectedWinsService`);
- spec 054 median and vs-all columns;
- head-to-head and rivalries;
- record book (`LeagueRecordService`);
- manager career;
- season superlatives;
- weekly pot;
- playoff odds (any score-based input);
- draft grades and trade verdicts (realized side);
- the recap, and roster management "should have started".

**Not affected:** anything shown from `roster_season` (`/rosters` W-L, `fpts`). That is official.

Luck in particular compares *official* W-L against *expected* wins built from unofficial weekly
scores, so it mixes the two sources.

**Separate bug [measured + code]:** `WeeklyReportService.recordAfter`
(`engine/WeeklyReportService.java:771`) counts head-to-head results only, never the median game. After
week 2 it shows roster 12 as 1-1 (official 4-0), roster 11 as 1-1 (official 0-4), and roster 1 as 0-2
(official 2-2). Part of that gap is the score drift (11 v 12). Most of it is the missing median win
or loss. This can be fixed on its own.

## Recommendation

Change nothing in ingest yet. A re-ingest would only replace one non-official number with another.
Never re-ingest NBA 2026 weeks from `/matchups` expecting a correction: week 1's stored snapshot is
*closer* to official than Sleeper's current answer (8/9 vs 7/9 forced games).

1. **Decisive check, no auth needed (do it after week 3 is scored, ~2026-11-09/10).** Sleeper's
   `fpts` and `fpts_against` move by exactly one week when week 3 processes. Take
   (`fpts` after) − (`fpts` in this snapshot) per roster. That is **Sleeper's official week-3 score
   per roster**, exactly. Compare it with `/matchups/3` and with what we store for week 3. Three
   outcomes:
   - they agree → `/matchups` is right at close and drifts afterwards, and our capture timing is the
     fix;
   - they disagree → the public payload never carries the official score;
   - Δ`fpts_against` cross-checks the pairing.

   Going forward this suggests a cheap, durable fix: store a per-week `fpts` snapshot from
   `/rosters` at each refresh, and derive official weekly totals by differencing.
2. **Quickest human check (2 minutes, Allan):** in the Sleeper app, read week 2 for 11 v 12 and week 1
   for 3 v 2. If the app shows 12 beating 11, the app and `/rosters` agree and the REST `/matchups`
   is the odd one out.
3. **Fix independently:** make the weekly report's record count the median game when
   `league_average_match = 1`, or show `roster_season` W-L for the latest week. Small, and does not
   depend on 1–2.
4. **Honesty label in the meantime:** NBA current-season score-derived panels could carry a caveat
   along the lines of "weekly scores from Sleeper's public feed; may differ from official standings
   by a few percent". That fits the project's confidence and provenance convention.

None of 1–4 has been started. Each needs Allan's go-ahead.

**Amended 2026-11-03, after Allan's go-ahead on item 3.**

- **Built:** in worktree `.claude/worktrees/fix-weekly-report-median-record`, branch
  `fix-weekly-report-median-record`, off `origin/main` 0446524. Uncommitted.
  - `WeeklyReportService.recordsThrough` replaces `recordAfter`. It adds one median game per
    regular-season week when `league_average_match` is on.
  - It reuses `ExpectedWinsService.headToHead`, `median` and the regular-season bound, so it cannot
    count a game differently from Luck or the spec 054 column.
- **Unit test:** `WeeklyReportRecordTest`.
- **Real-data check [measured]:** prod's stored week 1–2 scores now give
  `{1=1-3, 2=2-2, 3=1-3, 4=0-4, 5=4-0, 6=3-0-1, 7=2-2, 8=4-0, 9=1-2-1, 10=1-3, 11=2-2, 12=2-2}`.
  - 4 games each, the right shape. 4/12 equal the official record; the rest differ because of the
    score drift above, not the rule.
  - **New open question:** rosters 6 and 9 each tie a week's median, because in that week the middle
    two stored scores are equal and the shared rule scores that as a tie. Sleeper's official
    2026 record has no ties. Sleeper's tie-break at the median is unmeasured. The same rule already
    drives spec 054 and Luck, so it was left alone here.
