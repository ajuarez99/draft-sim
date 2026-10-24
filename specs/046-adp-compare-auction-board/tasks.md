---
description: "Task list for spec 046, ADP compare + live auction board"
---

# Tasks: ADP compare + live auction board

**Input**: `specs/046-adp-compare-auction-board/`: spec.md, plan.md, research.md, data-model.md,
contracts/adp-compare-api.md, quickstart.md and plan-review.md. Each of the first five has an
**"Amended after review"** section. Where an amendment and the original text disagree, **the
amendment wins**.

**Tests**: Included. The success criteria are hand-computation equalities (SC-002, SC-004,
SC-005), and the plan review (F6, F9, F14, F16) asks for specific tests.

**Paths**: relative to the worktree root. `B` = `backend/src/main/java/com/ballknowers/draftsim`,
`BT` = `backend/src/test/java/com/ballknowers/draftsim`, `R` = `backend/src/test/resources`,
`W` = `web/src`.

**Story order**: US4 (P1) runs before US1 (P1). It must land before any auction draft is ingested
with prices, so a fitted profile is never polluted, even locally.

## Phase 1: Setup

- [X] T001 Record baselines **before** any code change, in
  `specs/046-adp-compare-auction-board/baseline/`. *Amended after analyze (C1)*: every step that
  ingests or fits runs against a **scratch database**: a `pg_dump` copy of 5433's `draftsim`,
  restored into a throwaway cluster on 5436. **Never** against 5433, which other sessions share.
  Point this worktree's backend at it with `SPRING_DATASOURCE_URL`, and record the port in the
  README.
  1. Run `./gradlew test` and record the totals **and the skip count**. Postgres must be up on
     5433, or the ITs skip silently.
  2. Dump every NBA manager's fitted profile (`feature_json`, drafts observed) from the local DB to
     `profiles-before.json`. Do it after ingesting "Legacy Ball Knowers"
     (`POST /api/ingest/all/1415422393706455040`) with **today's** code, which already shows
     whether the auction pollutes profiles. That's evidence for US4.
  3. Write the two dumps and a one-paragraph `README.md` saying what was run and when.
- [X] T002 [P] Save the recorded fixtures under `R/fixtures/046/`:
  - `auction-draft.json`: `GET https://api.sleeper.app/v1/draft/1415422395308609536`
  - `auction-picks.json`: its `/picks` (230 picks, Σ amount 1,982)
  - `sleeper-nba-2026-season.json`: the season projection payload with the client's `position[]`
    params
  - `yahoo-nba-page-0.json` and `yahoo-nba-page-175.json`: Yahoo pages at `start=0` and
    `start=175`. The second includes `"-"` values.

  Record each fetch's date in `R/fixtures/046/README.md`. These are read-only GETs.

## Phase 2: Foundational (blocks every story)

- [X] T003 Create `backend/src/main/resources/db/migration/V39__adp_compare_auction.sql`, exactly
  per data-model.md (original + amended). Re-check first that main's highest is still V38.
  - `adp_snapshot`: add `avg_cost numeric(6,2) null` and `percent_drafted numeric(5,4) null`.
  - `draft`: add `budget int null`.
  - `draft_pick`: add `amount int null`, `player_name text null` and `sleeper_player_id text
    null`.
  - New table `adp_unmatched`: `id bigserial pk`, `sport text not null`, `source text not null`,
    `captured_on date not null`, `name text not null`, `team text null`, `positions text null`,
    `adp numeric(6,2) null`, `avg_cost numeric(6,2) null`, `percent_drafted numeric(5,4) null`,
    and an index on `(sport, source, captured_on)`.
  - Comment the file in V2's style: why columns, not a table, and the unmatched-player reason
    (F5).
  - No defaults, no backfill.
