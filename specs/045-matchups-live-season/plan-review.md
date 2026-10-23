# Plan review: spec 045 (adversarial, before tasks)

Reviewed 2026-10-23, cold, against worktree `045-matchups-live-season` at `5512cd7` (origin/main). I read
spec.md, plan.md, research.md, data-model.md, contracts/*.md and quickstart.md, then checked their claims
against `backend/src/**` and `web/src/**`, the local DB on 5433 (read-only), and Sleeper's public API.

Labels: **VERIFIED** means I read the code or ran the query or curl. **REASONED** means inferred, not executed.

The running server on 8080 predates spec 044 (`/lock-ins` returned 404), so I took no live timings of the new
paths. Timing figures below are quoted from 044's own measurements.

## Verified claims (no finding)

- **R8's main-code caller list is complete.** VERIFIED by grepping `\.resolve(` in `src/main`. There are 13
  one-argument sites: `WeeklyReportService:188`, `ExpectedWinsService:361,373`, `PlayoffOddsService:311`,
  `RosterManagementService:140`, `SeasonSuperlativesService:254`, `RecapService:113,205`,
  `AdminRecapController:45,54,66,79` and `LeagueRefreshService:162`. `PlayerStatsService:305,471` already
  name `STORED_GAMES`. `EspnLeagueIngestService:378` is a different `resolve`. The test callers are in F10.
- **Preseason games never reach `player_game`.** VERIFIED. `SleeperPlayerStatsClient.java:56-57` sends
  `season_type=regular`, and the schedule is `/schedule/{sport}/regular/{season}` (line 73). So
  `STORED_GAMES` really does separate "drafted, preseason" from "games played". That matches lessons #40:
  Sleeper reads `in_season` with `leg 1` the day after the draft. In the DB, NBA 2026 `player_game` is 350
  rows, week 1, 2026-10-20..21.
- **`LeagueRow` carries `status` but not `leg`.** VERIFIED. `LIVE_WEEK` has to call
  `leagues.currentLeg(id)` (`LeagueRepository.java:391-400`), and that method already exists.
- **`LockInService.report()` goes on to live calls on `NO_SCORED_WEEKS`.** VERIFIED at
  `LockInService.java:282`: `!MEASURED && !NO_SCORED_WEEKS -> return base`. The live gate also needs
  `!complete` and a stored `currentLeg` (line 285).
- **Live calls' `rosterId` and Sleeper matchups' `roster_id` are the same id space.** VERIFIED. Both come
  from Sleeper's league roster ids, `LeagueRosterSource.rosters` and `/matchups/{leg}`.
- **`allLocks` excludes ride-outs.** VERIFIED. Only `case LOCK` adds to `locks`
  (`LockInService.java:826-866`). `RODE_OUT` and `TIE_NON_LAST` only increment counters.
- **The live roster cache costs one Sleeper read per 60 s per league.** VERIFIED. It is keyed
  `league.id|asOf|playedThrough` (line 423), and the TTL is `liveTtlSeconds` on OK or
  `liveFailureTtlSeconds` on failure (line 428).
- **Sleeper, live, 2026-10-23.** VERIFIED by curl. League `1339351318115946496`: `in_season`, `leg 1`,
  `last_scored_leg` absent, `game_mode 1`, `playoff_week_start 20`. `/matchups/1` returns 12 rows, each
  with `points`, `matchup_id`, `players_points` and `custom_points: null`. `/state/nba` reads week 1,
  `season_type regular`, `season_has_scores true`.
- **Membership scoping on `/lock-ins` for the shown season works.** VERIFIED. `LeagueMembership.canSee` is
  chain-based (`LeagueMembership.java:167-175`), so a member of 2026 can read 2025's id.
- **A 400 has precedent.** VERIFIED: `LeagueHistoryController.java:776-798` uses
  `ResponseEntity.badRequest().body(Map.of("message", ...))`. Ordering is in F18.

## Findings

### F1 — BLOCKER — Recap calls `forWeek`, so after this change one recap request resolves under two rules

**Evidence (VERIFIED, code):**

- `RecapService.view` (`RecapService.java:113-123`) first runs `resolver.resolve(sleeperId)`, which is
  `PLAYED_WEEKS`. The `leagueId` it gets back decides the feature grant, the caps and the `league_recap`
  row. It then calls `weeklyReport.forWeek(sleeperId, week, null)`, which the plan moves to `LIVE_WEEK`.
- `preview` (`:205-208`) does the same, and it only checks `result.available()`, not `weekFinal`.
- `AdminRecapController` grants and reads on the `PLAYED_WEEKS` row.
- On the real NBA route id during opening week, that pairs the 2025 row with a 2026 live result.
  - `view` changes. Today it answers about 2025 week N; with the change it returns `WEEK_NOT_FINAL` for
    2026.
  - `preview` would build a `RecapInput` from a live, partial week with empty rankings and **spend a model
    call on it**.

This is the "two implementations of one rule" class from memory, and it lands inside the very fix meant to
end it. It also contradicts plan/R8's claim that Recap is unchanged, and SC-004's "no other page moves".

**Amend:**

- Make the rule an explicit parameter of `forWeek`, as `forWeek(id, week, user, Rule)`.
  - The controller passes `LIVE_WEEK`.
  - `RecapService.view` and `preview` pass the same `PLAYED_WEEKS` they resolve with, so the row and the
    result can't disagree.
- Defensively, have `preview` and `view` also refuse `result.live()`.
- Add a RecapService test: with the opening-week shape, `view` and `preview` behave exactly as on main.
- List Recap in the plan's touched files.

### F2 — BLOCKER — The page clamps every week to `latestScoredWeek`, so the unstored live week can't be reached mid-season

**Evidence (VERIFIED, `web/src/pages/WeeklyReport.tsx`):**

- `latest = data?.latestScoredWeek` (line 69).
- An effect rewrites `?week=` to `latest` whenever `requestedWeek > latest` (lines 76-82).
- `goToWeek` clamps with `Math.min(latest, …)` (line 88).
- "Next" is disabled at `shown >= latest` (line 118).

Mid-season, the live week L is by definition greater than `latestScoredWeek`. Following plan step 6's new
"Week L in progress →" link loads the live response (`latestScoredWeek` = L−1), and the effect then
immediately replaces the URL with week L−1. FR-002's "the live week is reachable by choosing it" fails
silently. The plan's Design §6 doesn't touch the clamp.

**Amend:**

- Use `maxWeek = Math.max(latestScoredWeek, liveWeek ?? 0)` for the effect, for `goToWeek` and for the
  stepper's disabled and visible state.
- Show the stepper when `maxWeek > 0`.
- Add a Vitest case: mid-season `?week=L` (the live week) stays on L, and "next" from L−1 reaches L.

### F3 — SHOULD-FIX — The home page consumes `weekly-report/0`, so it changes too

**Evidence (VERIFIED):**

- `LeagueHome.tsx:170` fetches `getWeeklyReport(id, 0)`.
- `scoredThisSeason` (`:223-228`) gates the matchup block during `DRAFTED_UNSCORED` on `available`,
  `week > 0`, `season === seasonYear` and `requestedSeason == null`.
- Today the NBA league answers 2025 with `requestedSeason: 2026`, so the block hides. After the change it
  answers 2026 live, so the home shows "Leading/Trailing" for the 2026 opening week.
  - `MatchupBlock` already handles `!weekFinal` (`:525-537`), so this is probably desirable.
  - But it isn't in the spec. SC-004 and the clarification say only Matchups moves.
- `HomeSpotlight.tsx:112` and `PlayerSpotlight.tsx:300-312` also read `weekly-report/0` for football's
  top list. A live NFL opening week (next September) would hand them `topPerformers: []`. That part is
  REASONED.
- Every home load of an in-season Sleeper league now also triggers `LiveWeekSource`.

**Amend:**

- Decide explicitly. Either accept it (recommended) and amend the spec's SC-004 and clarification text to
  name the home matchup block as changing, or gate it.
- Either way, add the home page to the verification bar.
- For football's `topPerformers` on a live response, send `[]` and have the spotlight say "week in
  progress" rather than "nobody".

### F4 — SHOULD-FIX — Sleeper has no timeout, and `LiveWeekSource` sits on the request path of three pages

**Evidence (VERIFIED):**

- `SleeperClient`'s constructor is `RestClient.builder().baseUrl(baseUrl).build()` (`SleeperClient.java:21-23`).
- There is no request factory and no read or connect timeout anywhere: a grep for
  `readTimeout|requestFactory|ClientHttpRequestFactory` finds only `EspnClient`.
- `application.yml` sets no HTTP timeout either.
- After this change, every `forWeek` on an in-season Sleeper league makes two synchronous Sleeper calls
  when the cache is cold. That covers Matchups, the league home (F3), recap polls (F1), and both sports,
  including every in-season NFL league.
- A hung Sleeper connection hangs those pages, which is the spec's "read fails or **times out**" edge case,
  left unhandled.
- Separately, the plan caches `FAILED` "for the TTL too" while claiming to copy 044. But 044 uses a
  shorter, separate `liveFailureTtlSeconds` for failures (`LockInService.java:427-428`).

**Amend:**

- Give `LiveWeekSource` its own `RestClient` with connect and read timeouts of about 2–3 s. Put the
  values in config and label them arbitrary.
- Alternatively, wrap the call in a bounded future.
- Cache a failure for 044's failure TTL, not 60 s.
- Test that a timeout produces the unavailable shape for the current season, not a hang and not 2025.

### F5 — SHOULD-FIX — Three different "current week" rules meet on one card

**Evidence (VERIFIED):**

1. The resolver uses the **stored** `leg`.
2. `LiveWeekSource` uses Sleeper's **live** `leg` (R2).
3. Lock-in calls use a **schedule-derived** week. `LockInService.liveWeek(schedule, playedThrough, legWeek)`
   (`:441-452`, used in `computeLive` at `:458`) takes "the week of the first counted game on or after
   today" and falls back to the stored leg.

These can disagree. Say week 1's last game is Sunday night. On Monday the schedule rule says week 2, while
Sleeper's leg may still read 1 until Sleeper rolls over. Or the stored leg is stale (lessons #47). The page
would then pair week-2 calls with a week-1 live scoreboard. That disagreement is REASONED, because I didn't
observe a rollover. Each rule is VERIFIED in code.

**Amend:**

- The page renders calls only when `lockIns.live.week === data.week`. Otherwise it says in words
  "calls are for week X".
- Add the week equality to SC-007's check.
- Name the three rules in research.md, and say why `LiveWeekSource` uses the live leg while lock-ins use
  the schedule, rather than leaving it implicit.

### F6 — SHOULD-FIX — The second NBA 2026 league is a 404 on Sleeper, so the live path turns its honest empty state into "unavailable"

**Evidence:**

- VERIFIED by curl. `GET /v1/league/1414306784801239040` returns **HTTP 404, body `null`**, and so does
  `/matchups/1`.
- VERIFIED in the DB. Local row 34534 is `in_season`, `leg 1`, with no predecessor, **0 `roster_season`
  rows**, 1 `league_member` and 0 `league_matchup` rows.
- `LIVE_WEEK` qualifies it, because NBA 2026 games are stored and the token is per sport-season.
- `RestClient.retrieve()` throws on a 404, so the plan's `LiveWeekSource` returns `FAILED`. The page would
  say "live scores unavailable from Sleeper right now" forever, where today it says "no week has been
  scored". REASONED from RestClient's default status handler.
- A 200 response with a `null` body would NPE unless it's handled.

**Amend:**

- `LiveWeekSource` treats a 404 or a null league body as "no live week", returns `Optional.empty()`, and
  logs it once. That keeps today's path.
- Only I/O failures and 5xx responses become `FAILED`.
- Add a test with a null body, and one with a 404.
- Note that a live page for a league with no `roster_season` rows labels teams "Roster N".

### F7 — SHOULD-FIX — `weekCovered` can't be computed from what `Computed` holds, and must require MEASURED

**Evidence (VERIFIED):**

- `record Computed(LockInReport report, List<Lock> allLocks, Pool pool)` (`LockInService.java:241`) doesn't
  keep the `weeks` set. `report.weeksCovered` is an `int` count.
- Every refusal, which includes `SHARE_BELOW_THRESHOLD` and `NO_SCORED_WEEKS`, carries an empty `allLocks`
  (`:626-630`).
- A naive "N is in the season's final weeks" would return `weekCovered: true` with `locks: []` for a
  refused league. That reads as "no locks" when the truth is "can't tell", which is exactly Story 4
  scenario 3.
- `report()` also returns early for non-MEASURED statuses (`:282`), so `viewWeek` has to slot in before or
  around that.

**Amend:**

- Add `Set<Integer> weeks` to `Computed`, the result of `scorable(...)`.
- Define `weekCovered = MEASURED && weeks.contains(N)`.
- Update data-model.md, and test the refusal case.

### F8 — SHOULD-FIX — FR-012 says existing `/lock-ins` responses must not change, and the plan changes every one

**Evidence (VERIFIED):**

- `LockInController` serializes the `LockInReport` record directly (`:48`).
- There is no global `default-property-inclusion` in `application.yml`; `@JsonInclude` appears only on
  `recap/RecapInput`. So a new `Boolean weekCovered` component serializes as `"weekCovered": null` on
  every form.
- The contract works around this with "compared with that one key ignored", but FR-012 says the existing
  responses MUST NOT change.

**Amend:** annotate the component `@JsonInclude(JsonInclude.Include.NON_NULL)`, so the no-param and
`player` forms stay byte-identical. Mirror it as `weekCovered?: boolean` in api.ts. Then drop the "ignore
one key" caveat from the contract and verification.

### F9 — SHOULD-FIX — Back-compat for `Result`'s 18-argument constructor, and key placement in `body()`

**Evidence (VERIFIED):**

- The plan only defaults the new fields through the 15-argument convenience constructor. But the
  18-argument canonical constructor is called at `WeeklyReportService.java:155` (`unavailable`),
  `WeeklyReportShapeTest.java:160,168` and `RecapFixtures.java:42`, which reads JSON fixtures.
- `WeeklyReportController.body()` returns early for `!available` (`:61-68`). "On every shape" therefore
  means the three new keys must be put before that return.

**Amend:**

- Add an 18-argument back-compat constructor alongside the 15-argument one.
- Add a shape test that `live`, `liveWeek` and `sleeperLeagueId` are present on the unavailable shape.
- Check that `RecapFixtures` and the golden recap input don't change. `RecapInputBuilder` doesn't read the
  new fields: REASONED.

### F10 — SHOULD-FIX — Test callers of the one-argument overload, and how to rewrite their stubs

**Evidence (VERIFIED by grep in `src/test`):**

- `ScheduleSwapTest.java:160`: `seasons.resolve("L7")`.
- `AdminRecapControllerTest.java:56`: `resolver.resolve(anyString())`.
- `RecapServiceTest.java:290`: `resolve(anyString())`.
- `RecapServiceTest.java:692`: `resolve("L2")`.
- `LeagueSeasonResolverTest.java:86` asserts the overload equals `PLAYED_WEEKS`. Delete it, and replace it
  with a test that the overload is gone, which the compiler already enforces.

**Amend:**

- Rewrite each stub with `eq(Rule.PLAYED_WEEKS)`, never `any()`. An `any()` would hide a caller that picked
  the wrong rule, which is the bug class itself.
- These rewrites are expected, so build agents shouldn't treat them as surprises.

### F11 — NOTE — NFL gets the live path on every in-season league, including weeks that haven't kicked off

**Evidence:** VERIFIED in the DB. NFL 2026 league 3 has `leg 4 / lsl 3`, and league 4 has `leg 7 / lsl 6`.
The current NFL week is never stored, so every NFL Matchups view now calls Sleeper, carries `liveWeek` and
shows the link.

REASONED: once Sleeper's leg advances, and before the week's first kickoff, `/matchups/L` would read 0.0
for every roster. The page would show 0–0 "in progress" games for a week that hasn't started.

**Amend:** set `liveWeek` and the live default only when some roster has `points > 0`, or when the stored
schedule has a started game that week. Otherwise answer "week L hasn't started". Check this against a real
NFL Tuesday before calling it verified.

### F12 — NOTE — Live pairing has to mirror the stored pairing exactly

**Evidence (VERIFIED):** `pairedWithScores` (`LeagueMatchupRepository.java:110-126`) puts the lower
`roster_id` as home (`b.roster_id > a.roster_id`) and orders by week, then `a.roster_id`. It skips null
`matchup_id`s, and a 3-roster `matchup_id` yields several pairs; `ScheduleSwapTest` has a fixture of that
shape.

**Amend:** the live path builds pairs by the same rule, so a card doesn't flip sides when the week becomes
stored. Add a test that live and stored pairings are identical for one week's fixture.

### F13 — NOTE — During the rollover, the week that just ended disappears and records read 0-0

REASONED. After Sleeper's leg reaches 2 and before ingest stores week 1, week 1 isn't stored and isn't
live. In opening week the default becomes live week 2, and every "record before this week" is 0-0, because
`recordAfter` reads only stored pairs. Week 1 is invisible until the refresh runs. That's acceptable, but
R2's "a rollover doesn't mislabel" should also say this. Optionally, have the page note "week 1 results are
still being stored".

### F14 — NOTE — `defaultWeek` is no longer shared with the spotlight

VERIFIED: grep finds no main-code caller of `WeeklyReportService.defaultWeek` outside its own class.
`PlayerSpotlightService` has its own week rule. R3's justification is stale but harmless, so correct the
text.

### F15 — NOTE — `Lock.laterGames` counts later games *played*, not games that were left

VERIFIED. `later = wk.size() - 1 - m.index()`, where `wk` is the player's *played* games that week
(`LockInService.java:805, 834`). A DNP after a lock isn't counted. A live call's `gamesLeft` counts
scheduled games. The page's "(2 left)" on a past lock therefore means something different from "2 left" on
a live call. Word past locks as "2 later games played", consistent with 044's caveat "later game = played
game only".

### F16 — NOTE — There is no "021 harness" script, and the before/after capture needs a protocol

**Evidence (VERIFIED):** nothing under `scripts/` or `specs/021-codebase-cleanup/` automates it. 021's
method is a manual `jq -S` diff, before and after, "without re-ingesting in between"
(`specs/021-codebase-cleanup/quickstart.md:43`).

**Amend:**

- Write the capture as a short script. It must:
  - capture main **twice across a JVM restart**, per lessons #40;
  - turn on-visit and auto refresh off for both runs, or run against a frozen DB copy, since the 2026 data
    is moving;
  - list the ignored keys exactly. That's three on weekly-report, and none on lock-ins if F8 is taken.
- Add Recap (F1) and the home page (F3) to the "other pages" list.

### F17 — NOTE — Timing: 044 measured `/lock-ins` cold at 2.49 s, and the mid-season payload is the full report

**Evidence (VERIFIED, documents):** `specs/044-lock-in-threshold/code-review.md:113-116` measured 2.49 s for
the first `/lock-ins` on 210 on a fresh JVM, and 0.07 s warm. SC-006 is a warm bar, so it holds, but the
page must fetch lock-ins after and independently of the scoreboard, and never block on it.

Mid-season, the no-parameter `/lock-ins` returns full `managers`, `players` and `payoff` just to get
`live.calls`. **Amend:** consider letting `?week=L` return `live` when L is the live week, which also
answers F5's week check in one place. Otherwise accept the payload and record its size in verification.

### F18 — NOTE — Order the player+week 400 after the 404

The 400 must come after the membership 404 in `LockInController`, so that a non-member can't tell an
existing league from a missing one by sending a malformed query. Use the existing
`Map.of("message", ...)` body. That `Map.of` holds a non-null literal, so it's safe.

### F19 — NOTE — R4 contradicts itself

R4's Decision says "No new `sectionsUnavailable` code is needed". Its Rationale then says "one new code is
a copy change". Keep the Decision and fix the Rationale.

### F20 — NOTE — The refresh pill names 2025 while Matchups shows 2026

The plan already flags this (R8). Accept it as clarified, but have the verification note observe the pill
on the live page, so the mismatch is seen rather than assumed harmless.

## Summary for applying

| ID | Severity | One line |
|---|---|---|
| F1 | BLOCKER | Recap resolves `PLAYED_WEEKS`, then calls `forWeek` (`LIVE_WEEK`): mismatched rows, and `preview` spends a model call on a live week. Give `forWeek` a `Rule` parameter. |
| F2 | BLOCKER | `WeeklyReport.tsx` clamps the URL, the stepper and `goToWeek` to `latestScoredWeek`, so the live week is unreachable mid-season. Bound by `max(latestScoredWeek, liveWeek)`. |
| F3 | SHOULD-FIX | The home `MatchupBlock` and football spotlight consume `weekly-report/0`. The home will show the live 2026 matchup. Spec it (amend SC-004) or gate it. |
| F4 | SHOULD-FIX | `SleeperClient` has no timeouts, and `LiveWeekSource` sits on the Matchups, home and recap paths. Add timeouts, and use 044's short failure TTL. |
| F5 | SHOULD-FIX | Three current-week rules (stored leg, live leg, schedule). Render calls only when `live.week == data.week`. |
| F6 | SHOULD-FIX | League 1414306784801239040 is a Sleeper 404 with a null body. Treat 404/null as "no live week", not FAILED. |
| F7 | SHOULD-FIX | `Computed` has no weeks set, and refusals have empty locks. `weekCovered = MEASURED && weeks.contains(N)`. |
| F8 | SHOULD-FIX | `weekCovered: null` changes every `/lock-ins` response, against FR-012. Use `@JsonInclude(NON_NULL)`. |
| F9 | SHOULD-FIX | Add back-compat for `Result`'s 18-argument constructor, and put the new keys before `body()`'s unavailable early return. |
| F10 | SHOULD-FIX | Test callers of the overload: ScheduleSwapTest:160, AdminRecapControllerTest:56, RecapServiceTest:290/692, LeagueSeasonResolverTest:86. Stub with `eq(PLAYED_WEEKS)`. |
| F11 | NOTE | NFL gets the live path everywhere, and a not-yet-kicked-off week reads as 0–0 "in progress". Require a started game. |
| F12 | NOTE | Live pairing must mirror `pairedWithScores` (lower `roster_id` home, same order, 3-roster ids). |
| F13 | NOTE | During rollover, the ended week vanishes and "record before" reads 0-0 until ingest. Document it. |
| F14 | NOTE | `defaultWeek` has no other caller now. R3's spotlight claim is stale. |
| F15 | NOTE | `Lock.laterGames` counts later games played, not games left. Word it accordingly. |
| F16 | NOTE | No 021 harness script exists. Script the capture (twice across a restart, refresh off) and add recap and home. |
| F17 | NOTE | Cold `/lock-ins` measured 2.49 s in 044. Fetch it non-blocking, and consider `?week=L` returning `live`. |
| F18 | NOTE | Put the player+week 400 after the membership 404. |
| F19 | NOTE | R4's decision and rationale contradict each other on `sectionsUnavailable`. |
| F20 | NOTE | The refresh pill names 2025 while Matchups shows 2026. Observe it in verification. |
