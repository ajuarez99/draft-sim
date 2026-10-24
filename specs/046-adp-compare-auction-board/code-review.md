# Spec 046 — Bug-hunting code review

Reviewer: separate pass (Opus), 2026-10-23. Scope: uncommitted diff on branch
`046-adp-compare-auction-board` vs origin/main 5512cd7, incl. untracked files.
Not a style pass. Each finding is CONFIRMED (reproduced) or SUSPECTED (reasoned from code).

## Findings


### C1 — MEDIUM — Blend $ "unavailable" reason says an auction "isn't an auction" when its budget is null — CONFIRMED (data) / code-read
- **Where:** `web/src/pages/AdpCompare.tsx:63-69` (`blendCostReason`), fed by `draft.budget`.
- **Scenario:** any auction draft row ingested before V39 (or whose Sleeper settings lacked `budget`) has
  `draft.budget = null`. The page then renders, under the Blend $ note: *"Blend $ is unavailable here: this
  league's draft has no budget (it isn't an auction)"* — directly above a Budgets section for that same
  auction. The budget cards also show `$—` left / `max bid $—`.
- **Evidence:** scratch DB 5436 right now: `draft 27378` (sleeper `1415422395308609536`, `draft_type='auction'`)
  has `budget = NULL` and `count(amount) = 0` of 230 picks — i.e. exactly this state until a re-ingest.
- **Fix:** branch on `d.draft.type`: for `type === 'auction' && budget == null` say "Sleeper hasn't reported this
  auction's budget yet"; keep "it isn't an auction" only for non-auction types.

### C2 — MEDIUM — The already-completed real auction shows 230 "price missing" sales in prod until someone re-ingests the league — CONFIRMED (data)
- **Where:** deploy path; `LeagueIngestService` is the only writer of `amount`/`budget` for a `complete` draft
  (the poller never runs for a complete draft, `LiveDraftPoller.track` / `liveStream` skip it).
- **Scenario:** deploy V39 + code → prod's "Legacy Ball Knowers" auction row has `budget NULL`, every
  `draft_pick.amount NULL`. The page renders every sale as "price missing", every team as `spent $0 · 23 prices
  missing`, max bid `$—`. Nothing in the daily refresh re-ingests the draft.
- **Evidence:** same scratch-DB row as C1 (it mirrors pre-046 ingest). quickstart.md re-ingests locally, but no
  task/HANDOFF line owns the prod `POST /api/ingest/all/1415422393706455040` after deploy.
- **Fix:** add an explicit post-deploy step (and note HANDOFF.md:328's warning not to run `/ingest/all` during a
  live draft does not apply to this complete one).

### C3 — MEDIUM — A Sleeper ADP capture carries no season, so any refresh for another season overwrites today's capture — CONFIRMED (payload measured) / write path code-read
- **Where:** `ingest/SeasonProjectionIngestService.java` (capture call added before the gp check),
  `ingest/SleeperAdpCaptureService.java:64-106`, `api/IngestController` `/adp/sleeper?season=` and
  `/season-projections?season=`.
- **Scenario:** `POST /api/ingest/season-projections?sport=nba&season=2025` (a legitimate projections
  backfill), or `/api/ingest/adp/sleeper?sport=nba&season=2025`, fetches the 2025 payload, and
  `adpCapture.capture(sport, raw)` writes it as **today's** `sleeper_dynasty` capture. The shrink guard does not
  stop it: measured today, nba/2025 has **530** usable `adp_dynasty` rows vs **817** for nba/2026, and
  530 >= 0.5 x 817. (Redraft: 422 vs 903, refused.) The page then shows last season's dynasty ADP labelled
  "fetched on <today>", beside this season's redraft ADP. `ADP_CAPTURE` won't repair it that day
  (`capturedToday` is true for both sources); it self-heals at the next day's run.
- **Evidence:** `curl https://api.sleeper.app/projections/nba/{2025,2026}?...` counted with
  `SleeperAdpCaptureService.ranked`'s rule (0 < x < 999): 2025 -> 530 dyn / 422 std; 2026 -> 817 dyn / 903 std.
  `MIN_KEPT_SHARE_OF_STORED = 0.5`.