- [X] T004 In `B/store/DraftRepository.java`:
  1. Add `public static boolean modelsSnakeOrder(String draftType)` returning
     `!"auction".equals(draftType)`. Javadoc: this is the one rule; SQL filters spell it as
     `d.draft_type <> 'auction'` and point here; `linear` is deliberately unchanged (research R5
     amended).
  2. Add `draftType` to `DraftRow` (all constructors and the row mapper) and to `DraftSummary` and
     `DRAFT_SUMMARY_COLUMNS`.
  3. Add `budget` to the draft `upsert`. **No overload may default it**: update every main-code
     caller to pass the real value or an explicit `null` with a comment (F12).
  4. Add `Integer amount, String playerName, String sleeperPlayerId` to `PickRow`. Write them in
     `upsertPicks`/`replacePicks` with `= excluded.<col>`, never `coalesce`, and read them in
     `picks()`.
  5. Add `updateBudget(long draftId, Integer budget)` beside `updatePickTimer`.
  6. Fix every test `new PickRow(`/`DraftRow(` call site with a test-only fixture helper in
     `BT/store/` (≈45 sites). Main code gets no defaulting constructor.
- [X] T005 [P] In `W/api.ts`, add `draftType: string` to the `DraftSummary` type in the same
  change as T004's record (hard rule). Grep for test fixtures that build `DraftSummary` and add
  the field.
- [X] T006 In `B/ingest/PickMapper.java`:
  - Read `metadata.amount` (a string on the wire) into `amount`. Parse defensively: a missing,
    blank, unparseable or negative value becomes `null`, and a negative one is logged.
  - Set `playerName` = trimmed `first_name + " " + last_name` (null if both are blank).
  - Set `sleeperPlayerId` = the raw `player_id`.
  - Unit test in `BT/ingest/PickMapperTest.java`: `"22"` → 22; missing → null; `"abc"` → null;
    `"-5"` → null; the name is built from the metadata.
- [X] T007 In `B/ingest/LeagueIngestService.java` `ingestDraft`, read `settings.budget` (null when
  absent) and pass it to `upsert`. In `B/ingest/LiveDraftPoller.java` `pollOnce`, call
  `drafts.updateBudget` when `settings.budget` is present, beside `updatePickTimer`. **No draft
  upsert from the poller** (F12).
- [X] T008 IT `BT/ingest/AuctionIngestIT.java`: replay `auction-picks.json` + `auction-draft.json`
  through the ingest path into Postgres. Assert:
  - 230 rows, Σ amount = 1,982
  - `draft.budget = 200`, `draft_type = 'auction'`
  - every `player_name` is non-null
  - a re-ingest with one amount changed overwrites it (`= excluded`, F12)

## Phase 3: User Story 4 – Auction history doesn't corrupt snake models (P1)

**Goal**: no snake-order model reads auction picks. **Independent test**: profiles are identical
with and without the auction (SC-004).

- [X] T009 [US4] In `B/store/DraftRepository.java` `allCompletedPicks`, add
  `and d.draft_type <> 'auction'` beside the spec-035 platform filter, with a comment pointing at
  `modelsSnakeOrder`.
- [X] T010 [US4] In `B/ingest/BoardService.java`:
  - Add the same filter to the `adp_at_time` backfill subquery.
  - In `observedPickNumbers`, skip and `log.warn` when the configured draft's type isn't snake
    order.
- [X] T011 [P] [US4] Create `B/engine/AuctionGuard.java`, one helper that throws a
  `ResponseStatusException`-style 409. Body:
  `{"error":"auction_draft","message":"This is an auction draft. The simulator models snake pick order, which an auction doesn't have."}`.
  Wire it into `ErrorHandler` if that's how 409 bodies are shaped there; check first.
- [X] T012 [US4] Apply `AuctionGuard` to:
  - the simulation start path (`B/engine/SimulationService.java`, or its controller, whichever
    resolves the draft)
  - `B/mock/MockDraftService.java` `createSessionFromDraft`
  - `B/api/LeagueController.java` `recordPick`

  **Do not touch** `LeagueController.realBoard` (F1). *Analyze U1*: first check whether `/api/sims`
  carries a draft id at all. If it doesn't, put the guard where a draft is resolved (or skip it,
  with a note in code-review.md), and rely on T033's redirect.
