# Spec 045: bug-hunting code review

**Date**: 2026-10-23. **Tree**: worktree `045-matchups-live-season`, uncommitted, on origin/main 5512cd7.
**Scope**: the full `git diff` plus the untracked files (LiveWeekSource, MatchupLockIns, the new tests, scripts/045).

PROVEN means I ran something that shows it. READ means I traced the code path and did not execute it. The
throwaway probes (one JUnit class, one Vitest file) were deleted afterwards. The targeted backend suites
(`WeeklyReportServiceLiveWeekTest`, `LiveWeekSourceTest`, `LockInWeekViewTest`, `LeagueMatchupPairingTest`,
`LeagueSeasonResolverTest`, `WeeklyReportShapeTest`) pass on the tree as it is.

## Bugs

### B1: Matchups still declares a winner on a live week (FR-003, US1 AS3). PROVEN

`web/src/pages/WeeklyReport.tsx:328` (Scoreboard) and `:479-480` (Game) decide Won/Lost/Tied and the `wr-won`
highlight from the points alone. They never look at `weekFinal` or `live`.

- **Probe**: a Vitest render of a `live: true, weekFinal: false` response, with the reader on the home side at
  120.5 to 99. The scoreboard text was `MMe(0-0)120.50TThem(0-0)99.00Won by 21.50`.
- **Why it matters**: on opening week this is now the page's **default** view. The "In progress, live from
  Sleeper" banner sits right above it.
- **Already done right elsewhere**: the home page's `MatchupBlock` (`LeagueHome.tsx:527-537`) says
  Leading/Trailing/Level until the week is final. The same wrong word was already shown for a stored partial
  week before this change, but FR-003 now requires the fix, and no 045 test asserts the absence of "Won" on a
  live week.
- **Fix**:
  - Pass `final = data.weekFinal` into `Scoreboard` and `Game`.
  - When the week isn't final, use Leading/Trailing/Level as `MatchupBlock` does, and drop `wr-won`.
  - Add a Vitest case: on a live week, no "Won" or "Lost" text, and no `.wr-won`.

### B2: mid-season, an explicit request for the live week whose answer is a refusal gets clamped away silently. PROVEN

`WeeklyReport.tsx:81-87` with `WeeklyReportService.java:468` and `:479`. Both refusals return `liveWeek = null`
by design (Sleeper's week is unknown, or it hasn't started). So the page's `top` falls back to
`latestScoredWeek`, and the clamp effect rewrites `?week=L` to `?week=L-1`.

- **Probe**: Vitest at `?week=4`. The server answered `available: false`, reason "live scores unavailable from
  Sleeper right now", `latestScoredWeek: 3`, `liveWeek: null`.
- **Result**: the URL became `?week=3` and the page fetched `[["L1",4],["L1",3]]`. The reason text was **not**
  on screen, and the reader saw week 3's final with no explanation.
- **Spec conflict**: this breaks the spec's edge case ("says live scores are unavailable and links to the
  latest stored week").
- **Also affected**: the A11 "week L hasn't started" answer is never seen mid-season. On an NFL Tuesday,
  `?week=7` is clamped to 6. It is visible only at opening week, where `top` is 0 and no clamp runs.
- **Reach**: this needs a bookmark, a reload, or a stale tab during an outage. The "in progress" link only
  appears when `liveWeek` is known.
- **Fix** (either of these):
  - Don't clamp while `data.week === requestedWeek && !data.available`. The server has already answered for
    that week, and its reason should stand.
  - Or have both refusals carry the week they refused as a separate field (e.g. `refusedWeek`) and include it
    in `top`.

## Should fix

### S1: a hung Sleeper call is never cancelled, so timed-out reads pile up blocked virtual threads. PROVEN

`LiveWeekSource.java:122-126`. `CompletableFuture.supplyAsync(...).orTimeout(...)` only completes the
*future* exceptionally. The supplier keeps running.

- **The HTTP client has no read timeout**: `SleeperClient` builds `RestClient.builder()` with no request
  factory. With no Apache, Jetty or Reactor client on the classpath (`build.gradle.kts`), that resolves to the
  JDK `HttpClient`, which has no read timeout by default.
- **So a half-open connection blocks that virtual thread** (and its connection) until TCP gives up.
- **Probe**: a stub `league()` that blocks forever, a 200 ms timeout, and the clock advanced past the 15 s
  failure TTL between calls. Five `current()` calls left **5 threads still blocked, 0 interrupted**.
