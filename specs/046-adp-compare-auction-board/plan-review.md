# Adversarial plan review: spec 046 (ADP compare + live auction board)

This review read spec.md, plan.md, research.md, data-model.md, contracts/adp-compare-api.md,
quickstart.md and the brief `claude/adp-compare-and-auction-board.md` cold on 2026-10-23. It checked
them against worktree `046-adp-compare-auction-board` at `5512cd7` (= `origin/main`, spec 046
untracked) before any code was written.

Every item is labelled **measured** (run today, output quoted) or **read** (from the code, not
executed). Measurements used read-only GETs against Sleeper and Yahoo and read-only `select`s on the
local Postgres on 5433 (`127.0.0.1`, user/db `draftsim`). Throwaway files live in the session
scratchpad (`unfilt.json`, `filt.json`, `y/p*.json`, `realpicks.json`). Nothing in the repo or DB was
changed.

Severity: **BLOCKER** = the plan as written can't meet the spec or breaks a shipped page.
**HIGH** = a real bug or unmet FR that the build would ship. **MEDIUM** = wrong detail, missing test,
or an honesty gap. **LOW** = tidy-up.

## Claims confirmed

| Claim | Where checked | Result |
|---|---|---|
| Highest migration is V38; V39 is free | `ls db/migration` (V32/V35 are gaps, highest V38); `git ls-tree` over every local and remote branch for `V3[89]__\|V4x__`; every sibling worktree (measured) | ✅ Only `V38__conduct_entry_through_week.sql` (16 branches). No V39 or V40 anywhere. Local DB `flyway_schema_history` max = 38. |
| `SleeperProjectionClient.season` returns stub rows carrying `adp_dynasty` / `adp_std` | `nba/2026` payload, today (measured) | ✅ 2,132 rows, 524 with `pts`, **817** with `adp_dynasty < 999`, 903 with `adp_std < 999`. **334** of the 817 dynasty-ranked rows have no `pts`, so capturing after the `pts` filter would lose 41% of them. R1's "capture from the raw payload" is right. |
| The `position[]` filter drops no dynasty-ranked player | same call with and without the five `position[]` params (measured) | ✅ Both return 2,132 rows, 817 dynasty, 903 redraft; no duplicate `player_id`. (The files differ only in key order.) R1's "1,857 unfiltered vs 2,120 filtered" figures are not reproducible today and look like two different days; the plan doesn't depend on them. |
| `adp_` keys are stripped from the stored line | `SeasonProjectionIngestService.java:110` (read) | ✅ `if (key.startsWith("adp_")) continue;` |
| 999 = undrafted; values fit `numeric(6,2)` | payload (measured) | ✅ 965 rows hold exactly 999.0; real values span 1.3–699.0 (dynasty) and 1.3–700.0 (redraft). |
| Every dynasty/redraft-ranked Sleeper id exists in `player` (FK `adp_snapshot.player_id not null`) | 817 + 903 ids vs `select sleeper_id from player where sport='nba'` (measured, local) | ✅ 0 missing locally (2,133 NBA players). Prod not checked. |
| Every `adp_snapshot` reader filters by `source` | `BoardRepository.load/latestCapture/topRanked/asOf/latestBefore`, `BoardService.backfillAdpAtTime` (`s.source = ?`), the only other mention is a comment in `FfcAdpService` (read) | ✅ No `select *`, no unfiltered read. New sources can't leak into the board. |
| `adp_snapshot` writes are clear-then-insert per (sport, source, day) | `BoardRepository.save/saveDetailed` → `clear()` (read) | ✅ Same-day reruns replace the day. `saveDetailed` already writes `source_scoring`/`derived`; it needs two more columns. |
| Yahoo needs no auth or special header; no CORS | `curl` with default UA, `-A "Java/21.0.4"`, and an empty UA; `-H Origin:` (measured) | ✅ 200 JSON in all three; no `Access-Control-Allow-Origin`. `cache-control: max-age=300`. |
| Yahoo paging and counts | 16 pages of 25 (measured) | ✅ 185 numeric `average_pick` (pages 0–150 full, 175 has 10, 200+ none). Deepest ADP **123.8**. ADPs strictly sorted. Each call 0.38–0.57 s, so a 2 s timeout has headroom. The stop rule ends after 9 calls, not 12. |
| `out=…,auctionvalues` gives 400 | (measured) | ✅ "Invalid player resource auctionvalues requested." |
| `PlayerMatcher` can match Yahoo names | reimplemented `normalize` + `build()`'s name-only path in Python against the local NBA player table (measured) | ✅ **185/185** matched on a unique normalized name, 0 ambiguous. Yahoo's `display_position` is a comma list (`"PG,SG"`) that `Position.fromSleeper` won't parse, so matching runs on the name-only branch. 7 normalized-name collisions exist in the NBA table but none is Yahoo-ranked today. |
| Auction picks carry `metadata.amount` as a string; draft settings | `GET /v1/draft/1415422395308609536` and its 230 picks (measured) | ✅ `type: "auction"`, `budget: 200`, `rounds: 23`, `teams: 10`; 230 picks, `amount` a string on all 230, Σ = 1,982. Per slot left: slot 2 = $17, slot 6 = $1, rest $0 → **$18**. |
| An auction pick's `draft_slot` is the **buyer's** slot; `picked_by` is the buyer | same 230 picks vs `draft_order` and `slot_to_roster_id` (measured) | ✅ 0 of 230 mismatched; each slot holds exactly 23 picks. `round` is only a bucket of 10 sales (rounds 1–23 × 10), **not** the buyer's nth player. |
| Max-bid arithmetic in E3 / US3 | by hand | ✅ 200 − 22 = 178; after $84: 116 left, 22 spots, 116 − 21 = 95. |
| R6's measured rescale numbers | today's Yahoo capture (measured) | ✅ Σ of the 166 positive costs = 2,018.9; floored/padded to 230 → 2,082.9 ≈ 2,083; k = 1,770 / 1,852.9 = 0.955; Wembanyama 1 + 0.955 × 79.9 = $77.3. **Only true if zero costs are floored to $1** (see F9). |
| `allCompletedPicks` and `backfillAdpAtTime` filter on `l.platform = 'SLEEPER'` | `DraftRepository.java:525`, `BoardService.java:313` (read) | ✅ The plan's "add `draft_type` beside it" is placeable in both. |
| `observedDrafts` holds no auction | `config/weights.yml:95-100` (read) | ✅ Two NBA snake drafts and one NFL. The guard is defensive, as R5 says. |
| The poller already re-fetches picks at 2 s while `drafting`; a reset clears picks | `LiveDraftPoller.java:50, 459-463, 485-488, 540-542` (read) | ✅ Budgets derived from `draft_pick` reset for free on a spec 028 clear. `paused` polls at 10 s, not 2 s. |
| `SLEEPER_BASE_URL` is a seam for V8's stub | `application.yml:72`, `SleeperClient.java:21` (read) | ✅ |
| `draft_type` is read by nothing that filters | grep over `main/java` (read) | ✅ Only the upserts, `DraftRepository.format()` and `LeagueController.seats()` (`draftType` in the response, spec 024 A9). |