- [X] T013 [US4] In `B/engine/DraftGradesService.java`:
  - Add the constant `AUCTION_DRAFT`, returned as `available: false, reason: "AUCTION_DRAFT"`
    **before** any expensive work in `read()`.
  - In `W/api.ts`, add `'AUCTION_DRAFT'` to `DraftGradesReason`.
  - In `W/draftGrades.ts`, give it its own sentence: "Auction drafts aren't graded. Grades compare
    each pick with its draft slot, and an auction has no slots."
- [X] T014 [US4] In `B/engine/DraftAndAdpJoin.java`, set `pickNo` and `round` to null for picks
  from an auction draft. In `W/pages/StatLeaderboard.tsx`, render "auction" in that cell when
  `pickNo` is null and the draft is an auction. Check the `api.ts` type allows null.
- [X] T015 [US4] Tests:
  - IT `BT/profile/AuctionExclusionIT.java`: a league with one snake and one auction draft
    (fixture). Fitted profiles are field-for-field equal to fitting with only the snake draft. A
    manager with only auction history has `drafts_observed = 0` (US4 AS2).
  - MVC tests for the three 409s and the grades reason, plus a test that `GET
    /api/drafts/{id}/board` still returns 200 for an auction draft.

## Phase 4: User Story 1 – Compare ADP sources (P1) 🎯 MVP

**Goal**: the compare view with three sources, provenance and gap tints. **Independent test**:
quickstart V1, V2, V7 (tint part) and V10 SC-001.

- [X] T016 [P] [US1] In `B/store/BoardRepository.java`, add source constants `SOURCE_SLEEPER_DYNASTY
  = "sleeper_dynasty"`, `SOURCE_SLEEPER_REDRAFT = "sleeper_redraft"` and `SOURCE_YAHOO = "yahoo"`.
  Add:
  - a writer that replaces one `(sport, source, captured_on)` capture with `avg_cost`,
    `percent_drafted`, `source_scoring`, plus that day's `adp_unmatched` rows, **in one
    transaction**
  - a reader returning the latest capture per source with its date
- [X] T017 [US1] Create `B/ingest/SleeperAdpCaptureService.java`:
  - Input: the **raw** season payload.
  - For each row with `stats.adp_dynasty < 999`, write a `sleeper_dynasty` row
    (`source_scoring='dynasty'`). For `adp_std < 999`, write a `sleeper_redraft` row
    (`'redraft'`). Map Sleeper player ids to local ids. Unmapped ids are counted and logged, not
    written. Values ≥ 999 are never written.
  - Shrink guard: reuse `SeasonProjectionIngestService.MIN_KEPT_SHARE_OF_STORED`. Don't re-declare
    it.
  - Unit test with `sleeper-nba-2026-season.json`: about 817 dynasty rows, and stub rows (no
    `pts`) included.
- [X] T018 [US1] In `B/ingest/SeasonProjectionIngestService.java` `refresh`, pass the fetched raw
  list to `SleeperAdpCaptureService` **before** the `gp`/`pts` validation loop. Then a rejected
  projection fetch still captures ADP (F11). Test: a payload with `gp = 2` stores no projection
  lines but does store ADP rows.
- [X] T019 [US1] Create `B/ingest/YahooAdpClient.java` (RestClient, 2s timeout):
  - Build `…/fantasy/v2/league/nba.l.public;out=settings/players;position=ALL;start={n};count=25;sort=DA_AP;out=draft_analysis?format=json_f`.
  - Parse string numerics, with `"-"` → null. Read `average_pick`, `average_cost` and
    `percent_drafted`, **not** `preseason_*`.
  - Stop at the first page with no numeric `average_pick`, with a hard cap of 400 players.
  - Shape check: `fantasy_content.league.players` must exist, or throw.
  - Unit test asserting the **exact** built URL string (F16) and parsing of both fixture pages.
