# Plan review — league suite, Phase A + power rankings

Adversarial review of `claude/league-suite.md`, done before any code exists, per
this repo's pipeline convention (`AGENTS.md`, "This repo's convention for building
anything feature-sized"). Reviewed 2026-09-07 against `c855159`, the commit that
finished the plan.

**No code was written or changed as part of this review.** Every number below was
measured against the live Sleeper API or read out of the current tree; nothing is
inferred from the plan's own text.

## Verdict: GO, with amendments

The plan's endpoint claims all check out, the auth split is right, and the Phase A
/ Phase B boundary is drawn in the correct place. Two findings are real enough to
change what gets built, and one of them changes what a mode *means*. Four smaller
gaps would otherwise get decided ad hoc mid-build.

---

## Finding 1 — "draft capital" over a current roster is only 70% computable

**Severity: changes the design.** The plan proposes computed mode (a) as "sum of
board value over the roster" and calls it *draft capital*, glossed as "whose roster
looked best on draft day."

Measured against Ball Knowers 2025 — its end-of-season rosters versus the players
actually taken in its own draft:

    127 of 182 rostered players (70%) were drafted in this league's draft
     90 of 120 starters        (75%)

So a quarter to a third of every roster arrived by waiver or trade and has **no
`adp_at_time`** — that column is written per *pick*, not per player. A draft-capital
number computed over a current roster is therefore missing a third of its inputs,
and would silently score a team that streamed a league-winner off waivers as though
the slot were empty.

The finding is that the plan is quietly asking two different questions at once:

| question | data | changes week to week? |
| --- | --- | --- |
| "who drafted best?" | the drafted 15, complete via `adp_at_time` | **no** — it is fixed at the draft |
| "whose roster is most valuable now?" | every rostered player, needs the board | yes, as rosters change |

Only the second is a power ranking. The first is a draft metric, and — worth
noticing before someone plots it — **it would be a flat line on a week-by-week
graph**, since nothing about a completed draft moves after week 1.

**Amendment.** Compute mode (a) from the **board** (`adp_snapshot`'s blend, which
covers every player regardless of how he was acquired), and rename it accordingly —
"market value", not "draft capital". Keep "who drafted best" if it is wanted, but
put it on the *history* page as a per-season draft grade, not on the rankings graph.
The plan's own honesty rule applies to names, not just numbers: a metric called
draft capital that is 30% waiver pickups is mislabelled.

## Finding 2 — matchups return data past the week the league actually scored

**Severity: silent bad data.** The plan says to ingest `players_points` per week
but never says which weeks. The obvious implementations — iterate 1..18, or iterate
until the response is empty — both ingest a week the league did not play.