- **Fix:** capture only for the season the compare page means (pass `season` into `capture` and skip unless it
  equals the daily step's season), or record the season on the row and filter the reader on it. Also make the
  daily step and the projection step agree on ONE season rule (today: `ProjectionSeason.current` vs "max NBA
  league season" -- two implementations of one rule, the class in MEMORY's multi-sport landmines).

### C4 — LOW — Yahoo matching uses the lenient matcher: first-of-duplicates wins, team ignored, losers vanish silently — SUSPECTED (latent)
- **Where:** `ingest/YahooAdpService.java:74,85,108-116`; `PlayerMatcher.build` (`putIfAbsent`, team used only for
  DEF); `PlayerRepository.findAll` has no `ORDER BY`, so which duplicate wins is heap order.
- **Scenario:** the NBA table holds real normalized-name collisions (query on 5436): `jaylin williams` (OKC C ACT vs
  a FA PF/SF), `chris smith`, `mike james` (RET vs FA), `jameer nelson`, `marcus thornton`, `tony mitchell`. A Yahoo
  "Jaylin Williams, PF,C, OKC" tries `PF` first and lands on the **free agent**, not OKC's centre. Separately,
  `rows.putIfAbsent` drops a second Yahoo entry resolving to an already-matched id: it is neither stored nor put in
  `adp_unmatched`, so `ranked` undercounts with no log line.
- **Evidence:** none of the 185 players Yahoo ranks today hit a colliding key (checked all 8 live pages), so this
  is latent, not live.
- **Fix:** `PlayerMatcher.buildStrict` (spec 035 built it for exactly this) with team as tie-break; send AMBIGUOUS
  and duplicate-id entries to `adp_unmatched` instead of dropping them.

### C5 — LOW — `YahooAdpService.ingest` is documented "never throws" but a DB failure in `saveCapture` propagates; no range check on `percent_drafted` — SUSPECTED
- **Where:** `ingest/YahooAdpService.java:26-27,98`; `YahooAdpClient.number`.
- **Scenario:** a `percent_drafted` >= 10 (e.g. Yahoo switching to percents) overflows `numeric(5,4)` -> the
  transaction rolls back (good; `BoardRepositoryCaptureIT` proves the rollback) but `POST /api/ingest/adp/yahoo`
  answers 500 instead of a `rejectedReason`. A value between 1 and 9.9999 would be stored and read as "drafted in
  500% of leagues", making every row "reliable".
- **Fix:** reject the fetch in the client when any `percent_drafted` is outside [0, 1]; route `saveCapture`
  failures to the rejected path.
- Related edge: `latestCaptureDetail` dates a capture only from `adp_snapshot`, so a Yahoo day with 0 matched rows
  (everything in `adp_unmatched`) reads as "unavailable". Unreachable while players are ingested; noted only.

### C6 — LOW — Live following ignores `paused`, and a failed refetch is never retried for that pick count — SUSPECTED
- **Where:** `web/src/pages/AdpCompare.tsx` (`following` = `pre_draft || drafting`; `fetchedFor.current = picksMade`
  is set before the fetch, which ends in `.catch(() => {})`).
- **Scenario:** (a) page opened while Sleeper reports `paused` (auction autopause is a Sleeper setting; the real
  draft had it off) -> no stream; after resume no sale appears until a manual reload. (b) the refetch for pick N
  fails -> sales stay at N-1 until pick N+1 lands; for the LAST sale nothing ever retries.
- **Fix:** include `paused`; set `fetchedFor` only on success (or retry once).

### C7 — LOW — `AdpDraft.status` is typed `string`, but `draft.status` is a nullable column passed through as null — CONFIRMED (schema)
- **Where:** `web/src/api.ts` `AdpDraft.status`; `\d draft` on 5436 shows `status text` nullable.
- **Fix:** `status: string | null` (hard rule: mirror nullability). No crash today (only compared with `===`).

### C8 — LOW — An auction league can still be mocked from the new-mock modal and the Home hero — SUSPECTED
- **Where:** `web/src/pages/DraftPicker.tsx` `mockableLeagues` (filters ESPN only) and the `recentLeague` hero.
- **Scenario:** FR-015 says "Mock it MUST NOT be offered for an auction league"; the card button is hidden, but the
  modal's league list still offers the auction league and starts a 23-round snake mock. `POST /api/mocks` is not
  guarded (only `/from-draft` is). A fresh snake mock may be acceptable -- needs a decision, not necessarily code.

### C9 — LOW — During an NBA auction the rail shows three rows that open the same page — CONFIRMED (code)
- **Where:** `web/src/destinations.ts`: `board` -> `draftRoute` -> `/leagues/X/adp` ("Auction board"), `live` ->
  same, and the new `adp` row -> same. Only `adp`'s `match` regex highlights on that page.
- **Fix:** filter `live` (and arguably `board`) out in `destinationsFor` when `auctionAdpRoute` is non-null.

### C10 — LOW — Football auction live room: the 409 is swallowed, not shown (FR-016 "clear message") — SUSPECTED
- **Where:** `web/src/pages/LiveDraftView.tsx` resim via `streamSimulationQuietly`; `auctionAdpRoute` is NBA-only,
  so a football auction stays in the live room and re-hits `409 auction_draft` on every landed pick with no
  message. No football auction exists today.

### C11 — LOW — Shrink guards never age out and have no override (answers review question 9) — CONFIRMED (code)
- **Where:** `YahooAdpService.java:90-96`, `SleeperAdpCaptureService.java:111-119`.
- **Scenario:** if Yahoo legitimately ranks fewer players (its analysis thinning in-season), every daily run is
  refused forever and the page shows an ever-older "fetched on" date (honest, but stuck); the admin route has no
  `force`. **Not a hammer:** `runAll` is only called by the once-a-day GitHub cron (`0 11 * * *`), so a refusal
  costs one ~9-request Yahoo pass per day.
- **Fix:** a `force` param on `/api/ingest/adp/yahoo` and `/adp/sleeper`, or compare only against a recent capture.

### C12 — LOW — `drafts.forLeague` (`order by season desc limit 1`) has no tie-break — SUSPECTED
- **Where:** `store/DraftRepository.java` `forLeague`; newly load-bearing for `AdpCompareService`.
- **Scenario:** two Sleeper drafts in one league-season (a commissioner re-creating the draft) -> the page may bind
  to the dead one. Fix: `order by season desc, start_time desc nulls last, id desc`.

### C13 — LOW — React key collision for unmatched Yahoo rows sharing a name — SUSPECTED
- **Where:** `AdpCompare.tsx` row key `p.playerId ?? 'y:' + p.name`. Two unmatched entries with one name ->
  duplicate keys. Fix: include team/ADP in the key.

### C14 — INFO — `origin/main` moved (5512cd7 -> 48371dd, spec 045) and touches files in this diff
- Overlap: `web/src/api.ts`, `web/src/pages/LeagueHome.tsx` (+ test), `web/src/styles.css`, `HANDOFF.md`. No
  migration collision (no V39+ on any remote branch). Rebase/merge and re-run both suites before the PR; the green
  run below was against the old base.

## Bug-class checklist (AGENTS.md): checked and found correct

1. **Sign/ordering.** `gap = yahooAdp - dynasty`; `tintOf` gives `'yahoo'` (CSS `--up`, green) when
   gap <= -threshold, i.e. Yahoo earlier: correct, and `adpBlend.test.ts` asserts the Durant (green) / Dybantsa
   (red) direction, not just shape. Blend = `dyn + w*(yahoo - dyn)`, `w = trust*min(|gap|/fullGap, 1)`, 0 when
   unreliable: correct. `rescale`: `1 + k(c-1)`, `k = (teams*budget - N)/sum(c-1)`, padded with $1 to N;
   `prices[rank-1]` for the 1-based blended rank: correct. Sorts: nulls last both ways, fixed ascending tie-break.
   `maxBid = max(0, left - (spotsLeft - 1))`, 0 with no spots: correct (`AdpCompareServiceTest` asserts 178).
2. **SQL.** `draft.draft_type` is `NOT NULL` (`\d draft`), so `d.draft_type <> 'auction'` can't drop a NULL row;
   ESPN drafts are already excluded by `platform = 'SLEEPER'`. Backfill and `allCompletedPicks` filters executed by
   `AuctionBackfillExclusionIT` / `AuctionExclusionIT` (green). `saveCapture` is `@Transactional` and called across
   beans (proxy applies); replace + rollback covered by `BoardRepositoryCaptureIT` (5 cases, green).
   `latestCaptureDetail` uses `order by ... limit 1`; `latestCapture`'s `max()` -> `optional()` maps the null row
   to empty (pre-existing, relied on by `currentBoard`).
3. **JDBC binds.** numeric columns via `setDouble`/`setNull(NUMERIC)`; `amount`/`budget` via `setInt` /
   `setNull(INTEGER)` / `.param(12, budget, Types.INTEGER)`; text via `setString`. Real values fit: Yahoo
   `percent_drafted` "1.00" (a fraction), costs <= 81, ADP < 999.
4. **api.ts vs records.** Source, Player, Sale, TeamState (incl. `mine`), SaleRow, Draft, Response,
   `DraftSummary.draftType`, `AUCTION_DRAFT`, nullable `LeaderboardDraftPick.pickNo/round`: field-for-field except
   C7. `@JsonInclude(NON_NULL)` is only on `Draft.teamsState/sales` (optional in TS); TeamState's null
   `managerId/name/avatar` and Player's null Yahoo fields serialize as `null` (no global inclusion setting).
   `npx tsc -b` passes in this worktree.
5. **Map.of.** None on the new JSON path; ErrorHandler's has two non-null constants.
6. **Live path.** Measured on the real auction: `draft_slot` is the winner's slot (23 picks per slot; per-slot
   sums 200/183/199 against budget 200), `round` is the pick-number round, `metadata.amount` is a string.
   PickMapper handles all of these. `picksMade` = rows Sleeper returned and `sales` = all stored picks incl. unresolved
   players, so `picksMade === sales.length` compares like with like; a reset -> `picksMade 0` triggers one refetch, no
   loop (`fetchedFor`). `useLiveDraft` closes the source on `complete`. `onTheClockSlot`'s snake math runs for
   auctions but nothing on the ADP page reads it.
7. **Routing.** `auctionAdpRoute` is the single rule; picker, LeagueHome (3 links), rail board/live, DraftView and
   LiveDraftView redirects (`replace`, before any `run()`/resim) all use it; snake and football unchanged; no loop
   (the ADP page never redirects). `/drafts/:id/board` deliberately unchanged (F1).
8. **Ingest.** Only `LeagueIngestService` calls the 12-arg `upsert` and passes `budget` explicitly; ESPN uses
   `upsertEspn` (budget untouched) and passes nulls in `PickRow`; the poller's `updateBudget` writes only when
   Sleeper sent one. Amount: missing/blank/junk/negative -> null, never 0. `= excluded` for amount/name/id is covered
   by `AuctionIngestIT`. Checked 4 real snake drafts: none carries `settings.budget`.
9. **Yahoo.** URL verified live (200, `json_f`, 25 per page); stop rule verified on real pages (start=175: 10
   numeric; start=200: 0 -> stop; 185 ranked). `"-"` -> null. Fetch failure keeps the previous capture.
10. **Sleeper capture.** 999 and <= 0 excluded; payload values are JSON numbers (measured); per-source guard; no
    double fetch (`SEASON_PROJECTIONS` runs first and `ADP_CAPTURE` skips when both captures are today's).
11. **Honesty.** FR-013 is rendered: "fetched on", ranked counts, redraft + category-league assumption, hand-set
    badges on both sliders and the 50% line, "derived guides, not measurements", the 25-50% sentence, and a reason
    whenever Blend $ is hidden (but see C1's wrong reason).
12. **Perf.** Per request: 3x2 capture queries, one `findAll(nba)` (2,138 rows on 5436), 3 manager maps, draft and
    picks. No N+1. Same order as `pickNaming`; acceptable even with per-pick refetches.

Test evidence: latest backend run in this worktree: 2,294 tests, 0 failures, 0 errors, 6 skipped (the known
spec-041 NBA fallback ITs); all 13 new/changed spec-046 test classes present and green.

## Counts

BLOCKER 0 / HIGH 0 / MEDIUM 3 (C1, C2, C3) / LOW 10 (C4-C13) / INFO 1 (C14)

## Fixes applied (2026-10-23)

Fix pass on the findings above. Tests were written with each fix; they were NOT each run against the unfixed
code first (the fixes and tests landed together), so "would have failed before" is by construction of the
assertion, not by a recorded red run. Suite totals are at the bottom.

| # | Change | Test |
|---|---|---|
| C3 | New `engine/AdpSeason.current(leagues, sport)` = newest league season, the ONE rule. `SleeperAdpCaptureService` now takes `LeagueRepository`; `capture(sport, season, raw[, force])` and `fetchAndCapture(sport, season[, force])` refuse (rejected `Result`, nothing written, no fetch) unless `season` is that season. `SeasonProjectionIngestService` passes its `season`; `DailyRefreshService.adpCapture` uses `AdpSeason`; `/adp/sleeper` gets the refusal through the result. | `SleeperAdpCaptureServiceTest`: other season writes nothing and does not fetch, no NBA league writes nothing, `force` doesn't bypass the season rule; `SeasonProjectionIngestServiceTest.theCaptureIsHandedTheSeasonBeingRefreshed` |
| C1 | `blendCostReason` branches on `draft.type`: an auction with null budget says Sleeper hasn't reported the budget (re-ingest). | `AdpCompare.auction.test.tsx` C1 |
| C2 | Docs: post-deploy re-ingest of Legacy Ball Knowers in `quickstart.md` and the spec 046 entry of `HANDOFF.md`, with the do-not-run-during-a-live-draft warning. Page: when every sale has a null amount and the draft is complete (or has no budget), the feed is one line with the count instead of N "price missing" rows; per-team missing counts stay. A live auction with a single unpriced sale still lists it. | `AdpCompare.auction.test.tsx` C2 (two tests) |
| C4 | Yahoo matching uses `PlayerMatcher.buildStrict` plus new `matchStrictWithTeam` (team breaks a same-name tie; ESPN's `matchStrict` untouched). Name first, then Yahoo's position list only if the name is still shared. AMBIGUOUS, UNMATCHED and a second entry for an already-matched player all go to `adp_unmatched` (logged with the reason; no reason column added, that would be a migration). | `YahooAdpServiceTest`: Jaylin Williams OKC vs free agent listed first, shared name no team can split, duplicate entry -> unmatched (fetched = rows + unmatched) |
| C5 | `saveCapture` failure is caught into `rejectedReason` (logged). Whole fetch rejected if any ADP <= 0 or any `percent_drafted` outside [0, 1]. | `YahooAdpServiceTest`: save failure, percent 98, ADP 0 |
| C6 | `following` includes `paused`; a pick count is marked fetched only on success, an in-flight ref stops duplicate fetches, and the effect depends on `live` so the next frame retries. | `AdpCompare.auction.test.tsx` C6 (paused; failed refetch retried, then not refetched) |
| C7 | `AdpDraft.status: string \| null`. Checked every other mirrored field of the compare records: only `status` was wrong. | `npx tsc -b` |
| C8 | `DraftPicker`: the new-mock modal's league list and the hero's `recentLeague` exclude NBA auction leagues (`auctionAdpRoute`). `POST /api/mocks` itself is still unguarded (only `/from-draft` is); not changed, see below. | `DraftPicker.test.tsx`: modal omits the auction league, says nothing to clone when it is the only one, hero skips it |
| C9 | `destinationsFor` drops `mock`, `live` AND `adp` for an auction league and keeps ONE row to the page: `board`, "Auction board". Chosen over keeping `adp` because `switchTarget` and the rail's board link fall back to the `board` row. New `railKeyFor` makes the rail light `board` (and year links stay on the page) when the path says `adp` but no `adp` row is offered. | `destinations.test.ts` (one row; snake keeps both; `railKeyFor`), `LeagueRailSection.test.tsx` year link |
| C10 | The 409 was not swallowed: it reached the page banner, but as the raw code `auction_draft`, because `apiError` prefers `error` over `message`. `apiError` now uses `message` for `auction_draft` only. | `api.auctionDraft.test.ts` (3), `LiveDraftView.resim.test.tsx` (banner shows the sentence) |
| C11 | `force=true` on `POST /api/ingest/adp/yahoo` and `/adp/sleeper` bypasses the shrink guard only. | `YahooAdpServiceTest`, `SleeperAdpCaptureServiceTest`, `IngestControllerTest` |
| C12 | `forLeague` orders by `season desc, start_time desc nulls last, id desc`. | `DraftRepositoryForLeagueTieBreakIT` (3; real Postgres on 5436) |
| C13 | Unmatched row key is `y:name:team:adp:index`. | `AdpCompare.auction.test.tsx` C13 (no duplicate-key warning for three same-name unmatched rows) |

Deliberately not changed:
- **C8, `POST /api/mocks`** stays unguarded for an auction league. The review called a fresh snake mock
  "possibly acceptable"; the UI no longer offers it, and a decision (not code) is still open.
- **C5 related edge** (a Yahoo day with 0 matched rows reads as unavailable): unreachable while players are
  ingested; left as noted.
- **C14** (origin/main moved): not touched; rebase/merge and re-run both suites before the PR.
- **Unmatched reason column:** C4 asked for a reason "if cheap"; it needs a migration (V39 is unmerged but
  append-only discipline applies once anyone applied it), so reasons are in the log only.

Suite totals after the fixes (this worktree, scratch DB 5436): backend 2,310 tests, 6 skipped (the same known
spec-041 fallback ITs), 0 failures, 0 errors, summed over all 289 `build/test-results/test/*.xml` (was 2,294).
Web: `npx tsc -b` clean, `npx vitest run` 2,476 passed in 142 files (was 2,459), `npm run build` OK.