- [X] T020 [US1] Create `B/ingest/YahooAdpService.java`:
  - Match with `PlayerMatcher.build(Sport.NBA, players)` on (name, position, team).
  - Write the `yahoo` capture (`source_scoring='redraft'`). Write unmatched players to
    `adp_unmatched`.
  - Use the same shrink guard. On any client failure, keep the previous capture and return
    `rejectedReason`.
  - Result record: `{fetched, rows, matched, unmatched, rejectedReason}`.
  - Test with a stub client that throws: the previous capture is untouched.
- [X] T021 [US1] In `B/refresh/DailyRefreshService.java`, add two best-effort steps in the
  `SEASON_PROJECTIONS` style, each gated by `latestCapture(source) == today`, **not**
  `daily_capture` (F16):
  - `ADP_CAPTURE`: gated on "any NBA league exists", not `ProjectionSeason.current`. It makes its
    own `season()` call for the newest NBA league's season unless the projection step already
    captured today.
  - `YAHOO_ADP`

  Add an outcome enum value per step if the pattern needs it. Tests in the existing
  `DailyRefreshService` test style.
- [X] T022 [P] [US1] In `B/api/IngestController.java`, add admin `POST /api/ingest/adp/yahoo?sport=nba`
  and `POST /api/ingest/adp/sleeper?sport=nba`, following that controller's existing admin guard.
- [X] T023 [US1] Create `B/engine/AdpCompareService.java` and `B/api/AdpCompareController.java`:
  - `GET /api/leagues/{sleeperLeagueId}/adp-compare`, identity from `X-Sleeper-User`,
    `LeagueMembership.canSee`, 404 when not visible. No platform gate (R8 amended).
  - Response records per contract (original + amended): `sources` (always all three keys, with
    `capturedOn` possibly null, `ranked`, `scoring`; for Yahoo also `unmatched`, `deepestAdp`,
    `minPercentDrafted: 0.5`), `players` (union of the latest captures plus unmatched rows with
    `playerId: null`), `costCurve` (floored at 1.0, sorted descending, empty without a capture),
    and `draft` (null without a draft row).
  - **No `Map.of`** anywhere on this path: every field is nullable.
  - In this task, `draft` carries only `sleeperDraftId`, `type`, `status`, `teams`, `rounds` and
    `budget`. Budgets and sales come in US3.
- [X] T024 [P] [US1] In `W/api.ts`, add the `AdpCompareResponse`, `AdpSource`, `AdpPlayer` and
  `AdpDraft` types, mirroring T023's records field for field, plus
  `getAdpCompare(sleeperLeagueId)` via `apiFetch`, in the same change as T023.
- [X] T025 [P] [US1] Create `W/adpBlend.ts` (pure) with:
  - `gap(p)` = `yahooAdp − dynasty`
  - `yahooReliable(p, minPct)`
  - `tintOf(p, threshold, minPct)` returning `'yahoo' | 'sleeper' | null`
  - `sortKey` with the tie-break dyn → redraft → name

  Tests in `W/adpBlend.test.ts`:
  - Durant-like is green, Dybantsa-like is red.
  - A player under 50% drafted is never tinted.
  - Raising the threshold removes a tint (US1 AS3, named test).
  - LeBron-like (100% drafted, Yahoo 38.9, dyn 157.3) is green.
- [X] T026 [US1] Create `W/pages/AdpCompare.tsx`:
  - Page header and the source provenance strip ("fetched on", ranked count, "unavailable" when
    null).
  - The table: player, team, position chips, years/rookie, dynasty, redraft, Yahoo ADP, Yahoo $,
    gap text.
  - Search, position filter, rookies toggle, sort by each column and by gap either way.
  - Gap threshold slider (default 20).
  - Unreliable Yahoo values greyed, with "drafted in N% of Yahoo leagues". Unmatched rows marked
    "not matched to a player".
  - The honesty notes from FR-013: redraft, categories assumed.
  - Design: the repo's dark card/tinted-pill language (`styles.css` tokens). Dense table rows,
    tints via `color-mix`, and the gap always spelled out in text. Works at 375px.
  - RTL test in `W/pages/AdpCompare.test.tsx`.
