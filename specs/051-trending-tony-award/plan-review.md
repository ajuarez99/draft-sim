# Plan review: The Trending Tony Award (spec 051)

**Reviewer**: adversarial plan review, read cold against worktree `051-trending-tony-award` (branch at main 7168753), 2026-10-27.
**Scope**: spec.md, plan.md, research.md, data-model.md, contracts/superlatives-api.md, quickstart.md, against the real code.

**What I could and couldn't run**: I read all the code cited below and ran the `git` checks myself (F-list and the
"Checked and fine" list mark each one). I could **not** run a DB query. Nothing was listening on 5433 (`netstat`
showed only the unrelated 5432 cluster, and that one is off limits), and `docker ps` was empty. So every claim about
how many stored rows have a null `created_at` is **inferred from the ingest code**, not measured.

---

## F1 — `completedAdds` is not "the WAIVER/FREE_AGENT set". Trades and commissioner adds would be judged as pickups

**Severity**: HIGH

**Evidence (read)**:
- research.md R4: "Reuse `WaiverPickupAttribution.completedAdds(txRows)` unchanged — the same completed
  WAIVER/FREE_AGENT set Waiver Wire Warrior and Jabari Smith Jr. use".
- `WaiverPickupAttribution.java:61-65`, the method's own javadoc: "Shared by `attribute` (which additionally needs
  TRADE/COMMISSIONER adds …) and `MostAddedPlayers`, which filters to WAIVER/FREE_AGENT **itself**". Lines 67-81
  filter only on `status == "complete"`. They never look at `type`.
- `MostAddedPlayers.java:102-103` is where Jabari applies `WAIVER`/`FREE_AGENT` and `week < 1 || week > throughWeek`.
- data-model.md's `judge(...)` rules list only "Drop adds with `week > throughWeek` and … `eligible`". There is no
  type filter and no `week < 1`.

**Why it matters**: built as written, a manager who trades for a player who is trending gets a "trending pickup".
A commissioner move does too. That breaks FR-010 and the edge cases ("Trades and commissioner moves … never count").
It would also inflate `totalPickups`, and the coverage line counts against that total. A structural test with only
WAIVER/FREE_AGENT fixtures can't catch it (lesson #33).