- **Rate of the leak**: during a Sleeper hang, one more stuck thread per league per failure TTL, plus one per
  concurrent miss (S2). That's about 240 per hour per live league at 15 s, against the 1 GB heap spec 041 just
  tuned.
- **Fix**: give `LiveWeekSource` its own `RestClient` with
  `JdkClientHttpRequestFactory.setReadTimeout(timeoutMs)` and an `HttpClient` connect timeout. That bounds the
  socket itself, and `orTimeout` becomes a backstop. Alternatively, `executor.submit` plus `future.cancel(true)`
  on timeout: interrupting a thread blocked in JDK `HttpClient.send` aborts the exchange. Either way, add a
  test that the blocked thread ends.

### S2: concurrent cache misses each call Sleeper (thundering herd). PROVEN

`LiveWeekSource.java:106-118`: a check, then the read, then a put, with no per-key in-flight guard.

- **Probe**: 12 concurrent cold `current()` calls made **12** `league()` calls.
- **Who triggers it**: Matchups, the league home (`weekly-report/0`) and the home spotlight all pass through
  here. A TTL expiry with a dozen members on the page fans out to 24 Sleeper requests.
- **It compounds S1**: under a hang, every request inside the 2.5 s window spawns its own stuck thread.
  "Track herd" was already fixed once in this codebase (multi-user concurrency audit).
- **Fix**: `ConcurrentHashMap<String, CompletableFuture<Optional<Live>>>` with `computeIfAbsent` for the
  in-flight read, so the first caller's future is shared. Keep the TTL entry as today.

### S3: `pair()` is a second implementation of the pairing rule, not the shared one A12 asked for. READ

`LeagueMatchupRepository.java:98`. A12 says pairing is "extracted into one shared static helper, used by both
paths, so there aren't two implementations of one rule".

- **What was built**: the SQL self-join in `pairedWithScores` still does its own pairing. `pair()` is a Java
  restatement of it, used only by the live path.
- **What the test actually checks**: `LeagueMatchupPairingTest` is pure Java. It pins `pair()` to what the
  author believes the SQL does, and never feeds the same rows to both.
- **Equivalence, as I read it**: for one week they agree.
  - Same filters: `a < b`, null `matchup_id` on either side drops the row.
  - Same 3-roster behaviour: three pairs from both.
  - Same ordering by `a.roster_id`. Java adds a `b` tiebreak, which SQL leaves unspecified.
- **One real difference**: the SQL's INNER join to `roster_week_points` drops a roster that has a
  `matchup_id` and no score row. The live path turns a missing `points` into `0.0` and pairs it as a 0-point
  side. That's harmless today, but it is exactly how the two rules would drift. This is AGENTS.md's
  "two implementations of one rule" class.
- **Fix** (either of these):
  - Make the stored path pair through `pair()`: select `league_matchup ⨝ roster_week_points` rows for the
    week and pair in Java.
  - Or add an IT that inserts a week with a 3-roster `matchup_id`, a null and an odd roster, and asserts that
    `pairedWithScores(...)` filtered to that week equals `pair()` of the same rows.
- At minimum, correct the javadoc and plan A12 with an "amended after review" note: "pinned by tests" is not
  "shared".

### S4: every Matchups or home view of a live season waits on Sleeper, even for a stored final week. READ

`WeeklyReportService.java:223` and `:457`. `decideLive` runs before the stored path on every LIVE_WEEK
request, so `liveWeeks.current()` runs even when `requestedWeek` is a stored week. That call does nothing
except fill `liveWeek` for the stepper.

- **Cost**: on a cache miss the request blocks for the full Sleeper round trip. On a slow Sleeper that's up to
  2.5 s every 15 s (the failure TTL).
- **Where it lands**: on stored weeks and on the home page's `weekly-report/0` mid-season, whose answer never
  depends on Sleeper. SC-006's 2 s target applies to the scores.
- **Fix**:
  - When `requestedWeek` (or the resolved default) is a stored week, read the live week from the cache only
    (a `peek()` that never fetches), and leave `liveWeek` null when nothing is cached.
  - Or start the read asynchronously and take its result only if it's ready.

## Notes

- **N1** (READ, doc only): `WeeklyReportService.java:448` says "Football stores no per-game rows, so for NFL
  only the points test applies". That's false. `player_game` holds NFL 2025 weeks 1-18 and NFL 2026 weeks 1-6,
  about 1,500 rows a week (queried 2026-10-23). The behaviour is better than the doc, because the
  stored-games arm works for NFL too. Correct the comment and the matching A11 line.