- [X] T027 [US1] Add the route `/leagues/:sleeperLeagueId/adp` in `W/App.tsx` (`KeyedByLeague`, no
  gate). In `W/destinations.ts`, add the rail entry "ADP & prices" for NBA leagues only, following
  `schedule`'s sport check. Update the `DestinationKey` union and the destinations tests.

## Phase 5: User Story 2 – Trust-Yahoo blend and Blend $ (P2)

**Goal**: sliders give a blended order and a price rescaled to the league. **Independent test**:
the T028 properties and quickstart V7's blend part.

- [X] T028 [US2] In `W/adpBlend.ts`:
  - `blendValue(p, trust, fullGap, minPct)`: `w = trust × min(|gap|/fullGap, 1)`. It's
    dynasty-only when Yahoo is unreliable or missing, and null when there's no dynasty ADP.
  - `blendOrder(players, …)` using the tie-break.
  - `rescale(costCurve, teams, rounds, budget)`: pad with $1 to N = teams × rounds, surplus
    scaling, empty → null.

  Tests in `W/adpBlend.test.ts` (F6, F9):
  1. Trust 0 → order equals dynasty order with ties broken.
  2. For Yahoo-favoured players, the value is non-increasing in trust (property loop over a grid).
  3. A Yahoo-favoured player never loses ground to a non-favoured one.
  4. The research R6 counterexample (P 12/11, Q 40/2, G 30) shows P's **rank** getting later. It's
     documented as expected, so nobody "fixes" it.
  5. Rescale sums to teams × budget ± 0.5 × N after whole-dollar rounding.
  6. A $0 cost floors to $1.
  7. An empty curve gives null (no divide-by-zero).
- [X] T029 [US2] In `W/pages/AdpCompare.tsx`, add:
  - the trust slider (default 75%) and full-trust-gap slider (default 30), labelled hand-set
  - a blended ADP sub-line when the weight is ≥ 0.05
  - a Blend $ column, shown only when the league's draft has teams, rounds **and** a budget, and
    Yahoo has a capture; otherwise "unavailable" with the reason
  - sorts by blended ADP and by Blend $
  - FR-013's exact Blend $ note, including "does not model how fewer teams and deeper rosters push
    money to the top; in the one auction measured, the top sold 25–50% above it"

  RTL test: the note is present; the column is hidden for a snake league without a budget.

## Phase 6: User Story 3 – Follow a live auction (P2)

**Goal**: budget cards, sales feed and sold prices, live. **Independent test**: the replay IT
(SC-002), quickstart V3, V4 and V8.

- [X] T030 [US3] In `B/engine/AdpCompareService.java`, when `draft.type == 'auction'`, add:
  - `teamsState`: one entry per `draft_slot` 1..`teams`. Manager, name and avatar come from
    `slot_to_manager` (null when unset).
    - `spent` = Σ amount, with null counted as 0 and adding to `priceMissing`
    - `filled` = number of picks
    - `spotsLeft` = rounds − filled
    - `left` = budget − spent
    - `maxBid` = spotsLeft > 0 ? max(0, left − (spotsLeft − 1)) : 0
  - `sales`: ordered by `pickNo` descending. `name` = the player's name, else `player_name`, else
    "Unknown player". Include `priceMissing`.
  - Per-player `sale` on the rows.

  Omit `teamsState`/`sales` entirely for snake drafts. Mirror the fields in `W/api.ts` in the same
  change.
- [X] T031 [US3] IT `BT/engine/AuctionBudgetReplayIT.java` (SC-002, F14):
  - Replay `auction-picks.json` one pick at a time.
  - After each pick, assert every slot's `left`/`spotsLeft`/`maxBid` against values computed in
    the test from the pick list: 2,300 checks.
  - The final Σ left = 18.
  - Also include a synthetic pick with an unknown player id but a Sleeper name (shown in `sales`),
    and one with no `amount` (counted, `priceMissing`).
  - Before any pick, every slot shows 200/23/178, including with `slot_to_manager` empty.