Measured on Ball Knowers 2025, whose settings read `leg: 17`,
`last_scored_leg: 17`, `playoff_week_start: 15`:

    week 15 -> points 164.66   (real, playoffs)
    week 17 -> points 132.58   (real, the league's last scored week)
    week 18 -> points 129.60   (RETURNED ANYWAY, past last_scored_leg)

Week 18 comes back populated. A loop that stops on an empty response never stops
in the right place, and every downstream average silently includes a phantom week.

**Amendment.** Bound ingest by `settings.last_scored_leg`, read from the league
object the code already fetches. Use `playoff_week_start` to separate regular
season from playoffs — a power ranking that averages in a bye-week zero for the
eight teams not in the playoffs is worse than one that stops at week 14.

## Finding 3 — nothing in the plan knows what week it is, and today that is the whole feature

**Severity: missing prerequisite.** The graph is week-by-week and the default mode
is the computed one, but the plan never says how the app determines the current
week. `SleeperClient` (`SleeperClient.java:25-73`) has no such call.

It exists: `GET /state/nfl` returns `{"week": 1, "season": "2026",
"season_start_date": "2026-09-09", "display_week": 1, ...}`. Verified live.

This is not a rounding error today. **As of this review the 2026 season has not
started** — `season_start_date` is 2026-09-09, two days out — so the realized
computed mode has *zero* weeks of data, and the empty state is the state Allan
will see on the day this ships, not an edge case to handle later. That makes the
plan's "default to draft capital before week 1" note load-bearing rather than a
nicety, and it makes finding 1's rename more urgent: preseason, the *only*
computed mode that can render is the board-value one.

**Amendment.** Add `state(sport)` to `SleeperClient`, and treat "the season hasn't
started" as a first-class rendered state with real copy, not a blank chart.

## Finding 4 — no tie rule

Two teams with an identical computed score is not exotic at 12 teams and one
decimal place. Rank is stored (`power_ranking_entry.rank`), so a tie has to resolve
to *something* before it hits the table, and the graph will draw whatever it is.

**Amendment.** Decide it once, in the service: equal scores share the lower rank
number and the next rank is skipped (standard competition ranking), or break ties
on a stated secondary key. Either is fine; deciding it in two places is not.

## Finding 5 — ingest volume is unbounded as written

Per league chain: (seasons) × (up to 17 weeks) matchup calls, each returning every
roster. Ball Knowers alone is 2 seasons × 17 = 34 calls, and the plan says nothing
about doing this incrementally.

**Amendment.** Store per (league, week) and fetch only weeks not already stored,
bounded by `last_scored_leg` for a complete season and by `/state/nfl`'s `week` for
a live one. The current week is the only one that needs refetching, since its
points are still moving.

## Finding 6 — IR is a separate array, and the real injury case is elsewhere

Acceptance criterion 4 says an injured starter must not silently score as though
he played. Checked: rosters carry a `reserve` array distinct from `players` (5 of
12 rosters in Ball Knowers 2025 had a non-empty one), and reserve players do not
appear in `starters` — so the IR case the criterion imagines mostly cannot happen
through the starters path.

The live case is narrower and still real: a player who *is* in `starters` carrying
`injury_status` Out/Doubtful. That is on the `player` table already
(`V1__init.sql:4-16`).

**Amendment.** Keep the criterion, aim it at `injury_status` on active starters
rather than at IR, and state which way it resolves (exclude, or include with a
flag) rather than leaving it to the implementer.

---

## What checked out, and did not need changing

- **Every endpoint the plan names returns what it says it returns**, unauthenticated:
  `/league/{id}/rosters` (`players` 16, `starters` 10, `owner_id`, `roster_id`),
  `/league/{id}/matchups/{week}` (`players_points`, `starters_points`),
  `winners_bracket`, `transactions/{week}`, and `metadata.latest_league_winner_roster_id`.
- **No Sleeper OAuth.** Their docs say the API performs no authentication. The
  plan's central sequencing argument rests on this and it holds.
- **`roster_season.manager_id` has a source.** `LeagueIngestService.upsertManagers`
  (`LeagueIngestService.java:129-139`) upserts a `manager` row for every user of
  every league in the chain, so a roster's `owner_id` will resolve. Ball Knowers
  2025 has no null `owner_id` and no `co_owners` — but Sleeper models both, and the
  column is nullable in the plan's sketch, which is correct.
- **V5 is genuinely the next free migration.** `V4__mock_from_draft.sql` is still
  the highest on disk.
- **The auth split** (commissioner and computed need none; ballots do) is right,
  and it is what makes the page useful before Phase B exists.
- **The y-axis argument** — that only rank is shared across all three modes,
  because modes 1 and 2 produce orderings with no score behind them — is sound and
  is the correct constraint to design from.

## Amendments, as a list

1. Mode (a) computes from the board, and is named **market value**, not draft
   capital. "Who drafted best" moves to the history page if wanted at all.
2. Bound week ingest by `last_scored_leg`; use `playoff_week_start` to separate
   regular season from playoffs. Never loop until empty.
3. Add `SleeperClient.state(sport)`; render "the season hasn't started" as a real
   state, because it is the state on day one.
4. Pick a tie rule, in the service, once.
5. Ingest incrementally per (league, week); refetch only the current week.
6. Re-aim the injury criterion at `injury_status` on active starters, and state
   how it resolves.

None of these change the phase boundaries or the storage sketch beyond renaming a
`kind` value.