## Findings

### F1. BLOCKER: `GET /api/drafts/{id}/board` is the real completed board, not the simulator. Refusing it breaks a shipped page

**Evidence (read).** The contract's refusal table and quickstart V6 say
`GET /api/drafts/{id}/board` → `409 auction_draft` because it "would simulate snake order". It doesn't.
`LeagueController.realBoard` (`LeagueController.java:290-321`) is documented as "the picks that actually
happened in this draft … as opposed to `/api/sims`, which always predicts what WOULD happen and never
reads `draft_pick`". It backs `pages/CompletedDraftBoard.tsx` (`getRealDraftBoard`, line 56), which is
what the rail's "Draft board" opens for every complete draft (`destinations.ts:106-110`), plus
`LeagueHome.tsx:377,409` and `StatLeaderboard.tsx:61`. The simulator lives in `SimulationController`
(`/api/sims`, `/api/sims/stream`) and `SimulationService.java:54,129`, neither of which the plan's file
list mentions (it puts "409 auction_draft on board/sim endpoints" in `LeagueController`).

A 409 there would take away the one page that already shows "what the auction did", for the league
this feature exists for. Separately, that page is **already wrong** for an auction, and the plan doesn't
notice: `components/DraftBoard.tsx:121` places cells by `pickNo` through the snake helper
(`snake.ts`'s `pickNoAt`/`isForward`). An auction's `pick_no` is nomination order, and its `draft_slot` is
the buyer (measured above). So every sale lands in the column of whoever "would have picked" that
number in a snake, not the buyer's.

**Amend:**
1. Drop `/drafts/{id}/board` from the refusal table and V6.
2. Refuse the endpoints that really model snake order: `POST /api/sims` and `/api/sims/stream`
   (`SimulationService`), `POST /api/mocks/from-draft/{id}` (F3), and `POST /api/drafts/{id}/picks` (F3).
3. Decide what a complete auction's "Draft board" link shows. The simplest honest answer is to route it
   to `/leagues/:id/adp`, whose sales feed is the real record. If the snake grid is kept, it needs a
   buyer-column layout. Either way, record the choice in R5 as "amended after review".

### F2. HIGH: the web app can't tell a draft is an auction from the data it routes on, so only one of about six entry points gets redirected

**Evidence (read).** The plan changes `pages/DraftPicker.tsx` only ("auction drafts link to the ADP page,
not the sim room"). But:
- `DraftSummary` (`DraftRepository.java:420-423`, mirrored at `api.ts:323`) has **no `draftType`**. The
  picker, the rail and LeagueHome all route from it, so none of them can branch today.
- The rail entries `board` (`draftRoute`/`draftLabel`, `destinations.ts:106-116, 346-360`), `live`
  ("Follow live", `:362-372`, offered while `pre_draft`/`drafting`) and `mock` ("Mock it") all go to snake
  pages for an auction league.
- `LeagueHome.tsx:355,359,377,409` link to `/drafts/:id`, `/drafts/:id/live` and `/drafts/:id/board`.
- `DraftView` and `LiveDraftView` start simulations themselves. Once F1's sim refusal lands, opening
  either for an auction shows a 409 error rather than a redirect.

The one place that already carries the type is `GET /drafts/{id}/seats` (`LeagueController.java:195`,
spec 024 A9), which DraftView already calls.

**Amend:**
- Add `draftType` to `DraftSummary` (Java record + `DRAFT_SUMMARY_COLUMNS` + `api.ts`, same change:
  hard rule).
- Branch `draftRoute`/`draftLabel`, the `live` entry and LeagueHome's links on it.
- Make DraftView/LiveDraftView redirect to `/leagues/:id/adp` when `seats.draftType === 'auction'`, as a
  safety net for old links.
- Decide whether "Mock it" is offered for an auction league. A snake mock of an auction league is
  practice for a format it doesn't use.
- Add these to the Source Code list. Today the list implies one file.

### F3. HIGH: R5's reader list misses five pick-order readers, three of which misbehave badly for an auction

R5 says it was "found by grepping every `draft_pick` reader on main". Grepping every
`drafts.picks(`/`bySleeperId`/`forLeague` caller (read) finds these, which aren't in it:

| Reader | What it does with an auction | Evidence |
|---|---|---|
| `MockDraftService.createSessionFromDraft` ("fork a live draft into a mock") | Copies every pick into a snake mock and **re-derives each pick's slot and round with `DraftSlot.slot(pickNo, teams, reversal)`**. That hands each sale to the wrong seat. It's only blocked for non-`drafting` status, so it works mid-auction. | `MockDraftService.java:238-336`; endpoint `MockDraftController.java:106` |
| `LeagueController.recordPick` (manual pick, `drafting`/`paused`) | Computes `slot = DraftSlot.slot(pickNo, …)` and `manager = slotToManager[slot]`, so it writes the sale to the snake-order manager, and the new `amount` is null. Budget cards would move money to the wrong team. The 403 seat check also tests the wrong seat. | `LeagueController.java:619-714` |
| `DraftAndAdpJoin.join` (stats leaderboard) | Beyond grades, it publishes each drafted player's `pickNo`/`round` plus the blend ADP as of draft day. For an auction those are nomination number and nomination bucket, shown as if they were draft slots. | `DraftAndAdpJoin.java:150-160` |
| `TradeVerdictService` | Resolves traded draft picks to players by `(round, draftSlot)`. For an auction's `round` (a bucket of 10 sales), a traded "round 1" pick resolves to whatever that slot bought among the first 10 nominations. Low impact: a startup auction's own picks are rarely traded. | `TradeVerdictService.java:117-129` |
| `SeasonStartService.gradeSummary` | Calls `DraftGradesService.read`. It's covered if grades come back unavailable (F8), but it's a second caller the plan should name so the test covers it. | `SeasonStartService.java:162-166, 345-355` |

**Amend:** add all five rows to R5's table. Refuse fork-from-draft and manual pick for auction drafts
with the same `auction_draft` 409. In `DraftAndAdpJoin`, return no pick/round for an auction (or the
sale amount instead, flagged). Note TradeVerdict as reviewed: no change, or a "pick in an auction draft"
unresolved reason. Put the 409 in one helper next to `DraftRepository.AUCTION` so the four refusals
share one rule. `DraftRow` doesn't carry `draft_type` (`DraftRepository.java:244-246`), so the helper
needs `format()` or a new field.

### F4. HIGH: "a sold player the app has no record of" can't be named. There's no Sleeper player cache, and `draft_pick` stores neither his Sleeper id nor his name

**Evidence (read).** data-model E2 says the name comes "from the Sleeper player cache, falling back to
'Unknown player'". No such cache exists: the only `*Cache` class is `SeasonBoxCache`, and the player
table *is* the local copy of Sleeper's players. A pick whose player didn't resolve has
`player_id = null` (`PickMapper.java:263`), and nothing else about him is stored. The raw pick does carry
`metadata.first_name`/`last_name` (measured: `"first_name": "Trae", "last_name": "Young"`). So US3 AS4
("using the name Sleeper sends") always falls back to "Unknown player". The quickstart has no step that
would catch it (F14).

**Amend:** V39 also adds `draft_pick.player_name text null`, filled by `PickMapper` from
`metadata.first_name + last_name`. Optionally add `sleeper_player_id text null` so a later player ingest
can resolve it. Write it with plain `= excluded.` semantics. Add a test pick with an unknown id to the
replay IT.

### F5. HIGH: unmatched Yahoo players (FR-003, edge case "listed as unmatched with his Yahoo values") are never persisted, so the view can't list or count them

**Evidence (read).** E1: unmatched players "aren't stored (there's no `player_id` to key on). They're
returned in the capture result … and logged." The capture result only goes to whoever called
`POST /api/ingest/adp/yahoo` or the daily step. But the contract's `sources[yahoo].unmatched` and the
spec's edge case need them **on every page view**, on another day. `adp_snapshot.player_id` is
`not null` with an FK (`V1__init.sql:28`). `daily_capture` can't hold it either: its `kind` check allows
only `PLAYERS`/`BOARD` (`V24__data_refresh.sql:56`), and `seasonProjections` deliberately records no row.

**Amend:** pick one and put it in V39. The simplest is a small `adp_unmatched (sport, source,
captured_on, name, team, positions, adp, avg_cost, percent_drafted)`, replaced per capture like
`adp_snapshot`. The compare response then returns them as rows with `playerId: null` (the contract
already makes every field nullable). Count today: 0 (measured above), so this is about the next rookie
or name change, not today's data. That's exactly the "silently dropped" case FR-003 exists for.

### F6. HIGH: US2 acceptance scenario 2 ("a Yahoo-favoured player … never moves later") is false for blended **rank**. On today's data, 20 players break it at the default settings

**Proof.** For one player, `blend = dyn + w·(yahoo − dyn)` with `w = t·min(|g|/G, 1)` is linear in `t`,
and its slope `min(|g|/G,1)·(yahoo − dyn)` is negative when Yahoo ranks him earlier. So his blended
**value** never increases as trust rises (true, and trivially testable). His blended **position** is
his rank among everyone else's blended values, and other Yahoo-favoured players with bigger gaps move
faster. Counterexample (G = 30): P dyn 12 / Yahoo 11, Q dyn 40 / Yahoo 2. At t = 0, P (12) is ahead of Q
(40). At t = 0.75, P = 12 − 0.75·(1/30)·1 = 11.975 and Q = 40 − 0.75·38 = 11.5, so Q passes P and P
moves **later**.

**Measured on today's captures** (817 dynasty-ranked players, 185 Yahoo-ranked, blended rank 0 → t):
Yahoo-favoured players whose blended rank got later: **21** at t = 0.25, **20** at t = 0.75 (default),
25 at t = 1.0. Examples at 0.75: Andrew Nembhard (dyn 125.7, Yahoo 117.0) goes from rank 125 to 144;
P.J. Washington goes from 132 to 149. They're all in Yahoo's compressed tail (F7), which is where
bigger-gap players overtake them.

The spec says "blended position", and FR-007 says "blended position per player", so the spec's own
Independent Test as written will fail. This is the shape of recurring bug class #1: the
preference-ordering test is the one that matters, and it has to assert something true.

**Amend:**
- Restate US2 AS2 as: his **blended value** moves earlier or stays put as trust rises; and, against any
  player who isn't Yahoo-favoured, he never loses ground. Both are provable.
- Write the unit test against those two properties.
- Say in research R6, with the counterexample, that rank among Yahoo-favoured players can reorder, so
  nobody "fixes" the test later by weakening it.
- AS1 ("trust 0 = dynasty order exactly") needs a stated tie-break. 22 dynasty ADP values are shared by
  two or more players today (measured). Suggest dyn, then redraft, then name, and use the same key in
  both orders.

### F7. HIGH: the gap-tint cutoff hides the real signal and keeps the artifact, and the blend ignores the artifact entirely

**Evidence (measured, today's Yahoo pages).** Yahoo's `average_pick` is averaged only over drafts that
took the player, so the tail is compressed. Yahoo rank 100 has ADP 103.4, but ranks 120, 150 and 185
have ADP 109.6, 115.6 and 123.8. 57 of the 185 have `percent_drafted < 0.5`, all with ADP ≥ 107.0, and
19 have `average_cost` 0.0. **The artifact is on Yahoo's side, below about rank 100 /
`percent_drafted` 0.5.**

R6's rule is "no tint when either ADP is past the deepest Yahoo ADP (≈123)". At gap ≥ 20 that removes
**64 of 83** green rows. It catches the low-% tail, but it also removes 100%-drafted veterans, which are
exactly the "vet discount" the prototype sorted for: LeBron James (Yahoo 38.9, 1.00 drafted, dynasty
157.3), Damian Lillard (67.0 / 1.00 / 135.7), Rudy Gobert (74.8 / 1.00 / 144.0) and Paul George
(78.0 / 1.00 / 131.3). For those, the dynasty ADP is deep *because* the market disagrees.

The blend (R6) applies no exclusion at all. A 2%-drafted player with Yahoo ADP 110 and dynasty 400 gets
w = 0.75 and a blended value of 182.5: a 217-pick jump on a handful of drafts, which then sets his
Blend $. That's the "looks more certain than it is" failure in the price guide used live.

**Amend:**
- Base the cutoff on Yahoo's own reliability: "Yahoo value counted only when `percent_drafted ≥` a
  threshold". Make it hand-set, labelled ARBITRARY like `MIN_KEPT_SHARE_OF_STORED`, and suggest 0.5
  because that's where the compression starts in today's data, stated as such.
- Apply the **same** rule to the tint **and** the blend. A player under the threshold is "Yahoo
  unranked" for blend purposes, and the row says why.
- Keep the deepest-ADP figure in `sources[yahoo]` as context, not as the rule.
- Re-run the 83/21 green/red counts under the new rule for verification.md, and update the spec edge
  case's wording with an "amended after review" note.

For scale: at gap ≥ 20, filtering on `percent_drafted ≥ 0.5` alone keeps 40 green rows. The
deepest-ADP rule keeps 19.

### F8. MEDIUM: the grades refusal is specified as a field the payload doesn't have

**Evidence (read).** The contract says grades return "the existing grades payload with
`graded: false, reason: "auction"`". The existing record is
`DraftGrades(…, boolean available, String reason, …)` (`DraftGradesService.java:70`), with reason
constants `NOT_CONFIGURED`/`DRAFT_NOT_COMPLETE`/`NO_SCORED_WEEKS` (`:52-54`). It's mirrored as a closed
union `DraftGradesReason` at `api.ts:260`, and each reason has its own sentence in a `switch` in
`draftGrades.ts:10-22`. A `graded` field would be a second flag beside `available`. A lowercase
`"auction"` value wouldn't be in the TS union, so it would fall to the default sentence: the
stale-type failure from the hard rules.

**Amend:** `available: false, reason: "AUCTION_DRAFT"` (new constant), returned **before** the
expensive work in `read()`. Add it to `DraftGradesReason` and give it its own sentence in
`draftGrades.ts`. This also covers `SeasonStartService.gradeSummary` (returns null when unavailable) and
`DraftAndAdpJoin`'s `DraftGradesState`. Fix V6's expectation to match.

### F9. MEDIUM: the rescale breaks on Yahoo's $0 costs and on a missing Yahoo capture, and SC-005's tolerance is defined two different ways

**Evidence (measured + arithmetic).**
- **Zero costs.** 19 of the 185 Yahoo-ranked players have `average_cost` = 0.0 (for example Al Horford,
  ADP 107.9). R6 says to take the costs "sorted descending" and "pad with $1". With the zeros kept, they
  sit *below* the $1 padding, so sorting before vs after padding gives different curves. Each zero
  scales to 1 + k·(0 − 1) = **$0.03**: a Blend $ under the minimum bid. R6's own "Σ ≈ 2,083" is only
  reproducible if zeros are floored to $1 (2,018.9 + 64 = 2,082.9). With zeros kept it's 2,063.9.
  The plan silently assumes a floor it never states.
- **No capture.** With Yahoo unavailable the curve is all padding, so Σ(c − 1) = 0 and
  k = (budget·teams − N)/0. Infinity, or NaN in JS.
- **Tolerance.** The spec (AS3, SC-005) says "within $1 per roster spot". For 10 × 23 that's ±$230.
  Quickstart V7 checks "$2,000 ± 23", which is $1 per *team* roster spot. Also, before rounding the sum
  is exactly `teams × budget` by construction, so the check only tests rounding. That's fine, but it
  should say so rather than read as validation of the guide.

**Amend:**
- Floor each cost at $1 before sorting (`c = max(c, 1)`), and state it in R6.
- When there's no Yahoo capture, there's no Blend $ (the column says "unavailable").
- Pick one tolerance. Suggest ±$0.50 × N for whole-dollar rounding, or display one decimal.
- Note in R6 that SC-005 is an identity check, not evidence the guide is right.

### F10. MEDIUM: honesty. The labels overclaim what the rescale does, and the capture date overclaims Yahoo's freshness

**Evidence.**
- **The rescale.** R6 measures k ≈ 0.955: a uniform 4.5% shrink of the surplus. It makes the total
  match and does nothing about the effect the spec names as the cause of the miss (10 teams × 23 spots
  vs ~12 × 13 concentrating money at the top). FR-013's "Blend $ is rescaled from a different league
  shape" and the plan's "rescaled to this league's teams, budget and roster spots" both read as if the
  shape was modelled.
- **Yahoo freshness (measured).** The top-5 Yahoo rows fetched on 2026-10-21 (`ypub.json`) and today are
  identical to the decimal: Jokić 1.4/77.0, Wembanyama 2.0/80.9, Dončić 3.4/70.7, SGA 3.9/66.8,
  Cunningham 6.0/64.0. Yahoo may have largely stopped moving now the season has begun. Only five rows
  were comparable, so this is a hint, not a measurement of the whole list. A daily capture stamped
  "captured 2026-10-23" then implies data from that day.
- **Format (read, not measured).** The `nba.l.public` context league in the payload reports
  `"scoring_type": "head"`, `"scoring_label": "H2H Cat"`. Yahoo's NBA default is categories, while these
  leagues score points. Whether `draft_analysis` mixes formats isn't published. The spec's labels say
  "redraft" but not "categories".

**Amend:**
- Reword the Blend $ note to say what it does: "Yahoo's price curve, scaled so it adds up to your
  league's $2,000. It doesn't model how fewer teams and deeper rosters push money to the top. In the
  one auction measured, the top sold 25–50% above it."
- Label the Yahoo date "fetched on". Optionally show "unchanged since <date>" when a capture equals the
  previous one, which is cheap to compute at write time.
- Add "Yahoo's leagues are mostly category leagues (assumed, not verified)" beside "redraft".

### F11. MEDIUM: the dynasty capture inherits the season-projection gates, which switch it off in the offseason, exactly when dynasty drafts happen

**Evidence (read).**
- The daily step calls `seasonProjectionIngest.refresh(sport, season, false)` only when
  `ProjectionSeason.current` finds a newest-season NBA league that isn't `COMPLETE`
  (`DailyRefreshService.java:179-187`, `ProjectionSeason.java`). After the NBA playoffs, and until the
  next season's leagues are ingested, there's no capture. Rookie drafts and dynasty startups (June to
  September) are the main use of a dynasty ADP.
- `refresh` also skips the fetch entirely when the stored set is younger than six hours
  (`SeasonProjectionIngestService.java:76-80`).
- The `gp ≠ 1.0` guard **returns mid-loop** (`:99-105`). A capture woven into that loop would be lost
  whenever the projection lines are rejected, even though the ADP fields are unaffected.

The spec's football edge case also says ADP compare "works the same way with football sources where
they exist". The plan makes both Sleeper sources NBA-only (R1), so an NFL league gets three
"unavailable" columns. That's a spec/plan contradiction nobody has decided.

**Amend:**
- Run the ADP capture as its own pass over the fetched payload, before and independent of the
  projection-line validation, with its own shrink guard.
- Give it its own daily step (or the Yahoo step's gate) keyed on "any NBA league exists", not "a
  current season is in progress".
- Record the football decision in the spec with an "amended after review" note: either NFL is out of
  v1 (rail entry NBA-only, the page says so) or `adp_dynasty` gets measured on the NFL season endpoint.

### F12. MEDIUM: the budget/amount write paths aren't the ones the plan names, and the defaulted overloads would wipe them

**Evidence (read).**
- **The poller.** The plan says "`LiveDraftPoller`: pass budget through on draft upsert". The poller
  never upserts the draft row. It calls only `updateStatus`, `updatePickTimer` and
  `updateSlotToManager`, on purpose, because `upsert` needs fields it doesn't hold
  (`DraftRepository.java:198-222`, `LiveDraftPoller.java:466-480`). Budget is written by
  `LeagueIngestService.ingestDraft` only (`:195`). Since `/track` needs an ingested draft row
  (`membership.visibleDraft`), that's enough. Either drop the poller row or add an `updateBudget` beside
  `updatePickTimer`, with the same "settings present" rule.
- **Overloads.** `DraftRepository.upsert` has 9- and 10-arg overloads that default later columns. The
  10-arg one is already documented as writing `pick_timer_seconds = NULL` on conflict
  (`DraftRepository.java:44-48`). That's the "optional param that encodes a rule" trap, which has shipped
  three times (memory). Adding `budget` as a 12th param with `budget = excluded.budget` repeats it.
- **PickRow.** `PickRow` gains `amount`. There are ~45 `new PickRow(` sites in tests plus 4 in main
  (grep). A convenience constructor defaulting `amount = null` and an `amount = excluded.amount` conflict
  rule would let the manual-pick path (`LeagueController.java:686`) null a stored sale price, if F3
  doesn't refuse it first.

**Amend:**
- Say explicitly that `amount` is written `= excluded.amount`. Unlike `adp_at_time`, Sleeper sends it
  on every tick and it's the truth, so don't coalesce.
- No defaulted `amount`/`budget` overloads in main code. Tests can use a fixture helper.
- Correct the poller line in the Source Code list.

### F13. MEDIUM: contract details that don't match how the app's gating and identity work

**Evidence (read).**
- **ESPN refusal.** The contract says "404 if … it's an ESPN league (`LeagueAvailability` page key
  `adp`)". The page-key path throws `PlatformNotSupported`, which `ErrorHandler.java:30-35` turns into
  **422** `{"error": "PLATFORM_NOT_SUPPORTED", …}`, not 404. The gate also needs more than a key: an
  entry in `LeagueAvailability.ROUTES` mapping the key to the URL segment (`"adp-compare"`, since
  `pageKeyOf` matches the first path segment after `/api/leagues/{id}/`, `LeagueAvailability.java:199-207`),
  a `FIXED` message, the `DestinationKey` union in `destinations.ts:46`, and `PlatformGate page="adp"`
  in `App.tsx`.
- **Identity.** The contract has `GET …/adp-compare?user={id}` and says it "follows the existing
  pattern (`LeagueController.liveStream` comment)". That comment is the *exception*: EventSource can't
  set headers. Every ordinary fetch sends `X-Sleeper-User` through `apiFetch`, and `PlatformGuard`
  reads only the header (`PlatformGuard.java:71`). A `?user=` route would see a null user in the guard.
- **Is ESPN exclusion even right?** The ADP sources are sport-wide. Only the auction half needs a
  Sleeper draft. That's a choice, so make it explicitly in R8.

**Amend:**
- Use the header.
- Document 422 for ESPN, and 404 only for not-visible.
- List the four gating touch-points in the Source Code section.

### F14. MEDIUM: acceptance criteria the quickstart never exercises

| Criterion | Gap |
|---|---|
| SC-001: 20 players' values match the provider's | V1 counts rows only. Add a 20-row spot check: Sleeper `adp_dynasty`/`adp_std` from the raw payload, and Yahoo's four fields from the JSON, against `adp_snapshot` for the same day. |
| US3 AS4: unknown sold player named from Sleeper's data | No step, and not implementable as planned (F4). |
| US4 AS2: a manager with **only** auction history shows no fitted snake history | V5 checks identical profiles only. Pick a "Legacy Ball Knowers" manager who isn't in the snake league and assert `drafts_observed = 0` and provenance "no history". |
| US2 AS4: Yahoo-unranked player keeps dynasty position *and the view says why* | No step. |
| US1 AS3: raising the threshold removes a tint | Unit test only, implied. Name it. |
| Edge: source fails, so the last good capture shows with its date; no capture shows "unavailable" | No step. A test with a stubbed failing Yahoo client is cheap. |
| Edge: sale with no price, counted and shown as "price missing" | No step. Add a null-`amount` pick to the replay IT. |
| FR-014: reachable from the draft room; works for snake/auction/complete | V7/V9 cover the page, not the draft-room link. |
| V6 | Expects a 409 on `/board`, which F1 says must not happen. Replace with sims / fork / manual-pick refusals. |

### F15. LOW: key team budgets by `draft_slot`, and handle an unset draft order

**Evidence.** E3 keys teams on `slot_to_manager` and each pick's `manager_id`. `PickMapper` prefers
`picked_by` (`PickMapper.java:251-255`), so a co-owner's purchase lands on a different `manager_id` than
the seat's. In the measured draft `picked_by` matched the slot's owner on 230/230 and `draft_slot` was
the buyer on 230/230, so slot is the safer key. Also, before the commissioner sets the order,
`draft_order` is null and `slot_to_manager` is empty (`LiveDraftPoller.java:625-655`). AS1 ("every team
shows $200, 23 spots, $178") then renders no cards.

**Amend:** compute budgets per `draft_slot` 1..`teams`, and attach the manager name when known.

### F16. LOW: Yahoo client details to pin down

**Evidence (measured).**
- Without `?format=json_f` the same URL returns `application/xml` (200). The matrix-parameter URL
  (`;out=settings/players;…;out=draft_analysis`) is picky: one wrong `out` value gives a 400. Assert the
  exact built URL in a unit test so `RestClient`'s URI encoding can't silently change it.
- Every numeric field is a **string**. `percent_drafted` and `average_cost` are `"-"` for unranked
  players (215 of 400 rows have `"-"` for percent).
- The payload has both `average_*` and `preseason_*` (Durant 15.6 vs 15.8). Pick one and record which.
- `nba.l.public` resolves to some private 4-team league (`478.l.101`) as context. It's harmless today,
  and it's the kind of thing that changes, so the shape check should cover it.
- If someone wires the Yahoo step to `daily_capture` the way `PLAYERS` is, the `kind` check constraint
  (`PLAYERS`/`BOARD` only) rejects it. Keep it gate-by-`latestCapture == today`, as the
  season-projection step does.
- "12 calls per refresh" is 9 in practice: the stop rule fires on page 200.

### F17. LOW: smaller rule choices to record

- **Deny-list vs allow-list.** `draft_type <> 'auction'` still lets Sleeper's `linear` type through,
  which `DraftSlot` also mis-models. Name the helper for what it means (`modelsSnakeOrder(type)`) and
  record whether `linear` is in or out.
- **Yahoo-ranked, dynasty-unranked.** No such player today (0 of 185, measured), but R6 doesn't define
  the blend for him. Suggest: no blend; shown with "no dynasty ADP".
- **Table growth.** `adp_snapshot` grows by about 1,900 rows a day (817 + 903 + 185) on top of
  `sleeper_search_rank`. Fine for now, but note it beside V2's precedent.

## Summary

| Severity | Count | Findings |
|---|---|---|
| BLOCKER | 1 | F1 |
| HIGH | 6 | F2, F3, F4, F5, F6, F7 |
| MEDIUM | 7 | F8, F9, F10, F11, F12, F13, F14 |
| LOW | 3 | F15, F16, F17 |

Biggest themes:
- **The auction exclusion is aimed at the wrong endpoint** (F1). It misses the fork, manual-pick and
  leaderboard paths (F3), and the web app has no `draftType` to route on (F2).
- **Two planned behaviours can't work with what's stored** (F4, F5).
- **The blend's acceptance property is false as worded** (F6), and its tail handling trusts the noisiest
  Yahoo data most (F7).

None of these change what the feature *is*. All are about how it's built and tested, so they can be
taken into the plan in place with dated "amended after review" notes.