- [X] T032 [US3] In `W/pages/AdpCompare.tsx`, add the auction mode, shown when `draft.type ===
  'auction'`:
  - Budget cards per slot: avatar, name or "Slot N", $ left, spots, max bid. The user's slot is
    outlined.
  - A latest-sales feed: price vs Blend $ when available, "price missing" when flagged.
  - Sold rows hidden by default ("Hide sold" toggle), with "Sold $X · buyer".
  - While `draft.status` is `pre_draft`/`drafting`, subscribe via `W/useLiveDraft.ts` and refetch
    `getAdpCompare` when `picksMade` changes. No second poll.

  RTL tests: the cards render from `teamsState`; a refetch happens on a `picksMade` change; snake
  drafts show no cards.
- [X] T033 [US3] FR-015 routing:
  - `W/destinations.ts`: `draftRoute`/`draftLabel` and the `live` entry send auction drafts to
    `/leagues/:id/adp`; "Mock it" is hidden for auction leagues.
  - `W/pages/LeagueHome.tsx` and `W/pages/DraftPicker.tsx`: auction drafts link to the ADP page.
  - `W/pages/DraftView.tsx` and `W/pages/LiveDraftView.tsx`: redirect to the ADP page when
    `seats.draftType === 'auction'`, before starting any simulation.
  - The draft room links to the ADP page (FR-014).
  - Tests for each branch, and that snake drafts are unchanged.

## Phase 7: Polish & verification

- [X] T034 Run `./gradlew test` (record totals **and skips**), `npx tsc -b`, `npm test` and
  `npm run build`. Compare with T001's baseline.
- [X] T035 [P] Run the profile dump again after ingesting the auction with the new code. Diff it
  against `baseline/profiles-before.json`. Expect identical profiles if the auction was already
  inert, or differences confined to pollution that existed before; record which (SC-004).
- [X] T035b *(added after analyze, E1)* Build a stub-Sleeper replay harness for SC-003:
  - A tiny local HTTP stub (a script under `specs/046-adp-compare-auction-board/harness/`) serves
    `auction-draft.json` with `status: drafting`, and a `/picks` list that grows by one pick every
    2s from `auction-picks.json`.
  - Point the worktree backend's Sleeper base URL property at it (check the property name in
    `SleeperClient`).
  - Open the ADP page and record, for 10 picks, the time from the stub adding a pick to the sale
    showing (via a DOM timestamp from `javascript_tool`).
  - Report the median and max in verification.md.
- [X] T036 Live verification per quickstart V1–V10 against this worktree's own servers (not
  main's `launch.json`). Write `specs/046-adp-compare-auction-board/verification.md` with actual
  numbers, screenshots at desktop and 375px, and what wasn't covered. A real in-progress Sleeper
  auction is owed.
- [X] T037 [P] Update `HANDOFF.md`'s spec 046 entry to the real state. Add any durable lesson to
  `claude/lessons.md`, for example "auction pick order is nomination order: `draft_type` is a model
  rule, like `platform`".

## Dependencies

- Phase 1 → Phase 2 → {US4, US1}. US4 and US1 touch different files, except that both edit
  `api.ts` (T013/T024): serialize those edits.
- US2 depends on US1 (T025/T026). US3 depends on US1 (T023/T026) and Phase 2 (T004–T008).
- T033 depends on T005 (`DraftSummary.draftType`).

## Parallel examples

- Phase 2: T005 alongside T006.
- US4: T011 alongside T009/T010.
- US1: T016, T019, T022, T024 and T025 are separate files.

## Implementation strategy

- **MVP**: Phases 1–4 (US4 + US1). That gives a correct compare page, with auction picks unable to
  pollute models.
- **Then**: US2 (blend/price), then US3 (live auction).
- Each story is verified before the next one starts.
