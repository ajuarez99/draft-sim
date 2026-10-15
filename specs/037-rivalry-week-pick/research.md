# Research: rivalry week pick under the two-meeting cap (spec 037)

Everything marked **measured** was run on 2026-10-15 against the shared local DB (`draftsim-pg`,
port 5433, read-only queries). Everything else is a decision or a code reading, and says so.

## R1 — Is the rest of the season's schedule actually stored? (measured)

Yes. `league_matchup` holds every regular-season week up front, not just played weeks:

| League (id) | Sport | Teams | `leg` | `playoff_week_start` | Max stored week |
|---|---|---|---|---|---|
| fantasy😍 (3) | NFL | 14 | 4 | 15 | 14 |
| "(Foot) Ball Knowers" (4) | NFL | 12 | 6 | 15 | 14 |
| NBA "Ball Knowers" (210) | NBA | 12 | 1 | 20 | 19 |
| West Coast (9465) | NFL | 14 | 4 | 15 | 14 |

Max stored week is always `playoff_week_start − 1`, so the whole regular season is there.
`LeagueMatchupRepository.scheduledPairs` (store/LeagueMatchupRepository.java:160) already
returns it, and `RivalryService` already loads it (RivalryService.java:218). **No new query
and no new ingest.**

## R2 — How often does the cap bite? (measured)

The table shows regular-season meetings per manager pair in the published 2026 schedule:

| League | Pairs meeting once | Pairs meeting twice | Pairs meeting 3+ |
|---|---|---|---|
| fantasy😍 | 84 | 7 | 0 |
| "(Foot) Ball Knowers" | 48 | 18 | 0 |
| NBA "Ball Knowers" | 18 | **48** | 0 |
| West Coast | 84 | 7 | 0 |

- **NBA is where the rule matters.** 19 regular-season weeks against 11 opponents means 48 of
  66 pairs already meet twice. A history-best pair that's one of those 48 can only be a
  rivalry pair in one of its own two scheduled weeks.
- **Football is lightly affected.** Most pairs meet once, so the cap rules out only a few
  pairs.
- **No league starts above the cap**, so the "schedule already exceeds the cap" edge case is
  real but not present in our data. It gets a unit test and a note, not live verification.

What hasn't been measured is how many of the **history-best** pairs fall in the "twice"
column. That needs the history scores, which only the running service computes. It's
quickstart §2's first measurement. **Prediction (a guess, labelled):** NBA loses at least one
history-best pair in most weeks, and football loses none in most weeks.

> **Corrected after plan review (F3, 2026-10-15).** The football half of that guess was
> **wrong**. The reviewer re-implemented the picker in Python against live payloads from 8081:
>
> - History-best pairs already meeting twice: "(Foot) Ball Knowers" 5 of 6, NBA 5 of 6,
>   West Coast 3 of 7.
> - "(Foot) Ball Knowers" loses 4–5 history-best pairs in every eligible week.
> - West Coast loses 3 pairs in 9 of its 10 eligible weeks.
>
> **Why:** the history score counts this season's played games, and Sleeper's schedules wrap
> around (late weeks replay early ones). So the history-best pairs are mostly the pairs
> scheduled to meet twice.
>
> These are re-implementation numbers, not the Java path. T024 re-measures them on the built
> code. The cap was not changed in response.

## R3 — Allowed-pair rule (decision)

Per eligible week W and pair (a, b):

- `reg(a,b)` = regular-season weeks (all of them, played or not, `week < playoff_week_start`)
  where a and b are scheduled against each other.
- `inW(a,b)` = 1 if a and b are scheduled against each other in W, else 0.
- If paired in W, their regular-season count becomes `reg − inW + 1`. **Allowed iff
  `reg − inW + 1 ≤ cap`.**

Every pair not in the rivalry set ends up with `reg − inW ≤ reg`. Its count never goes up, so
the cap only has to be checked on the pairs chosen. This is why the cap can be enforced
inside the matching as a per-week edge filter, rather than as a check over the whole schedule.

