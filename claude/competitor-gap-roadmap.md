# Roadmap: what to build next from the competitor gap docs

Status: **plan, nothing built.** 2026-10-05, against main @ `114b12d`. Orders the
12 design docs indexed in [competitor-gap-research.md](competitor-gap-research.md).
Like `next-features-roadmap.md`, this is a planning doc, not a verified spec.
Each item's own doc carries its design and acceptance criteria, and each still
goes through this repo's pipeline (plan → adversarial review → build → bug-hunt
review → live verification) before it counts as done.

**Sizes are guesses** (S ≈ a session, M ≈ a few, L ≈ a spec's worth), made from
reading the code, not from building anything. Treat them as guesses.

## How it's ordered

1. **Calendar first.** The NBA season starts **2026-10-20** (first game date in
   Sleeper's 2026 schedule, measured). Basketball is the real target (memory),
   and the schedule grid is worth most in week 1, when teams play 2–4 games.
2. **Then false statements on live pages.** A wrong sentence in production
   outranks a missing feature (see AGENTS.md: honesty about thin data is the
   product's value).
3. **Then realized-data features before projection-bound ones.** They work in
   both sports with data already stored, and they don't depend on a source whose
   accuracy hasn't been measured.
4. **Dependencies.** Some items unblock others (the stored schedule, the NFL
   scoring check, the ownership timeline).

## Phase 0: fix what's wrong now (S, this week)

> **Amended 2026-10-05 (spec 016 planning):** "a false statement on a live page" overstated it.
> Analysis is `sports: ['nfl']` in `web/src/destinations.ts`, so no basketball league links to it.
> The message reaches only a direct URL or an API caller. Still worth fixing (it's false). Spec 016
> also found a second copy in the projection-ingest 400.

| Item | Doc | Why now |
|---|---|---|
| Reword the "no nba equivalent" projection message | [projection-tools.md](projection-tools.md) | `LeagueAnalysisService.java:448-450` tells users something false. Reword it to "not wired up for basketball yet". Wiring real NBA projections is Phase 3. |

## Phase 1: before the NBA season (by 2026-10-20)

> **Status 2026-10-05 (spec 017):** 1.1, 1.2 and 1.3 (basketball) are built and verified locally, but
> not deployed. 1.1 turned out smaller than "S": the schedule was already fetched on every refresh.
> Measuring also overturned the design doc's "82 games per team" (it's 80 today). 1.3's football half
> waits on a measurement (spec 017 T004). See `specs/017-nba-schedule-grid/verification.md`.
>
> **Status 2026-10-07:** 017 is deployed (both services checked), and the NBA 2025/2024 schedules
> are backfilled. The backfill exposed the 2024 All-Star final as two fake teams, now fixed (spec
> 017 V9). T004 missed its Tuesday window and moves to 2026-10-13. Specs 018, 019 and 020 are
> deployed too (prod health reports their data loaded), despite the "not deployed" notes below.

| # | Item | Doc | Size | Unblocks |
|---|---|---|---|---|
| 1.1 | Store the NBA schedule (`sport_schedule`, V27) | [nba-schedule-grid-and-streaming.md](nba-schedule-grid-and-streaming.md) | S | 1.2, 2.3, 4.x "your games this week" |
| 1.2 | Schedule grid + playoff-weeks view | same | M | — |
| 1.3 | NBA next opponent on league home (split it from the projection payload) | [my-team-dashboard.md](my-team-dashboard.md) item 2 | S | — |

Streaming candidates (the grid doc's view 4) wait for 2.3, because ranking them
by recent form means more once minutes are visible beside it.

## Phase 2: realized-data features (October–November)

> **Status 2026-10-06 (spec 019):** 2.3 + 2.4 built and verified locally in
> `specs/019-minutes-streaming`, not deployed. 2.4 was reshaped by measurement: a 4-game week is worth
> about +4% in the measured league, so streaming ranks by season form, not game count.

> **Status 2026-10-06 (spec 018):** 2.1 + 2.2 **built and verified locally** in
> `specs/018-draft-grades` (worktree `018-draft-grades`), not deployed. 2.1 is closed by the Java
> parity test (0 mismatches). The 2026-10-05 planning status follows:
> **(2026-10-05)** 2.1 + 2.2 planned as `specs/018-draft-grades`, nothing
> built. 2.1 already passes in SQL (5,334 / 5,334 starter-weeks, research R1), and the Java
> check is spec 018 US1. **Amended:** "NFL drafts are five weeks old" is three *scored*
> weeks (NFL 2026 final weeks = 3), so NFL grades read "early" until week 4 is final. 2.3–2.6
> are later specs.

| # | Item | Doc | Size | Notes |
|---|---|---|---|---|
| 2.1 | **NFL scoring check:** `GameScoringService` vs `players_points` | [draft-grades.md](draft-grades.md) acceptance #1 | S | Gate for 2.2 and 3.1's NFL path. If it fails, fix that before anything reads NFL `player_game`. |
| 2.2 | Draft grades: how each pick actually scored | [draft-grades.md](draft-grades.md) | M | NFL drafts are five weeks old, and NBA 2025 has a full season to test against. |
| 2.3 | NBA minutes and role trends | [nba-minutes-trends.md](nba-minutes-trends.md) | S–M | Data already stored. Feeds streaming. |
| 2.4 | Streaming candidates | [nba-schedule-grid-and-streaming.md](nba-schedule-grid-and-streaming.md) view 4 | S | Needs 1.1 + 2.3. |
| 2.5 | Ownership timeline → trade verdicts | [trade-grades-and-trade-tree.md](trade-grades-and-trade-tree.md) | M | Most useful during the season, before NFL trade deadlines. Trade tree and CSV follow. |
| 2.6 | Schedule swap matrix | [schedule-swap.md](schedule-swap.md) | S | Uses Expected Wins' existing `Game` list. Can slot anywhere. |

## Phase 3: projections, behind one decision (November)

**Decision needed from Allan before starting:** whether to measure RotoWire
projection accuracy against these leagues, and whether the no-backtesting
decision covers measuring an *input*. Both are listed in projection-tools.md
"Open questions". The order below assumes yes to building the foundation either
way.

| # | Item | Size | Notes |
|---|---|---|---|
| 3.1 | Foundation: store projection stat lines both sports, one `projectedWeek` | M | Needs 2.1. Replaces the NFL `pts_ppr` column pick with exact league scoring, but only after the two are shown to match. |
| 3.2 | Analysis page for NBA (rate my team) | S | Reverses the Phase 0 rewording with the real thing. |
| 3.3 | Start/sit and player comparison | M | Thin views over 3.1. |
| 3.4 | Waiver assistant, then trade analyzer | M | Both use lineup gain, not raw points. |
| 3.5 | Trade finder | L | Last. The most likely to produce confident-looking nonsense. |

## Phase 4: history and presentation (December–January)

| # | Item | Doc | Size | Notes |
|---|---|---|---|---|
| 4.1 | Hall of fame + rivalries | [hall-of-fame-and-rivalries.md](hall-of-fame-and-rivalries.md) | M | Needs the regular-season/playoff split in `HeadToHeadService`. |
| 4.2 | Cross-league summary on home | [my-team-dashboard.md](my-team-dashboard.md) item 1 | S | Measure the client-side fan-out first. |
| 4.3 | Season Wrapped | [season-wrapped.md](season-wrapped.md) | M | After 2.2 and 4.1, since its slides draw on them. Buildable against 2025 any time, and it should be ready before the NFL 2026 season ends in early January. |

## Phase 5: optional, needs a spend decision

> **Status 2026-10-06 (spec 020):** Allan made the spend decision: Haiku 4.5, recap only, behind a
> flag as a future premium feature.
> - **Built** on branch `020-ai-weekly-recap`; verified with no key; **not verified with a live
>   model**.
> - **Cost:** the "~$0.11 per league-week" below was about 10× high (measured input size).
> - **Ordering:** "it reads Phase 2–4 data" holds only for the historian. The recap reads only the
>   existing weekly report, so it didn't have to wait.

| Item | Doc | Notes |
|---|---|---|
| AI weekly recap, then historian | [ai-recap-and-historian.md](ai-recap-and-historian.md) | Adds a paid API dependency. The cost estimate (~$0.11 per league-week) is unmeasured. Its grounding test is the bar. It reads Phase 2–4 data, so it goes last. |

## Parked: no league needs them

| Item | Doc | Revisit when |
|---|---|---|
| Keeper calculator | [keeper-calculator.md](keeper-calculator.md) | A league turns on keepers (check `is_keeper` on picks). |
| Auction drafts | [auction-drafts.md](auction-drafts.md) | A league switches to auction. |

## Critical path, in one line

Phase 0 reword → 1.1 schedule → 1.2 grid (by 10-20) → 2.1 NFL scoring check →
2.2 draft grades → 3.1 projection foundation → 4.3 Wrapped.