**Recommended amendment**: in R4 and data-model.md, state that `judge` filters `type ∈ {WAIVER, FREE_AGENT}` and
`1 ≤ week ≤ throughWeek` itself. Better, extract `MostAddedPlayers.countedByPlayer`'s predicate into one shared
`isCountedPickup(add, throughWeek)` and call it from both, so there is one rule, not two (lessons #16/#25). Add a test
fixture with a completed TRADE add and a COMMISSIONER add of a top-15 trending player. Both must land in no bucket.

## F2 — The "unavailable" condition disagrees between R6 and data-model.md, and one case gives an available card with a null `judgingSince`

**Severity**: MEDIUM

**Evidence (read)**: research.md R6 case 3 makes the award unavailable only when there are "zero judged pickups
**and** zero snapshots in `[firstPickup-24h, lastPickup+24h]`". The data-model.md state table says "Zero judged
pickups → unavailable". data-model.md also says `judgingSince` is "never null on an available card".

**Why it matters**: with R6's rule, take a snapshot inside the season's range that is more than 24h from every
pickup. A quiet stretch does this, and so does ship day when nobody has picked anyone up since the first snapshot.
The result is zero judged pickups with a snapshot in range. That falls to case 4: available, with "nobody has chased
the trending list twice yet" and `judgingSince == null`. That breaks the stated invariant and risks an NPE when
the date is formatted. The data-model rule (judged == 0 → unavailable) has the opposite problem: its only reason
text, "Trending history wasn't being recorded yet for this season", is **false** for the current season once
recording has started.

**Recommended amendment**: use the data-model condition (`judged == 0` → unavailable) with **two** reasons:
- No snapshot exists before the season's last pickup + 24h: "Trending history wasn't being recorded yet for this
  season" (past seasons, SC-003).
- Otherwise: "Trending history began {date}; no pickup since then can be judged yet."

Add both to the state table and the tests.

## F3 — R1 says "closest in time", but the rule as written prefers the earlier snapshot even when it is further away

**Severity**: MEDIUM

**Evidence (read)**: R1's decision says "closest in time to `P` within ±24 hours, preferring the latest snapshot at or
before `P` when one exists". data-model.md and spec FR-002 both say before-first, with after used only when no
before exists.

**Why it matters**: suppose there is a snapshot 23h before the pickup and another 5 minutes after it. R1's prose
picks the after one. The rule as written picks the before one, whose window `[P-47h, P-23h]` doesn't contain the
pickup at all. R1's own rationale calls the after-snapshot the one whose window "literally contains P". The two
readings give different ranks, and so different verdicts, on real pickups. At a daily 11:00 UTC cadence this is the
common case, not an edge.

**Recommended amendment**: choose one rule and delete the other wording. Option (a) keeps before-first, matching
FR-002, and drops "closest" from R1. Option (b) prefers the nearest snapshot whose window contains P (the after
side), then before. Either way, add a test with snapshots on both sides at unequal distances that pins the choice.

## F4 — The snapshot query isn't bounded the way the plan says. It loads every entry row in the season's range on every Awards load

**Severity**: MEDIUM

**Evidence (read)**: plan.md says "≤ a few thousand entry rows for a season". The spec's Assumptions say "roughly
220k rows per sport-year". research.md R3 says "tens of thousands of rows a year". These three figures disagree.

Fetch density is set by visits: up to one fetch per hour per sport (`RefreshProperties.STALE_AFTER`,
`LeagueRefreshService.java:240`). The range is `[min createdAt-24h, max createdAt+24h]` over the season's pickups.
Sleeper week-1 transactions include post-draft offseason moves (inferred from Sleeper's leg semantics, lesson #43),
so the range is the whole season, about 5-6 months. That gives up to 25 × 24 × 180 ≈ 108k entry rows per request
for the current season. `SportTrendingRepository`'s proposed `Snapshot(..., Map<String, Ranked>)` materializes all
of them. This runs on a 1 GB heap (spec 041).

**Why it matters**: SC-005 (within 0.5 s) and memory. Also bug class 8: three different volume figures are each
stated as fact.

**Recommended amendment**: load snapshot **headers** (`id, fetched_at, lookback_hours`) for the range, which is
cheap. Load **entries** only `where rank <= :topN and sleeper_player_id in (:pickedUpIds)`, or do the matching in
SQL. Replace the three volume figures with one stated as a bound, and measure the real count in verification
(SC-006 already reports snapshots per day).

## F5 — R5's reason for excluding ESPN is factually wrong

**Severity**: MEDIUM. The decision may stand; the stated evidence is wrong.

**Evidence (read)**: R5 says "ESPN transaction rows key players by ESPN ids … which never match Sleeper trending ids".
But `EspnLeagueIngestService.java:563-567` `keyed(...)` stores `rp.keyByExternalId().getOrDefault(pid, "espn:" + pid)`:
it uses the **Sleeper id** whenever the resolver matched the player, and the `espn:` prefix only when it didn't
(V37 header, "Plan F10"). ESPN `createdAt` is also a different instant: `EspnTranslator.java:277` uses `proposedDate`
(the bid), while Sleeper uses `status_updated` (processing; `TransactionIngestService.java:133-137`, lesson #43).

**Why it matters**: the project rule is to correct wrong claims visibly. If a reviewer or a later spec believes
"ESPN ids never match", they will misjudge what turning this on for ESPN would take.

**Recommended amendment**: rewrite R5's rationale with "amended after review". ESPN adds mostly do carry Sleeper ids.
Excluding ESPN is a **product** choice: Sleeper's platform-wide list says little about an ESPN league's waiver pool,
and ESPN timestamps mean bid time rather than processing time. Keep FR-013 as is. `league.platform()` is on
`LeagueRow` (`LeagueRepository.java:169-171`) and ESPN leagues do reach `forLeague`, because `destinations.ts:356-366`
has no platform gate. So the check is real and needed.

## F6 — The Players view would print the trending rank as "N trending pickups"

**Severity**: MEDIUM

**Evidence (read)**: plan.md lists `SuperlativeStandings.tsx` with "figure for contributions = '#rank'; nothing
structural". But the component takes one `figure` prop (`SuperlativeStandings.tsx` Props, "Built by the card from
`standingFigure`"). It applies that prop to standings rows **and** to contribution rows (`contributionRow`:
`{figure(c.value)}`). R7 sets contribution `value` = trending rank, so a #2 trending pickup would render as "2
trending pickups", or as a bare "2" (see F7).

**Why it matters**: it is a wrong number with a plausible label (lesson class 5), and no test catches it unless one
checks the Players view text.

**Recommended amendment**: either add a separate `contributionFigure` prop, which is structural (say so in the plan),
or let `standingFigure` take a context argument. Add a test that the Players view reads "#2 trending" and the Teams
view reads "4 trending pickups".

## F7 — The plan's `api.ts` list misses the `unit` union, and Home's `unitLabel` has no PICKUPS case

**Severity**: LOW

**Evidence (read)**:
- `api.ts`: `unit: 'POINTS' | 'WINS' | 'GAMES' | 'ADDS' | null`. The plan's api.ts list doesn't mention adding
  `'PICKUPS'`.
- `Superlatives.tsx` `formatValue` falls back to `String(value)` for an unknown unit.
- `LeagueHome.tsx:127-140` `unitLabel` returns `''` for an unknown unit. Home's award block is not in the plan's file
  list.
- `TrophyCase.test.tsx`, `LeagueHome.test.tsx`, `AwardWinners.test.tsx`, `SuperlativeStandings.test.tsx` and
  `Superlatives.test.tsx` together build 47 `Superlative` fixtures (`grep -c playerContributions`). A required
  `trendingCoverage` field breaks `tsc -b` in all five files, but the plan lists only three test files.

**Recommended amendment**: add `'PICKUPS'` to the `unit` union, add `PICKUPS → 'trending pickups'` in `unitLabel` and
`formatValue`, and list every fixture file, or one shared fixture factory, in tasks.

## F8 — Every past season in the Hall of Fame trophy case gains an "unavailable" Trending Tony row

**Severity**: LOW. It is honest, but the plan hasn't decided it.

**Evidence (read)**: `TrophyCase.tsx:145-151` renders `d.superlatives.map(...)` for each past season through
`AwardWinners`, with no filter on `available`. The plan doesn't mention the trophy case at all. Its hues also shift
(`hueForIndex(i, d.superlatives.length)`), the same cosmetic effect as plan design note 5.

**Recommended amendment**: decide and write it down. Either show the row, or hide `TRENDING_TONY` when it's
unavailable in the trophy case only. The Awards page itself must still show the reason (FR-008). Add whichever is
chosen to the plan's file list and to V7.

## F9 — The coverage total excludes DEF and unscored weeks without saying so, and V2's hand count would disagree on NFL

**Severity**: LOW

**Evidence (read)**: data-model.md says "Dropped adds are in no bucket and not in `totalPickups`" (DEF and
`week > throughWeek`). FR-007 promises "how many of **the season's** completed pickups were not judged".
quickstart.md V2's hand count is "completed WAIVER/FREE_AGENT adds through `throughWeek`", which doesn't exclude DEF.

**Why it matters**: on NFL, V2 fails against a correct build, or the build gets tuned to match V2. The card's
"89 pickups" will also differ from Roster Management's pickup count. On NBA, `throughWeek` is the highest scored
week, so the most recent pickups (in the current week) drop out of every bucket until that week is scored. Those
are the pickups most likely to have a snapshot.

**Recommended amendment**: name both exclusions in the coverage copy and in How this works ("pickups through week N;
team defenses excluded"). Fix V2's SQL to exclude DEF. Whether `scoredWeeks` includes the live NBA week is
**inferred, not checked**. Measure it in V2 and record the answer.

## F10 — `fetched_at` is the league refresh's start time, not when the fetch happened

**Severity**: LOW

**Evidence (read)**: `LeagueRefreshService.java:218` sets `Instant startedAt = Instant.now()`. Then
`history.ingestChain(...)` and the per-game loop run, and only after that does `:240` call
`trendingRefresh.refreshIfStale(sport, startedAt)`. `TrendingRefresh.java:197-198` writes `startedAt` as
`fetched_at`. A cold-start chain refresh runs for minutes (memory says ~5 min). The daily route passes a fresh
`Instant.now()` (`DailyRefreshService.java:190`), so it is not affected.

**Why it matters**: snapshot timestamps can understate the real Sleeper window end by minutes. It's harmless at
±24h, but research and the contract present `snapshotAt` as exact.

**Recommended amendment**: either state the skew in data-model.md, or have `fetch` stamp `Instant.now()` at the
moment of the call. That changes `sport_trending_fetch.fetched_at` too, so the two stay identical. The spec 014
staleness gate would then measure from the true fetch time, which is arguably more correct; note it as a
behaviour change.

## F11 — Name clash: `SportTrendingRepository.Snapshot` already exists with a different shape

**Severity**: LOW

**Evidence (read)**: `SportTrendingRepository.java:37-39` already defines
`record Snapshot(OffsetDateTime fetchedAt, int lookbackHours, Integer leagueSeason, LocalDate seasonStartDate, …, List<Entry> entries)`.
data-model.md's `snapshotsBetween` returns a different `Snapshot(fetchedAt, lookbackHours, Map<…>)` in the same class.

**Recommended amendment**: give the new record its own name, e.g. `HistoricalSnapshot` or `TrendingSnapshot`, in
data-model.md before a build agent invents one.

## F12 — The new 15th component needs a new 14-arg constructor, and that constructor recreates the F2 trap

**Severity**: LOW. The plan already names the trap; this is about how far the guard reaches.

**Evidence (read)**: the `Superlative` canonical constructor is 14 components today
(`SeasonSuperlativesService.java:177-180`). Several builders call it directly with 14 args: `waiverWinners`, the
absence and unethical builders, `withStandings` (`:555-559`) and `withUsernames` (`:1846-1848`). Adding a 15th
component makes every one of them a compile error unless a 14-arg overload is added. Once that overload exists, both
rebuilders compile unchanged and silently null out `trendingCoverage`.

**Recommended amendment**: say explicitly in tasks that a 14-arg overload is added (defaulting to null) and that
both rebuilders switch to the 15-arg canonical constructor. Cover them with a test that runs a `TRENDING_TONY`
superlative through `withUsernames` **and** `withStandings`, so the guard doesn't depend on which rebuilder today's
call path happens to use.

## F13 — Smaller honesty and spec-text points

**Severity**: LOW

- **Empty snapshot.** `TrendingRefresh` treats an empty `[]` body as success (`:186-199`). An empty snapshot would
  then judge every pickup within ±24h as NOT_TRENDING. Say whether a snapshot with zero entries judges anything. I
  recommend it judges nothing, giving NOT_JUDGED. Otherwise, "absent from the stored 25 counts as NOT_TRENDING" is
  sound: 25 > 15, and a short list means fewer than 25 players were trending.
- **"Guaranteed" daily snapshot.** R2 calls the 11:00 UTC GitHub Actions run a guarantee. GitHub scheduled
  workflows can be delayed or skipped, so "at least one per day" is the intended floor, not a guarantee. SC-006
  measuring it is the right fix; adjust the word.
- **`judgingSince` is a UTC date.** A 02:00 UTC snapshot shows US users the next day's date. Either label it UTC or
  format it in the viewer's local time.
- **Single transaction.** With the snapshot insert inside `replace`, any snapshot-insert failure now also blocks the
  home page's current-list update; `fetch` would record a failure. That trade-off is intended (design note 1), but
  FR-001 says history "MUST NOT change what the home page shows". Note the trade-off under FR-001.

---

## Checked and fine

- **V42 is free (run).** I ran `git fetch` and then `git ls-tree` over all 114 refs (`refs/remotes/origin/*` and
  `refs/heads/*`). No `V4[2-9]__` anywhere. `origin/main`'s highest is `V41__league_weekly_pot.sql`. I also listed
  `backend/src/main/resources/db/migration/` in all 41 worktrees on disk, which covers uncommitted files: none has
  V42+, and the highest is V41. The stash's top entry has none either. Still re-check before merge (lesson #43). I
  could not check the shared 5433 DB's `flyway_schema_history` because it wasn't running.
- **The transaction applies (read).** `TrendingRefresh` calls `repository.replace(...)` on an injected
  `SportTrendingRepository` bean, so the call goes through the Spring proxy and `@Transactional` applies. There is no
  self-invocation. The call runs on a `SingleFlight` virtual thread; the transaction is bound to that thread, which
  is fine. `sport_trending_fetch.fetched_at` and the planned snapshot `fetched_at` come from the same `fetchedAt`
  parameter, so they are identical (but see F10 for what that value means).
- **Timestamp units (read).** `TransactionIngestService.java:133-137` reads `status_updated` as epoch **millis** into
  an `Instant`. `LeagueTransactionRepository.java:60` binds it as `Timestamp.from(instant)` and `:169` reads it back
  with `getTimestamp().toInstant()`. That round-trips to the same instant. `createdAt` is null only when Sleeper
  omits `status_updated` (inferred from the code; the null rate is not measured). For waivers, `createdAt` is
  processing time, as the spec already says; lesson #43 confirms `created_at` means `status_updated`.
- **The 24h window (read).** `TrendingRefresh.LOOKBACK_HOURS = 24` and `LIMIT = 25` are stored per fetch.
  data-model.md reading each snapshot's own `lookback_hours` is right.
- **Refresh cadence (read).** R2 holds: the cron is `"0 11 * * *"` (`.github/workflows/daily-refresh.yml:10`),
  `grep @Scheduled backend/src/main` finds nothing, and there are two callers (`DailyRefreshService.java:190`,
  `LeagueRefreshService.java:240`).
- **Callers of the service (read).** The only main-code consumer of `SeasonSuperlativesService` is
  `SuperlativesController`. No backend test asserts 13 kinds or freezes a JSON key set. The only test that constructs
  the service mocks it (`SuperlativesControllerConductListBatchTest:37`), so a new constructor dependency on
  `SportTrendingRepository` breaks nothing.
- **Controller (read).** `SuperlativesController` builds every map with `LinkedHashMap`, and its `detailRow` is an
  `instanceof` chain with no exhaustive switch. A missed `TRENDING_PICKUP` branch would emit `{}` silently, not
  fail, so the controller test must assert the row's `type`. The sealed `DetailRow permits` list will force the new
  record into place at compile time.
- **AwardWinners (read).** If no `TRENDING_PICKUP` case is added to `detailLine`, `expandableRows` falls through to
  it, gets `''` for every row, filters those out, and so never opens an empty "Games" panel. That is safe, but it
  also means the card will show nothing unless holder lines are added explicitly, which the plan does.
- **Kind order (read).** `forLeague` iterates `Kind.values()`, so appending `TRENDING_TONY` last keeps every existing
  card in place.
- **Other awards (read).** Jabari and Waiver already share `txRows` (`SeasonSuperlativesService.java:356-361`), so
  reusing them costs no extra transaction query. FR-014 and SC-004 are plausible as long as the new method only
  reads.