A pair that is scheduled in W and meets twice in total is allowed (`2 − 1 + 1 = 2`). That's
spec US2 scenario 2, and it gets its own test, because an off-by-one there is exactly the
"well-formed and pointed backwards" class.

Median games aren't rows in `scheduledPairs` (median is a flag on the format, not a pairing),
so they never enter `reg`. Code reading, not measured: to be confirmed by the plan review.

## R4 — Week and pairings chosen together (decision)

For each eligible week, run spec 034's exact max-weight matching with disallowed pairs
removed. The week's value is that matching's total under the **unchanged** spec 034 score.
The recommended week has the highest total. Ties go to the earliest week, using the same
`EPS = 1e-9` rule the matching already uses.

- **Alternative rejected: keep the history-best set fixed and find a week it fits.** In the
  NBA (R2) there may be no such week at all. The answer would then be "no week", when a
  slightly different set fits easily.
- **Alternative rejected: penalize instead of forbid.** Allan made it a rule ("should not play
  someone else more than twice"), not a preference. A penalty weight would be a new arbitrary
  constant that could be tuned to let a third meeting through.
- **Alternative rejected: greedy repair** (take the history-best set and swap out offenders).
  It's order-sensitive and not optimal. The exact DP is already there and cheap (R6).

## R5 — Eligible weeks (decision)

A week W is eligible iff `W > max(leg, lastScoredWeek)` and `W < playoff_week_start`.

- **`leg` itself is excluded.** Sleeper's `leg` is the current week, which may be under way.
  The app can't tell a not-yet-kicked-off current week from a half-played one. Both
  mistakes are possible, and excluding it is the honest one. Cost, stated: before the NBA's
  tip-off (`leg = 1`), week 1 isn't offered.
- **`leg` is null:** use `lastScoredWeek` alone.
- **No usable playoff start** (`fmt` missing, or `playoff_week_start < 2`): the regular season's
  end is unknown, so status `NO_SEASON_FORMAT`. The page says it can't tell where the regular
  season ends. It doesn't guess.
- **A past season** (the page is season-scoped): every week is scored, so this falls into
  `NO_ELIGIBLE_WEEK` naturally, with no special case.

## R6 — Cost (code reading + arithmetic, not measured)

The DP is `O(2^n · n)` per week. At the configured `max-managers: 16` that's ~1M steps per
week, and 18 weeks (NBA) is ~19M simple steps. In practice it's 12–14 managers (4k–16k
states), so it's negligible next to the page's existing DB reads. Spec 034 measured 25–250 ms
per page load. The quickstart checks the new number on the same leagues instead of assuming it.

## R7 — The tie that will usually decide the week (risk, flagged for plan review)

In football, R2 suggests the history-best set will fit **most** remaining weeks. All of
those weeks tie at the unconstrained total, so FR-005's "earliest week" picks the **next**
week almost every time. That's correct by the spec, but it may be too soon for a commissioner
to set up by hand.

The plan doesn't invent a second ranking term, because Allan said the cap is the whole rule.
It mitigates in two ways:

1. **The reason says it's a tie** ("the earliest of 7 weeks where every best pairing fits").
2. **Every runner-up week ships with its own pairings**, so another week is one click away,
   not a recompute.

The adversarial review should decide whether that's enough or whether to put it back to
Allan.

## R8 — Where the cap lives (decision)

The cap goes in `config/weights.yml` under `draftsim.rivalry` as `max-regular-season-meetings: 2`,
bound by `RivalryProperties` (range 1–5). It's labelled **a league rule from Allan
(2026-10-15), not a fitted number**.

`loaded()` requires every field, so a weights.yml without it answers `CONFIG_MISSING` for the
whole rivalry week. The weights.yml change ships in the **same commit** as the property, and
the Dockerfile already copies `config/weights.yml` into the image (Dockerfile:19), so prod
gets both together. `RivalryPropertiesTest` covers missing and out-of-range values.
