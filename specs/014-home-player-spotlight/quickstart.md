# Quickstart: validating the player spotlight

This project's bar for "verified" is driving the real thing, not only a green suite. Each scenario below says
what to run and what must be true. Response shapes are in
[contracts/player-spotlight-api.md](contracts/player-spotlight-api.md); entities and rules are in
[data-model.md](data-model.md).

## Prerequisites

- Postgres on `localhost:5433` (db/user/password `draftsim`), with the four NFL 2026 and three NBA leagues
  ingested (research R1). **Not** the unrelated cluster on 5432.
- A backend built **from this worktree**. Port 8080 is often held by another session's `bootRun`, serving
  other code. Start this branch on 8081 (`draft-sim-api-8081` in `.claude/launch.json`) and confirm the
  classpath is this worktree before trusting a response (a peer worktree's `preview_start` has served
  `main` before).
- `X-Sleeper-User` set to a member of the league under test. Without it, every call is a 404 by design.

```bash
cd backend && ./gradlew test
```

Then read the skip count in `backend/build/test-results/test/*.xml`. `BUILD SUCCESSFUL` with integration
tests skipped means Postgres was not reached, not that they passed.

## V1 — Trending lands and is honest about its age

1. Trigger a refresh for an NFL 2026 league (`POST /api/leagues/{id}/refresh`, or open its home).
2. Check that `sport_trending_fetch` has an `nfl` row with `fetched_at` within the last minute and
   `lookback_hours = 24`, and `sport_trending` has 25 rows ranked 1..25.
3. Compare the rows against `curl "https://api.sleeper.app/v1/players/nfl/trending/add?lookback_hours=24&limit=25"`
   fetched at the same moment. Order and ids match (counts may move by a few adds).
4. Set `fetched_at` back 25 h and call the spotlight. `trending.stale` is `true`, and the page shows the age.
5. Make the Sleeper call fail (point `SLEEPER_BASE_URL` at a dead host for one refresh). The league refresh
   still reports success, `last_failure` is set, and the previous list is still served with its true age.

## V2 — Football week sections (live today)

Using "(Foot) Ball Knowers" 2026 (`1346366555759341568`):

1. `GET /weekly-report/0` and `GET /player-spotlight`. `period.week` equals the weekly report's `week`
   (3 on 2026-10-01), and both labels on the home name that week.
2. `topOfNight` is **absent** from the spotlight (football).
3. The home's "Top players of the week" matches the Weekly Report's Top performers for that week, entry for
   entry (SC-004).
4. Every `rookieWatch` entry has `years_exp = 0` in `player`. No team defense appears.
5. For each `PLAYED` trending entry, `points` equals that player's `players_points` for the week **where he
   is rostered** (R2 measured 0 mismatches; this re-checks it through the real code path).
6. Find a trending player with no game row for the week. He reads "Did not play" (or "Bye" when his team
   had no game), and `points` is absent, never `0`.

## V3 — Basketball pre-season (the state that ships first)

Using "Ball Knowers" NBA 2026 (`1339351318115946496`, `pre_draft`), before 2026-10-20:

1. `period` is `null` and `periodUnavailable` is `NO_GAMES_YET`.
2. `seasonStartDate` is `2026-10-20` once one trending fetch has run, and `null` before. The page shows a
   date only when it has one.
3. Top of night and Rookie watch each state a reason. Neither shows an empty list, zeros, or any 2025
   game.
4. Trending still renders, every entry `NO_PERIOD` and "unrostered" (pre-draft: nobody is rostered).

## V4 — Past season does not apply

Open the home of a completed past-season league (NBA 2025 `1229352720222134272`). The spotlight returns
`applies: false, reason: PAST_SEASON`, and the home shows **no** spotlight sections: no empty states, no
headings.

## V5 — Isolation and scoping

1. With no `X-Sleeper-User`, or a non-member: 404.
2. Force the spotlight endpoint to 500 (for example by stopping the DB mid-request, or with a test double).
   Every other block on the league home still renders (FR-012, SC-006).
3. Two consecutive calls with unchanged data are byte-identical in their entry order (FR-014).

## V6 — First real NBA night (owed; cannot run before 2026-10-21)

The night-completeness cutoff (`NIGHT_COMPLETE_HOUR_UTC = 10`) is an **assumption** (research R6). On the
first morning after NBA games:

1. Before 10:00 UTC, with rows present for the slate: `period` is the previous complete night, or `null`,
   and `laterNightInProgress` names the slate's date.
2. After a refresh past 10:00 UTC: `period.date` is that night. Each top-of-night entry's `points` equals the
   same game in that week's Weekly Report `bestNights` (SC-003). Rookie watch lists real 2026 rookies.
3. Record the result, cutoff right or wrong, in the spec's amendment notes in plain words.

## Browser pass

With `draft-sim-web` pointed at the 8081 backend, open an NFL 2026 league home and the NBA 2026 home as a
member. Check light and dark themes and a 375 px width, and screenshot each. Check that:
- each section names its night or week beside the list;
- each value sits beside the player's name;
- the viewer's own players are marked;
- the Sleeper credit is visible on Trending.