- **N2** (READ): the 422 branch in `MatchupLockIns.tsx:45` is dead. `/lock-ins` only returns 404, 400 or 200.
  An ESPN league is a 200 with `NOT_SLEEPER`, which `HIDDEN` already covers. Remove it, or say what it is for.
- **N3** (READ): `/lock-ins?week=` computes `thresholds(league)` (`LockInService.java:311`). That's a
  `computedOf` stamp, about 8 queries, for every complete season in the chain, plus a cold box-cache load. The
  past-week cards never read thresholds, so this is wasted cost on an extra call per final-week view. Drop it
  from the week view, or document that it's there for the response contract.
- **N4** (READ): opening-week rollover. Sleeper moves `leg` to 2 before ingest stores week 1, and week 2
  hasn't started (0 points, no week-2 `player_game`). Between those, the default view is "no week has been
  scored for this league yet" for the 2026 season. That's wider than A13's stated window, which only
  mentions records. It's probably short (on-visit refresh ingests), but it's unmeasured. State it in the
  spec's edge case.
- **N5** (READ): a live roster that is missing from `roster_season` gets `record: null`, and `TeamLine`
  renders `()`. That needs a roster added mid-season before re-ingest. Rare.
- **N6** (READ): a live card's record is "before this week" (`recordAfter(..., week - 1, ...)`), and a stored
  card's is "after this week". Both render as an unlabelled `(2-1)`. On a live card that reads correctly as
  "current record". The misleading case is the pre-existing stored *partial* week, whose record already
  counts a W/L that isn't decided. Not 045's regression, but it's the class-5 shape: worth a follow-up.
- **N7** (READ): `LiveWeekSource.fetch` ignores the live body's status except for `complete`. Together with
  `isLiveSeason`'s `hasGames`, which counts any league's games for the sport and season, a `pre_draft` or
  `drafting` league with `leg >= 1` would count as started. The resolver's stored `in_season` gate keeps that
  from being reached today.
- **N8** (READ): old bookmarks to 2026 `?week=21`, made when the page fell back to 2025, now resolve to 2026,
  where week 21 isn't stored, and get clamped to the live week. That's intended, just unannounced.

## Clean (checked, nothing found)

- **404 handling** is clean: a `HttpClientErrorException.NotFound` is caught inside the supplier, so it is
  never wrapped. Sleeper really returns 404 with body `null` for 1414306784801239040 (curl, 2026-10-23).
- **Errors that are thrown out of the supplier** are clean: `get()` unwraps the `CompletionException`, and a
  timeout is the `ExecutionException` cause.
- **Cache keyed by Sleeper id** is clean: the id is unique per season.
- **FAILED `week = 0`** is clean: it's never exposed as `liveWeek`, and only the stored leg is used, to decide
  whether to refuse.
- **`requestedWeek` past the live week** is clean: it gets the stored "has not been scored" answer, then the
  clamp.
- **A live week whose rows are all unpaired** is clean: it gives zero matchups, the same as a stored week like
  that.
- **`requestedSeason` pass-through on the live path** is clean.
- **Stored path after the `Directory` extraction and the `pairedWithScores` hoist** is clean: same roster
  order, names, records and matchup order. The hoist turns N+1 identical queries into one.
