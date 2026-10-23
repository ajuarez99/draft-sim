# Implementation Plan: Matchups shows the live season, then lock-ins on Matchups

**Branch**: `045-matchups-live-season` | **Date**: 2026-10-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/045-matchups-live-season/spec.md` (clarified 2026-10-23)

## Summary

Matchups for the NBA league shows 2025 during 2026's live week 1. That's because the defaulted
one-argument `LeagueSeasonResolver.resolve(String)` means "has a stored scored week", and Sleeper doesn't
let a week be stored until it ends (`last_scored_leg` absent, measured). The fix has three parts. First,
remove the defaulted overload and make every caller name its rule. Every caller except Matchups names
today's `PLAYED_WEEKS`, which changes nothing. Second, add a `LIVE_WEEK` rule for Matchups. Third, serve
the unstored live week from Sleeper's `/matchups/{leg}`, read live and cached 60 s, labelled in
progress, with no rankings or awards. Then lock-ins: the page pairs 044's existing live calls to each
matchup, and a new `?week=` filter on `/lock-ins` lists a past week's provable locks per roster. There's
no migration. See [research.md](research.md) R1–R9 for each decision.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5), TypeScript (React + Vite)

**Primary Dependencies**: existing `SleeperClient` (`league`, `matchups`), `LockInService`, `ScoredWeeks`,
`SeasonBoxCache`

**Storage**: Postgres. Reads only. No migration (highest is V38, untouched).

**Testing**: JUnit 5 (unit + `*IT` against the local Postgres on 5433), Vitest for `WeeklyReport.test.tsx`,
and the live check against the real NBA league

**Target Platform**: Railway (backend + web services), local dev on Windows

**Project Type**: web application (`backend/`, `web/`)

**Performance Goals**: Matchups with live calls loads in under 2 s warm (SC-006). It makes at most two
Sleeper calls per league per 60 s for scores (league + matchups) and one roster read per 60 s for calls
(044's cache).

**Constraints**: past-season and non-Matchups responses must be identical (SC-003/SC-004). There is no
background polling (out of scope).

**Scale/Scope**: about a dozen leagues and ~12 users. One backend service change set, one page.

## Constitution Check

| Rule | Status |
|---|---|
| Migrations append-only | Pass. No migration. |
| `api.ts` mirrors the Java records in the same change | Planned: `WeeklyReport` gains `live`, `liveWeek`, `sleeperLeagueId`. `LockInReport` gains `weekCovered`. `fetchLockIns` gains `week`. |
| Never retune a hand-set constant | Pass. The 60 s TTL is 044's existing value, reused and labelled as arbitrary. No thresholds touched. |
| `Map.of` with nullable values | Watch: `liveWeek`, `sleeperLeagueId` (ESPN?) and `weekCovered` are nullable. The controller already builds a `LinkedHashMap`. |
| Ask before committing | Commit, push and PR wait for Allan. |
| Planning doc ≠ verified spec | Applied: HANDOFF's list of affected pages was checked against the code and corrected (spec Context). |

Re-checked after Phase 1 design: no change.

## Project Structure

### Documentation (this feature)

```text
specs/045-matchups-live-season/
├── spec.md, plan.md, research.md, data-model.md, quickstart.md
├── contracts/weekly-report.md, contracts/lock-ins-week.md
├── checklists/requirements.md
├── plan-review.md       # adversarial review (next stage)
└── tasks.md             # /speckit-tasks
```

### Source Code

```text
backend/src/main/java/com/ballknowers/draftsim/
├── engine/LeagueSeasonResolver.java      # drop resolve(String); add Rule.LIVE_WEEK
├── engine/LiveWeekSource.java            # NEW: Sleeper league + matchups for the current leg, 60 s TTL
├── engine/WeeklyReportService.java       # LIVE_WEEK resolve; live path; Result + live, liveWeek, sleeperLeagueId
├── api/WeeklyReportController.java       # serialize the three new fields
├── engine/LockInService.java             # view for a week: locks of week N, weekCovered
├── api/LockInController.java             # ?week=
├── engine/{ExpectedWins,PlayoffOdds,RosterManagement,SeasonSuperlatives}Service.java  # name PLAYED_WEEKS
├── recap/{RecapService,AdminRecapController}.java                                     # name PLAYED_WEEKS
└── refresh/LeagueRefreshService.java                                                   # name PLAYED_WEEKS
backend/src/test/java/com/ballknowers/draftsim/
├── engine/LeagueSeasonResolverTest.java  # LIVE_WEEK cases
├── engine/LiveWeekSourceTest.java        # NEW
├── engine/WeeklyReportServiceTest.java   # opening week, mid-season, live failure (FR-008)
├── api/WeeklyReportShapeTest.java        # new fields on every shape
└── engine/LockIn*Test / api IT           # ?week=
web/src/
├── api.ts                                # mirrors
├── pages/WeeklyReport.tsx                # live banner, liveWeek link, per-matchup calls and locks
└── pages/WeeklyReport.test.tsx
```

**Structure Decision**: the existing web-app layout. One new engine class (`LiveWeekSource`). Everything else
edits files in place.

## Design

1. **Resolver (FR-006/007, R1, R8).** Delete `resolve(String)`. Add `Rule.LIVE_WEEK`. It qualifies when
   `PLAYED_WEEKS` holds, or when the season is not complete, `status` is `in_season`, stored `leg` ≥ 1, and
   `STORED_GAMES` holds. The `qualifies` switch gains the case. Rename the 13 call sites (R8). The compiler
   is the check for SC-005.
2. **`LiveWeekSource` (R2).** `Optional<Live> current(LeagueRow)`. `Live` is `(int week, Status status,
   List<Row> rows)`, where `Row` is `(rosterId, Integer matchupId, double points)` and `Status` is `OK` or
   `FAILED`. Empty for ESPN, complete, or leg < 1. It reads `sleeper.league` and then `sleeper.matchups`,
   with one 60 s TTL entry per sleeper id (`Clock`-driven, so it's testable), copying 044's `liveCache`
   shape. A `RestClientException` gives `FAILED` and is cached for the TTL too, so a Sleeper outage isn't
   hammered.
3. **`WeeklyReportService.forWeek` (R3, R4).** Resolve with `LIVE_WEEK`. Compute `ScoredWeeks` as today.
   Get `live = liveWeeks.current(league)`. Let `L` be `live`'s week when it isn't stored. If
   (`requestedWeek <= 0` and `scored.latestStored() == 0` and `L` exists) or `requestedWeek == L`, go to
   `liveResult(...)`. Otherwise use today's path, with `liveWeek = L` added to the result.
   `defaultWeek(...)` is untouched. `liveResult` builds names, avatars, `isMe`, and records before the
   week from the same helpers, then the matchups from the live rows, and sends empty rankings and awards.
   On `FAILED` it returns `Result.unavailable("live scores unavailable from Sleeper right now", …)`.
4. **`Result`.** Add `boolean live`, `Integer liveWeek` and `String sleeperLeagueId`, with defaults through
   the convenience constructor (false, null, null) so existing shape tests keep compiling. The controller
   puts all three on every shape.
5. **`/lock-ins?week=N` (R7).** The controller takes `week` as a nullable `Integer`. When `week` is set and
   `player` is blank, `report()` uses `viewWeek(c, N)`. That gives `locks` = `allLocks` with `week == N`,
   empty manager and player summaries and payoff, `weekCovered` = whether N is in the computed weeks, and
   `live` null. Every other form passes `weekCovered` null. `LockInReport` gains `Boolean weekCovered` as
   its last component.
6. **Page (Stories 1, 3, 4).**
   - When `live` is true: an "in progress, live from Sleeper" label on the scoreboard. Rankings and awards
     are replaced by one note.
   - The "Week N in progress →" link also shows when `liveWeek > week`.
   - For an NBA response whose week isn't final, fetch `/lock-ins` for `sleeperLeagueId` once and render
     each side's calls under its score. Each call shows the call chip, the player, the latest game's
     points, the margin with its baseline kind, games left, and the bar.
   - For a final NBA week, fetch `/lock-ins?week=N` once and list each side's locks, for example
     "Mitchell +54% stand-in (2 left)", or "no locks". If `weekCovered` is false, show "can't tell for this
     week".
   - Hidden on refusal statuses (`NOT_SLEEPER`, `NOT_NBA`, `GAME_MODE`). Every other non-OK status is shown
     in words.
   - One "Locks are inferred; bars are learned from past weeks" caption per lock-ins area.
   - The football page renders no lock-in content.

## Amended after plan review (2026-10-23): this section wins over the Design section above

[plan-review.md](plan-review.md) found 2 BLOCKERs, 8 SHOULD-FIX and 10 NOTEs. Each one is taken in below.
Where this section and "Design" disagree, this section is right.

- **A1 (F1, BLOCKER): Recap stays on `PLAYED_WEEKS` end to end.**
  - `forWeek` gains a `LeagueSeasonResolver.Rule` parameter. The controller passes `LIVE_WEEK`, and
    `RecapService` / `AdminRecapController` pass `PLAYED_WEEKS`.
  - So one request never resolves on two rules.
  - The live path is only reachable under `LIVE_WEEK`. Recap also refuses any `live` result as
    defence in depth (FR-015).
- **A2 (F2, BLOCKER): the page's week bounds include the live week.**
  - `WeeklyReport.tsx`'s URL clamp (`:76-82`), `goToWeek` (`:88`) and the next button (`:118`) bound by
    `max(latestScoredWeek, liveWeek ?? 0)`.
  - New Vitest case: mid-season with `liveWeek = latest + 1`, the "in progress" link lands on the live
    week (FR-016).
- **A3 (F3): the home matchup block and the football spotlight follow Matchups.**
  - This is accepted, and the spec's SC-004 is amended.
  - The home page goes into the verification bar.
  - The football spotlight, given a `live` response, says "week in progress" instead of rendering an empty
    top list.
- **A4 (F4): the live read is bounded.**
  - `LiveWeekSource` runs both Sleeper calls inside a `CompletableFuture` on a virtual thread, with
    `orTimeout(draftsim.live-week.timeout-ms)`. The default is 2500 ms, labelled arbitrary in config.
  - It doesn't change `SleeperClient`, whose ingest callers rely on today's unbounded behaviour.
  - A failure or timeout is cached for 044's `liveFailureTtlSeconds`, not 60 s. Success is cached for
    `liveTtlSeconds` (60).
  - Test: a stubbed slow client produces the current-season unavailable shape.
- **A5 (F5): one week on a card.**
  - Calls render only when `lockIns.live.week === data.week`. Otherwise the calls area says the
    lock-in week differs.
  - The three "current week" sources stay as they are (stored leg, live leg, schedule week). That's
    documented, not unified, in this spec.
- **A6 (F6): 404 or null from Sleeper means no live week (FR-018).**
  - `LiveWeekSource` maps a 404, or a null league body, to `empty` (not `FAILED`), cached for the success
    TTL.
  - The resolver can still pick such a season under `LIVE_WEEK` from stored state. The page then answers
    "no week has been scored" for it, as today. Measured case: 1414306784801239040.
- **A7 (F7): `weekCovered` is defined.** `Computed` gains the `weeks` set.
  `weekCovered = STATUS_MEASURED.equals(status) && weeks.contains(N)`. A refusal gives false.
- **A8 (F8): byte-identical `/lock-ins`.** `weekCovered` is annotated `@JsonInclude(NON_NULL)`, so the
  no-week forms don't carry the key at all. In `api.ts` it's `weekCovered?: boolean`. The diff ignores
  nothing on `/lock-ins`.
- **A9 (F9): `Result` construction.**
  - Add a back-compat constructor matching today's 18-argument canonical one, defaulting `live=false`,
    `liveWeek=null`, `sleeperLeagueId=null`. Existing callers keep compiling: `WeeklyReportService:155`,
    `WeeklyReportShapeTest:160,168`, `RecapFixtures:42`.
  - `body()` puts the three new keys before its `!available` early return.
- **A10 (F10): test callers.**
  - Update `ScheduleSwapTest:160`, `AdminRecapControllerTest:56`, `RecapServiceTest:290,692`. Stub with
    `eq(Rule.PLAYED_WEEKS)`, never `any()`, so a wrong rule fails the test.
  - Delete the default-overload test at `LeagueSeasonResolverTest:86`.
- **A11 (F11): a week is live only once started (FR-017).**
  - `liveWeek` is set, and the live default used, only when some live row has `points > 0`, or when
    `player_game` has a row dated in that week.
  - Otherwise the response for an explicit request is unavailable with reason "week L hasn't started".
  - This is NFL-relevant: the current NFL week is never stored. It has to be checked on a real NFL Tuesday
    or Wednesday before it can be called verified.
- **A12 (F12): live pairing uses the same rule as stored pairing.**
  - Pairing mirrors `LeagueMatchupRepository.pairedWithScores`: the lower `roster_id` is home, ordered by
    `a.roster_id`, null `matchup_id` skipped, and a 3-roster `matchup_id` yields the same pairs.
  - It's extracted into one shared static helper, used by both paths, so there aren't two implementations
    of one rule.
- **A13 (F13): the rollover window is accepted and stated.** See the spec's edge case.
- **A14 (F15): past-lock wording.** `Lock.laterGames` counts later games **played**. The card says
  "(2 later games)", not "(2 left)". The spec's example is reworded.
- **A15 (F16): before-and-after capture is scripted.**
  - `scripts/045/capture.sh <base-url> <out-dir>`, run against a main-built and a branch-built server, with
    refresh off. Each is run twice across a JVM restart, to see what's nondeterministic before diffing.
  - It covers every stored `weekly-report` week of NBA 2024/2025 and NFL 2025/2026 (non-live weeks), the
    other resolver pages, recap view, and the home's `weekly-report/0` on a complete league.
  - The diff is `jq -S`, ignoring exactly `live`, `liveWeek` and `sleeperLeagueId` on `weekly-report`, and
    nothing elsewhere.
- **A16 (F17): lock-ins never block the scores.**
  - The page renders matchups first, then fetches `/lock-ins` in a separate effect.
  - The cold-cache cost (2.49 s measured by 044 on league 210) is shown as a loading state on the calls
    only. SC-006's 2 s applies to the scores.
- **A17 (F18):** the player+week 400 comes after the membership 404, with the existing
  `Map.of("message", ...)` body.
- **A18 (F20):** the refresh pill naming 2025 is accepted (clarified), and is noted in verification.md when
  observed.

## Verification bar (feeds `verification.md`)

- Live, on the real NBA league while 2026 week 1 or 2 is live:
  - Matchups shows 2026, the live week, and 12 rosters / 6 matchups.
  - Each score equals Sleeper's `/matchups/{leg}` at read time, within 60 s.
  - There's no fallback note, and no rankings or awards.
  - Each matchup's calls equal `/lock-ins` `live.calls` for its two rosters.
- Identical JSON before and after (021 harness way): every stored week of NBA 2024/2025, NFL 2025 and
  NFL 2026, plus Expected wins, Playoff odds, Roster management, Superlatives and refresh status for both
  real leagues.
- `/lock-ins?week=` on a 2025 week: each listed lock equals 044's provable locks for that week.
  `/lock-ins` and `/lock-ins?player=` are byte-identical to main.
- The home page (A3) and the refresh pill (A18) are observed on the live league.
- Backend suite: check the skip count, not just green (memory: suite skips ITs silently). `npx tsc -b`,
  `npm run build`, and Vitest.

## Complexity Tracking

None. There are no constitution violations.
