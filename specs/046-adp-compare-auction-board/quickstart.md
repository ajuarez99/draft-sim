# Quickstart / verification guide: spec 046

These are the live checks the verification pass runs. Each one records the actual number in
`verification.md`.

## Prerequisites

- Local Postgres on 5433, the worktree's backend on its own port and its vite on its own port.
  The main checkout's `launch.json` serves main (memory: worktree preview serves main), so start
  this worktree's servers explicitly.
- Ingest "Legacy Ball Knowers" (league `1415422393706455040`): `POST /api/ingest/all/1415422393706455040`.
  Its draft is the completed 230-pick auction.
  - **This is also a required POST-DEPLOY step on prod** *(added after code review, C2)*. The row
    ingested before V39 has `budget NULL` and every `amount NULL`; only this re-ingest fills them
    (the poller never runs for a complete draft and the daily refresh doesn't re-ingest). Until it
    runs, the page says prices haven't been ingested yet. The same call refits NBA profiles: check
    `profilesWritten` and that managers with snake history are unchanged (V5).
  - **Never run it while a draft is live.** HANDOFF.md warns that `/ingest/all` and `/ingest/league`
    call `replacePicks` and the board rebuild stalls the poller. Check no NBA draft is in progress first.
- Run the season projection refresh (forced), then `POST /api/ingest/adp/yahoo?sport=nba`.

## V1 – Sources captured

- `select source, count(*) from adp_snapshot where sport='nba' and captured_on=current_date group by 1`
- **Expect**:
  - `sleeper_dynasty` ≈ 835 (±50; it moves daily)
  - `sleeper_redraft` > 0
  - `yahoo` ≈ 186

## V2 – Yahoo matching

- The ingest result's `unmatched` list.
- **Expect**: 0 unmatched, or every one listed by name. Record the count.

## V3 – Auction ingest

- `select sum(amount), count(*) from draft_pick dp join draft d on d.id=dp.draft_id where d.sleeper_draft_id='1415422395308609536'`
- **Expect**: 1,982 / 230. Also expect `draft.budget = 200`.

## V4 – Budgets (SC-002)

- Integration test `AuctionBudgetReplayIT`: replay the recorded 230 picks one at a time and check
  every team's left, spotsLeft and maxBid after each pick against values computed by hand from the
  pick list.
- **Expect**: 2,300 checks, all equal. Final total left across teams = $18.

## V5 – Profiles untouched (SC-004)

- Fit NBA profiles with and without the auction draft present.
- **Expect**: every profile identical, field for field. Also expect `allCompletedPicks` to return
  no pick from the auction.

## V6 – Refusals *(amended after review, F1/F3/F8)*

- `POST /api/sims` for that draft, `POST /api/mocks/from-draft/1415422395308609536` and
  `POST /api/drafts/1415422395308609536/picks`. **Expect**: each returns 409 `auction_draft`.
- `GET /api/drafts/1415422395308609536/board`. **Expect**: still 200, unchanged. The web app just
  doesn't link to it for auctions.
- Grades for that draft. **Expect**: `available: false, reason: "AUCTION_DRAFT"`, rendered with its
  own sentence.
- Stats leaderboard for that league. **Expect**: no pick or round number on auction-drafted
  players.

## V6b – Routing *(added after review, F2/FR-015)*

- For Legacy Ball Knowers, check that each of these leads to `/leagues/1415422393706455040/adp`:
  - the rail's Draft board and Follow live
  - LeagueHome's draft links
  - the draft picker row
  - typing `/drafts/1415422395308609536` and `/drafts/1415422395308609536/live` (redirect)
- Check that "Mock it" isn't offered.
- For Ball Knowers (snake), check that all of these still go to the snake pages.

## V10 – Criteria the first draft missed *(added after review, F14)*

- **SC-001**: spot-check 20 players. Compare `adp_dynasty`/`adp_std` from the raw Sleeper payload,
  and Yahoo's `average_pick`/`average_cost`/`percent_drafted` from its JSON, against `adp_snapshot`
  for the same day. All 20 must be equal.
- **US3 AS4**: the replay IT includes a pick with an unknown player id and a Sleeper name, and the
  sales feed shows that name.
- **Price missing**: the replay IT includes a pick with no `amount`. It's counted, shows "price
  missing", and adds $0.
- **US4 AS2**: pick a Legacy Ball Knowers manager who isn't in any snake league. Their profile
  shows no fitted history (`drafts_observed = 0`).
- **US2 AS4**: find a Yahoo-unranked player (or one under 50% drafted). His blend equals his
  dynasty ADP, and the row says why.
- **US1 AS3**: name the unit test that raises the threshold and checks the tint is removed.
- **Source failure**: with a stub Yahoo client that throws, the last good capture still shows with
  its date. With no capture, the column says "unavailable" and Blend $ says "unavailable".
- **FR-014**: the draft room links to the ADP page.
- **Tint counts (F7)**: record the green/red counts at gap ≥ 20 under the 50% rule. The review
  measured 40 green under that rule alone.

## V7 – Compare page (browser, desktop and 375px)

- Open `/leagues/1415422393706455040/adp`. Check that:
  - The three source rows show their capture dates and counts.
  - Wembanyama shows dynasty / redraft / Yahoo ADP, Yahoo $, and "Sold $112".
  - At gap ≥ 20, Durant is green and Dybantsa is red, each with its gap as text.
  - LeBron (100% drafted) is green. A player under 50% drafted is greyed and untinted, with the
    reason shown. *(amended, F7)*
  - Trust 0% → blend order equals dynasty order.
  - Blend $ over the top 230 sums to $2,000 ± $115 (an identity check, F9).
- The labels are present: redraft, derived, hand-set, rescaled.

## V8 – Live (SC-003)

- No real auction is live, so replay the recorded picks through the poller's test seam (a stub
  Sleeper serving N picks, increasing every 2s) against the running server, with the page open.
  Time from stub change to the sale appearing.
- **Expect**: ≤ one drafting interval (2s) plus the refetch. Record the measured time.
- **Not covered**: a real Sleeper auction in progress. Note it as owed, not passed.

## V9 – Snake league unaffected

- Open the page for "Ball Knowers" (NBA snake). Check that there are no budget cards and no sold-$
  column, and that the compare table renders.
- Open that league's live room and check that simulation still works.

## Suites

- `cd backend && ./gradlew test`: record the totals and **the skip count** (memory: the suite
  skips ITs silently when Postgres is down).
- `cd web && npx tsc -b && npm test && npm run build`
