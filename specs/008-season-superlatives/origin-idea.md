# Ongoing superlatives

**Status: parked 2026-09-22.** Not planned, not started. Allan's words: "table for later."

**Status update 2026-09-23: promoted.** This is now spec 008 (`spec.md` in this folder). Kept
unedited below as the origin. Where it and the spec disagree, the spec wins. The biggest
disagreement: the spec's "Amending the idea doc's guess" explains why tallying weekly awards was
the wrong approach.

## The idea

A season-to-date superlatives section that stays current as weeks are scored, as opposed to
the all-time record book (`specs/002-league-history-record-book/`) or a single week's awards
(the Weekly Report). The question it answers: "who is this season's luckiest / unluckiest /
most self-sabotaging manager *so far*?" — a running tally the league chat can argue about
mid-season, not only after it ends.

## What already exists (checked in code 2026-09-22, not run)

The Weekly Report already computes per-week awards in
`backend/src/main/java/com/ballknowers/draftsim/engine/WeeklyReportService.java`:

- `GOT_AWAY_WITH_IT` (`gotAwayWithIt`, ~line 347) — won despite a poor score / lineup
- `DESERVED_BETTER` (`deservedBetter`, ~line 370) — lost despite scoring well
- `ONE_PLAYER_CARRY` (`onePlayerCarry`, ~line 395)
- `SELF_INFLICTED_WOUND` (`selfInflictedWound`, ~line 426) — bench player who should have
  started; needs `roster_week_points.starters` (V18), and emits an `OmittedAward` with a
  reason when starters weren't stored

Schedule luck also exists season-wide in `ExpectedWinsService` (spec 004 US3).

So the cheapest version is probably **aggregation, not new math**: run the per-week award
functions over weeks 1..N of the current season and count / rank who won each one, plus
running extremes (highest and lowest week so far, biggest blowout, closest game, most points
left on the bench). That framing is a guess from reading the code — whether the award
functions are reusable as-is outside `forWeek` hasn't been checked.

## Things to check before building, not assume

- **Pairings coverage.** Spec 002 measured that past seasons had scores but no
  `league_matchup` pairings because of the ingest skip gate; V18's comment says the gate was
  changed alongside it. The current season has pairings; whether blowout/closest-game can
  also be computed for earlier seasons (a "superlatives by season" archive) depends on
  whether that backfill actually ran. Measure it.
- **Basketball.** Stored basketball weekly points are single-game values in this league's
  format (spec 005, measured). A "highest week" superlative means something different in
  each sport; decide whether basketball gets nights (spec 005's split) rather than
  pretending one definition fits both.
- **Thin sample, honestly labelled.** Three weeks in, "luckiest manager" is noise. The
  section should say how many weeks it's based on, beside the title, the same way the
  rest of the app surfaces its caveats.
- **Overlap with the Weekly Report and the record book.** Three places showing "highest
  score" is one too many unless each is clearly scoped (this week / this season / all time).

## Where it would probably live

League analysis page, near the Weekly Report — decided when it's picked up, not now.
