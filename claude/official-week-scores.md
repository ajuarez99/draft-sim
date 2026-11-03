# Official weekly scores from season-total snapshots

2026-11-03. Allan's pick, after the weekly-pot bug report: the week-2 pot went to ImReallyHarry, but in the
Sleeper app GraftonCarlson won the week. Background: `claude/nba-2026-score-drift.md`.

## What's there now

- Every weekly score we show comes from Sleeper's public `/matchups/{week}.points`, stored verbatim in
  `roster_week_points` (`LeagueHistoryIngestService.ingestWeeklyPoints`).
- For NBA "Ball Knowers" 2026 that feed is not the official score, and it keeps changing after the week ends.
  Measured 2026-11-03, week 2: we stored Harry 251.0, Sleeper's feed now says jstrobe 259.0, and the app says
  Grafton. [measured]
- Sleeper's official season totals (`/rosters` → `settings.fpts` / `fpts_decimal`) are already ingested into
  `roster_season.points_for`, but only the latest value. Each ingest overwrites the last one.
  (`LeagueHistoryIngestService.ingestStandings`)
- Control: (Foot) Ball Knowers 2026 (NFL) through week 8 has `fpts` equal to the sum of `/matchups` points
  for **12/12** rosters, to the cent. So for that league the feed and the official numbers agree, and this
  change is a no-op there. [measured 2026-11-03]

## Design

1. **V44 `roster_fpts_snapshot`** (`league_id`, `roster_id`, `through_week`, `fpts`, `fpts_against`,
   `captured_at`; PK `league_id, roster_id, through_week`). `through_week` is the league's
   `last_scored_leg` at capture time.
2. **Capture.** `ingestStandings` already reads `/rosters` on every league refresh (on-visit, at most once an
   hour; `RefreshProperties.STALE_AFTER`). It now also upserts one snapshot row per roster, keyed by that
   pass's `last_scored_leg`. Upsert, not insert-once: while the leg stays at N, later reads replace earlier
   ones, so a stat correction (or a read that raced Sleeper's week processing) heals on the next pass. Once
   the leg moves to N+1, the N row stops changing.
   Captured only while `1 <= last_scored_leg < playoff_week_start`. Whether `fpts` counts playoff weeks has
   not been measured, so playoff weeks are left out rather than guessed.
3. **Derive.** Official score for week N = `fpts(N) − fpts(N−1)`, where `fpts(0) = 0`. A week is OFFICIAL only
   when every roster has both snapshots. Otherwise it falls back to the feed score, and the fallback is
   labelled as such.
4. **Use it in the weekly pot first.** `WeeklyPotService` swaps in the official score for any OFFICIAL week.
   Each `Week` gets `scoreSource: OFFICIAL | FEED`, and the page labels FEED weeks. Other score pages
   (weekly report, luck, record book …) are **not** switched here. Each one has its own rules, and this
   change should be shown to work on one page first.

## What this cannot do (stated, not hidden)

- **Weeks 1–2 of NBA 2026 stay on the feed.** Nobody stored `fpts` after week 1, so week 1 and week 2 can't be
  separated. The current leg is 2, so the first capture is `fpts(2)` (weeks 1+2 together). **Week 3 is the
  first official week**, and only if a capture lands while the leg is still 2 *and* another lands while it is
  3. The week-2 pot stays Harry's until someone fixes those two weeks by hand (the commissioner-override
  option, not built).
- **A missed leg merges two weeks.** If no league refresh happens during a whole leg (nobody visits for a
  week), that week and the next are FEED, not a two-week lump.
- **A late stat correction to an earlier week** that Sleeper applies after the leg moves on lands in the
  later week's difference. Not measured. Expected to be rare and small.
- **Race:** (corrected after review below; the window is minutes) `/league` is read before `/rosters`, so a snapshot can be keyed one leg late.
  was the last read of the leg, the snapshot is off by one week. The window is seconds, and the next pass in
  the same leg overwrites it.
- **No backtesting** (project rule). The check is the live one below.

## Acceptance

- Unit: the derivation (diff, `fpts(0)=0`, a roster missing a snapshot → FEED, a gap in legs → FEED) and the
  pot picking the OFFICIAL winner when the feed disagrees. This needs a preference test, not a structural one
  (lessons class 1).
- IT: the capture writes rows keyed by `last_scored_leg`, overwrites within a leg, and skips outside
  `1..playoff_week_start-1`. Run against Postgres, not just a mock.
- Live: a local ingest of NBA "Ball Knowers" 2026 writes 12 rows at `through_week = 2` whose `fpts` matches
  `/rosters`. The pot page shows weeks 1–2 labelled as feed scores.
- **Owed after ~2026-11-10 (week 3 scored, prod deployed before then):** `fpts(3) − fpts(2)` per roster vs
  `/matchups/3`, and Allan's read of the app's week-3 scores. That is the decisive check.

## Amended after review (2026-11-03)

Bug-hunting review: `claude/official-week-scores-code-review.md`. What changed, and what didn't:

- **H1, fixed.** The `/league`–`/rosters` race is minutes, not seconds: Sleeper's CDN caches each for up to
  5 min plus 5 min stale (reviewer measured the headers). So the "Race" bullet above was wrong. A
  late-keyed snapshot doubles one week and zeroes the next. Re-reading in the same leg never repairs that,
  because the next pass is already in the next leg. `WeeklyPotService.plausibleOfficial` now falls back to FEED
  when an official week has a negative score, or when its league total is more than 25% from the feed's.
  The 25% is hand-set and arbitrary: measured drift is about 8%, and this failure is about 100%. Tests:
  `aSnapshotTakenALegLate…`, `aNegativeOfficialWeek…`.
- **M1, fixed.** The capture had no test of its key. `LeagueHistoryIngestServiceTest` now asserts the row is
  written at `last_scored_leg` (2), not `leg` (3), and that a playoff leg is skipped.
- **L1, fixed.** A week no longer mixes sources: any feed roster missing from the official week drops the whole
  week to FEED.
- **Reviewer measured that `fpts` is regular-season only** (NFL 2025: equals the feed sum through week 14 for
  12/12 rosters, through week 17 for 0/12). The playoff-leg exclusion above was a guess. It is now measured.
- **M2, rejected.** The reviewer said V43 was on an unmerged branch. It is on `origin/main` (`git branch -r
  --contains` → `origin/main`), so V44 is the next number.
- **Left as-is:** L2 (missing `fpts` fails safe to FEED), L3 (a negative week from a late correction is now
  caught by H1's gate), L4 (row-by-row upsert, no single-flight with a manual ingest, both harmless with
  upsert semantics), L6 (balances and settle-up carry no asterisk; the week list is where the source is named).
  L5: `claude/nba-2026-score-drift.md` is not on this branch. It sits uncommitted in the main checkout, from
  another session.