- **NBA fantasy week vs `player_game.week`** is clean: they line up (`start_week` 1 on every league; 2025's 21
  legs fall inside the sport's 25 weeks). The stored rankings already use the same `forWeek` keying.
- **Resolver LIVE_WEEK** is clean: the chain is walked newest-first. 1414306784801239040 has no chain, so it
  gets the same answer as before, then a 404, then empty, then "no week has been scored".
- **Recap** is clean: `PLAYED_WEEKS` end to end, and `live` refused in both `view` and `inputs`. No
  `forWeek` caller is left on the wrong rule, and no one-argument `resolve` remains.
- **`weekCovered` NON_NULL on a record component** is clean: propagated by Jackson and pinned by
  `LockInWeekViewTest`. A refusal or `withTotals` gives false, because `Computed.weeks` is empty there.
- **The `/lock-ins` no-week path** is clean: it builds through the 19-argument constructor, so the key is
  absent.
- **Player+week 400** is clean: it comes after the membership 404 and uses a non-null `Map.of` body.
- **`Map.of` with null values**: none on any response path. The new `weekly-report` keys use the existing
  `LinkedHashMap`.
- **Frontend clamp loop** is clean: there's no loop, and with `data` null mid-load `top` is 0, so the clamp
  is skipped.
- **The `MatchupLockIns` effect** is clean: it is keyed on `data` identity, which only changes when a fetch
  lands (one lock-ins call per report), and the `alive` flag drops stale responses.
- **Mode for a stored non-final week** is clean: it fetches the live `/lock-ins` and shows the "week differs"
  note when it isn't the current week (A5).
- **`api.ts`** is clean: `live`, `liveWeek`, `sleeperLeagueId` and `weekCovered?` mirror the Java field for
  field.
- **PlayerSpotlight and LeagueHome on a live response** are clean: "week in progress", and
  Leading/Trailing.

## Fixes applied (2026-10-23)

- **B1 fixed.** `matchupWord` (new `web/src/lib/matchupResult.ts`) is the one Won/Lost/Tied vs Leading/Trailing/Level rule; `LeagueHome`'s `MatchupBlock`, Matchups' `Scoreboard` and `Game` all use it, and `wr-won` needs `weekFinal`. Final weeks are unchanged. Proven by `WeeklyReport.test.tsx` "B1: ..." (4 cases: live Leading with no Won/Lost and no `.wr-won`; live card no highlight; Trailing/Level; a final week still highlights).
- **B2 fixed.** `decideLive` puts the refused week in `liveWeek` on both refusals, so `top` includes it and the page shows the reason. data-model.md `liveWeek` row marked "amended after code review B2". Proven by `WeeklyReportServiceLiveWeekTest` (`b2_...`, plus the two refusal tests now asserting `liveWeek`) and `WeeklyReport.test.tsx` "B2: mid-season, ?week=4 ... stays on week 4 and shows the reason".
- **S1 fixed.** `LiveWeekSource` builds its own `RestClient` from `${sleeper.base-url}` on `JdkClientHttpRequestFactory`, with connect and read timeouts both equal to `draftsim.live-week.timeout-ms` (hand-set, arbitrary); `orTimeout` stays as the outer bound; `SleeperClient` untouched; the package-private `Reader` seam keeps the stub tests network-free. Proven by `LiveWeekSourceTest.s1_aHungSocketEndsOnItsOwn...` (a real socket that accepts and never answers, 300 ms timeout, the call throws well under 2 s).
- **S2 fixed.** A `ConcurrentHashMap` of in-flight reads shares one read per Sleeper id; the TTL cache is unchanged. Proven by `LiveWeekSourceTest.s2_concurrentColdCallsShareOneRead` (12 concurrent cold calls make exactly 1 `league()` call).
- **S3 fixed (test plus doc, `pairedWithScores` not rewritten).** `LeagueMatchupPairingIT` feeds the same rows (null matchup_id, a 3-roster matchup_id, shuffled insert order) to the SQL and to `pair()` and asserts identical pairs, points and lower-roster order (the higher-roster order within a 3-roster matchup is unspecified in SQL, so it is compared as a set). A second case pins the one known difference (SQL drops a roster with no stored score), now documented in `pair()`'s javadoc. Ran, not skipped.
- **S4 fixed.** `LiveWeekSource.peek` returns the cache only and refreshes in the background (single-flighted). `decideLive` calls blocking `current()` only for the nothing-stored default or an unstored explicit week; otherwise `peek()`. `liveWeek` may be absent on a very first cold view (documented in the javadoc). Proven by `LiveWeekSourceTest.s4_*` (peek returns at once and the refresh lands; peek serves the cache, refreshes on expiry) and `WeeklyReportServiceLiveWeekTest.s4_*` (stored week and mid-season default never call `current`; unstored explicit week and opening-week default do).
- **N1 fixed.** The false "Football stores no per-game rows" javadoc is corrected (`player_game` has NFL 2025 weeks 1-18, about 1,550 rows a week, queried 2026-10-23). The matching A11 line in plan.md was not edited: A11 says "NFL-relevant", it does not repeat the false claim.
- **N2 fixed.** The dead 422 branch and the `ApiError` import are removed from `MatchupLockIns.tsx`; any error means "couldn't be loaded". Covered by the existing "a failed lock-ins read never takes the scores away" test.
- **N5 fixed.** The "()" came from the frontend (`({side.record})` in `ScoreSide` and `TeamLine`); nothing renders when the record is null or empty. Proven by `WeeklyReport.test.tsx` "N5: a side with no record renders no empty parentheses".
- **N3, N4, N6, N7, N8: left as notes**, as instructed. No code changed.
