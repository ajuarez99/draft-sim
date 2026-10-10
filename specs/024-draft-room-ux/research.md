# Research: Draft room UX (spec 024)

Phase 0 for [plan.md](plan.md). Each entry gives the decision, why, and what else was considered.

Every entry is also labelled by how it was established:
- **[measured]**: run or read during planning on 2026-10-09.
- **[read]**: read from code, but not run.
- **[decided]**: a design choice.

## R1. The draft room is three rooms, and they don't share a layout today [read]

**The three routes:**

| Route | Component | What it is |
|---|---|---|
| `/drafts/:id/live` | `LiveDraftView.tsx` (979 lines) | The live companion room. This is your screenshot. |
| `/drafts/:id` | `DraftView.tsx` | The projection/practice room for a real draft: the 450 ms reveal, `PickPrompt`/`PlayerPicker`, and `reveal.skip()`. |
| `/mock/:sessionId` | `MockDraftView.tsx` (211 lines) | A server-side mock. Bots advance on the server inside `POST /api/mocks/{id}/pick`, so this room has no reveal tick, no pick cards and no announcements. |

**Shared parts:**
- All three render `DraftBoard` and the floating `AvailabilityPanel` sheet, each inside its own `.board-stage`.
- Each room arranges its other parts (feed, team strip, meters, toolbar) differently.

**Decision [decided]:** extract one `DraftRoomLayout` shell with named regions:
- status/summary
- control group
- board
- divider
- target strip
- player list
- an optional "below" slot for feeds and meters

All three pages render into it, which is how FR-003 / "this should also look the same for actual draft" gets met. A room with no data for a region shows that region's explained-absence copy. It does not collapse the region.

**Alternatives considered:**
- *Restyling the three pages separately with shared CSS.* Rejected. That is three implementations of one layout, the bug class AGENTS.md names ("two implementations of one rule", see the multi-sport landmines memory).

## R2. Stacked, resizable split (US1, FR-001/001a) [decided] — *partly superseded by A4 (measured heights, compact cells)*

**The split:**
- Below 1280 px: one segmented Board | Players control, the same pattern as phone today, but reused across all three rooms.
- At 1280 px and above: a CSS grid of `board / divider / list`. The divider is a `role="separator"` element with `aria-orientation="horizontal"`, `aria-valuenow`, and keyboard support (↑/↓ move it 1 row, Home/End go to the minimums, Enter resets).
- The position is stored as a fraction of the room's height, in `localStorage` under `draftRoom.split`. It is per device, as the clarification said, and the same fraction is used in every room and draft.
- The 3-round / 3-row minimums are computed from the measured row heights, not hard-coded pixels.
- Double-click resets the divider.

**Default split:** it must meet SC-001 at 1440×900, meaning ≥ 7 rounds and ≥ 8 player rows. The rough arithmetic (header ~70 + 7×32 board rows + divider 8 + list head ~70 + 8×48 rows ≈ 756 px of 900) says this fits. **That is a guess. It gets measured in the browser during verification, not asserted here.**

**What it replaces:** the `--avail-sheet-reserve` ResizeObserver dance in `AvailabilityPanel.tsx:233-265`. That only existed because the sheet floated over the board, so it gets deleted, not kept alongside.

**Alternatives considered:**
- *A third-party split-pane library.* Rejected: about 80 lines of our own code covers it, and no dependency is warranted.

## R3. Board legibility (US2) [read + decided] — *corrected by A5, A6 and A7*

- **Cell label:** `DraftBoard.tsx` already imports `pickNoAt` from `snake.ts`, and `roundPickLabel.ts` exists. Cells move from bare `pickNo` to `roundPickLabel(pickNo, teams)` ("2.03").
- **Snake cue:** a direction arrow comes from `snake.ts`'s forward/backward for that round, honouring `reversalRound`. Optional parameters that encode rules are a known footgun, so `reversalRound` stays required wherever the arrow is computed (see the feedback memory on optional params).
- **Seat headers:**
  - With a manager: avatar plus name, as today.
  - Without one: a "Claim" pill that calls the existing `onSeatClick` → `SeatPopover` → "make mine" path. No new seat mechanism.
  - The header reads "assumed" whenever `slotKnown` is false.
- **Own column:** the per-cell crimson outline becomes a column tint plus a header badge. An assumed seat gets a dashed, low-alpha tint.
- **On the clock:** `onTheClockSlot` + `picksMade + 1` is already on `LiveState`. The mock has `currentPickNo`. DraftView uses the reveal's `pausedAt`.
- **Landed vs projected:** `revealedThrough` already splits them. Today projected cells are lighter; this feature makes that split explicit and the same in every room.

## R4. Target list storage [decided] — *concurrency fixed by A8*

- A new table, **`draft_target`, in migration `V30__draft_target.sql`**. V29 is the highest migration on `main` and on every unmerged branch (checked: 017-allstar-filter, 021, 022, 023 all top out at V29).
- **Scoped by draft:** either `sleeper_draft_id text` or `mock_session_id bigint → mock_draft_session(id) on delete cascade`, with exactly one of the two set.
- **Why not an FK to `draft.id`:** `draft` rows hang off `league` with `on delete cascade`, and a re-ingest must never wipe a person's stated choices. That is the same reasoning V9 gives for `ranking_ballot`. The text Sleeper id survives a rebuild.
- **Identity:** `owner_sleeper_user_id text`, the same token V8 uses for mocks, read from `X-Sleeper-User`.
- **Write shape:** replace-the-whole-list. `PUT` takes the ordered list and rewrites that owner+draft's rows in one transaction. Reordering a list of 5–20 items via a full replace is simpler and has no partial states. Last write wins across devices, which the spec allows ("instant syncing ... is not required").
- **Projection room and live room:** both rooms for one Sleeper draft see the same list, because the key is the Sleeper draft id. That is intended, since they are the same draft.
- **Forking:** `MockDraftService.createSessionFromDraft` copies the caller's targets for the source draft into the new mock in the same transaction.

**Alternatives considered:**
- *Per-device `localStorage`.* Rejected by clarification Q2.
- *Incremental add/remove/move endpoints.* Rejected: more endpoints and more ordering bugs, for no user-visible gain.

## R5. Mock auto-pick and auto-finish (US5) [read + decided] — *the need rule is WRONG as first written; see A1*

**What exists:**
- `MockDraftService.submitPick` validates the user's turn, inserts a `USER` pick, then `advanceAndPersist` runs bots up to the user's next turn.
- `mock_draft_pick.source` is checked against `('USER','BOT','LIVE')`. That constraint was widened in V4.

**Decision:**
- **Endpoint:** `POST /api/mocks/{id}/auto` with `{ "scope": "PICK" | "FINISH" }`.
- **One transaction, under the existing `lockForUpdate`.** The server loops: choose a player for the user's turn, insert it with `source = 'AUTO'`, advance the bots, and repeat until the user is next (`PICK`) or the draft is complete (`FINISH`).
- **It is atomic.** A closed tab can't leave a half-finished auto-finish, which matches "no gap in which to cancel".
- **V30 widens the source check** to include `'AUTO'`. The constraint is dropped and re-added by the name V4 gave it. **[measured]** That name is `mock_draft_pick_source_check`, confirmed both in V4's source and in `pg_constraint` on the local database. It currently allows `('USER','BOT','LIVE')`.
- **Choosing the player:**
  1. Take the owner's targets for this mock, in order, and use the first one not yet drafted.
  2. If none is left, take the top available player in **ADP order** who fits an open starter slot. "Fits" is defined as `SportRules.rosterNeed(candidate, prepareLineup(roster, …)) > 0`, which is the same need rule the bots score with.
  3. If no player fits a starter slot, take the top available by ADP.
- **Why ADP order:** that is what both the mock picker (`PlayerPicker` sorts by `adp`) and the sheet's tiers use, so it is the room's own best-available order.
- **No second rule:** this reuses the backend rule instead of porting `teamNeeds.ts`. `teamNeeds.ts` itself says it mirrors `FootballRules`/`BasketballRules`.
- **Frontend:** `PickView.source` gains `'AUTO'` in `api.ts` in the same change. The pick feed marks AUTO picks with an "auto" tag.

**Alternatives considered:**
- *A client-side loop calling `/pick` about 13 times.* Rejected. It needs 13 round trips, it isn't atomic, and it would need a way to mark the picks as auto anyway.
- *Letting the engine's manager model pick for the user.* Rejected. The spec says targets first, then the room's best-available order. It does not say "what a bot would do".

## R6. "SG 0/0 / SF 0/0" root cause [measured]

**What the data carries:**
- `PlayerRef.position` is a single value, the first one Sleeper lists (`player.positions[1]`).
- `scarcity.ts:80` counts `p.position === position`.

**The measurement.** Run against the local database (port 5433), it reproduces the screenshot exactly:

```
top 36 NBA by latest ADP, primary position:  PG 17 · C 11 · PF 8   (SG 0, SF 0)
```

**Decision for this feature (FR-018):**
- A position with `poolSize === 0` is left out of the chip row.
- When any are left out, one muted line names them, e.g. "SG, SF: no starter-pool players list these first".
- Nothing about counting changes.

**Not done here, and flagged:** counting a player toward every position he's eligible for. That needs `positions[]` on `PlayerRef` (a backend record and `api.ts` mirror change). It would also change what the scarcity meter, position filters and pick-run detector mean for NBA, which is a bigger and separate decision. The same single-position limit means the player list's SG filter shows only players whose *first* listed position is SG. The spec's edge-case bullet claimed otherwise; it was wrong and has been amended in place.

## R7. Format summary and pick timer (FR-017) [measured] — *corrected by A2 and A3*

**What Sleeper sends.** `curl https://api.sleeper.app/v1/draft/1414361905279049728` (the screenshot's draft) returned:
- `settings.pick_timer: 120`, `settings.teams: 4`, `settings.rounds: 14`
- `type: "snake"`, `draft_order: null`
- `metadata.type: "league_mock"`

The last two mean the screenshot's draft is a **Sleeper mock attached to a league**. That is why "0 of 4 managers identified", and it's a real case the unclaimed-header state must handle.

**Where the data is today:**
- Neither `pick_timer` nor `draft_type` reaches the frontend.
- `draft.draft_type` is already stored (V1). It gets added to `SeatsResponse` as `draftType`.
- `pick_timer` is read where `LiveDraftPoller.pollOnce` already fetches `sleeper.draft(...)`, and published on `LiveState` as `pickTimerSeconds: number | null`. No column is added: it only matters to a live room, and it is already on every poll.

**What each room shows:**
- The projection room and mock room show the summary without a timer.
- The live room shows the timer once a state frame has arrived.
- `api.ts` mirrors both fields in the same change (constitution rule 2).

## R8. Pre-draft duplication and control group (FR-016, FR-019) [read]

**Where the duplication comes from:**
- The top "DRAFT HAS NOT STARTED" is `LiveStatusBar`.
- The bottom "WAITING FOR THE DRAFT TO START" is `.live-waiting` (`LiveDraftView.tsx:936-941`).

**Decision:** the status bar becomes the single owner of draft status, and `.live-waiting` is deleted. Its detail copy ("Waiting for the commissioner to set the draft order") moves into the status bar.

**The four chips** become one `RoomControls` group in the layout's control region:
- "Continue as a mock" is the one primary button.
- The others are quiet toggles.

## R9. Concurrency and the open Sleeper-reset bug [read]

- **Taken status is computed, never stored.** Each target's "taken" flag comes from each render's picks (FR-013, SC-005). So the known bug where Sleeper's reset leaves stale picks (`upsertPicks` never deletes) would show a target as taken while those stale picks persist. That bug is pre-existing and unchanged here; this feature does not fix it.
- **Two devices editing at once:** last write wins, by design (R4).

---

## Amendments after plan review (2026-10-09)

Source: [plan-review.md](plan-review.md). Its 4 blockers, 11 should-fixes and 9 notes were read against the code; blocker 1 was re-checked by hand. Each amendment below supersedes the R-entry it names. The originals are kept above so the error stays visible.

### A1 (review #1, #15): auto-pick's need rule [verified]
- **What R5 got wrong.** It said "fits = `rosterNeed > 0`". **That was wrong.** `rosterNeed` returns values in `[benchFloor, 1]`, and `benchFloor` is 0.15 for both sports in `config/weights.yml`, so `> 0` is true for every player.
- **Corrected rule:**
  1. Filter every candidate by `rules.isDraftable(e, lineup, round, rounds)`, the bots' hard gate in `PickDecider.choose`.
  2. Take the first draftable target in rank order.
  3. Otherwise take the top draftable player by ADP with `rosterNeed(e, lineup) > benchFloor` ("would start"). `benchFloor` is read from `ScoringProperties` for the session's sport and never hard-coded.
  4. Otherwise take the top draftable player by ADP.
- **Meaning.** "Would start" includes displacing a weaker starter. That is the engine's own notion of need, used deliberately rather than adding a second rule.
- **Required test.** The preference test must include a case that **fails under `> 0`**: an NBA tenth pick with all nine starters filled, where the top-ADP player is bench-only.

### A2 (review #2): the pick timer is stored, not streamed [verified]
- **The problem.** The first live frame is built from the DB, and later frames are only sent when `status|picksMade|seatsMapped` changes. A pre-draft room would never receive the timer.
- **The fix.** V30 adds `draft.pick_timer_seconds int` (nullable).
  - `LeagueIngestService.ingestDraft` writes it from `settings.pick_timer`. `DraftRepository.upsert` adds it, and `draft_type = excluded.draft_type`, to its conflict list. Today `draft_type` is first-ingest-only.
  - `LiveDraftPoller.pollOnce` refreshes it next to `updateStatus`, since the raw draft is already in hand.
  - `seats()` returns both through a new `DraftRepository.format(draftId)` query (see A9).
- **Not changed.** `LiveSnapshot` and the SSE frame are untouched.
- **Who shows it.** The projection room shows the timer too; only the mock has none.

### A3 (review #3): the Sleeper URL in the request is a Sleeper mock this app cannot ingest [verified]
- **What R7 got wrong.** It said "the screenshot's draft is a Sleeper mock". **That conflated two drafts.**
- **The URL's draft.** `1414361905279049728` (the URL the user pasted) is a `league_mock` with `league_id: null`. The league drafts endpoint doesn't list it, and that endpoint is the only way ingest discovers drafts.
- **The draft the screenshot shows.** The league `1414306784801239040` has one real draft, `1414306786223153152`: 4 teams, 14 rounds, `pick_timer` 120, `pre_draft`, `draft_order: null`. "0 of 4 managers identified" comes from that null `draft_order`, which is still a real case for the unclaimed headers.
- **Out of scope.** Sleeper `league_mock` drafts are out of reach of ingest, and this spec does not add them.

### A4 (review #4): measured heights, and compact cells [measured 2026-10-09 in the 024 worktree at a 1440×900 viewport, on main's code]
- **Board, 12-team NBA 2025 draft, complete:** filled `.cell` = **70 px**, row gap **4 px**, `.col-head` = **72 px**.
- **Pre-draft, 12-team NBA 2026:** empty cell = **28 px**. The tiers list row is **27 px** and its thead **28 px**.
- **Live-room chrome above the board:** **438 px** in total (status bar, feed 98, "Your team" 59, scarcity + Room read 93, and the panel head).
- **The conclusion.** 7 filled rounds need about 590 px of board alone. R2's arithmetic (7 × 32) was a guess, and it was wrong for any filled board.
- **The user's decisions:**
  - compact one-line cells in the split, about 36 px per round, with full size when the board has the height (FR-001b);
  - a compact row of about 80 px above the board for the feed ticker, team and scarcity (FR-001c).
- **New budget at 900 px**, an estimate to be **measured** in T017:
  - status + summary + controls ≈ 70;
  - compact row ≈ 80;
  - col-head 72 + 7 × 40 = 352;
  - divider 8;
  - target strip 44;
  - list head + search ≈ 126;
  - 8 × 27 rows = 216.
  - **Total ≈ 896.** That is tight. If T017 measures it short, the list head is compressed first (chips and toggles on one row). The bar is not quietly lowered.

### A5 (review #5): Claim needs its own path [verified]
- **The problem.** An unmapped slot has **no** `Seat` object, so `DraftBoard` disables that header and `SeatPopover` renders nothing.
- **The fix.** Add an `onClaim(slot)` that does what `onMakeMine` does (sets `?slot=N`) without a seat. It shows only on a real draft with no manager for that slot, never on a mock's BOT seats.
- **Corrected claim.** Plan row 6 said "no new seat mechanism". It is a small new path, and that is now stated.

### A6 (review #6): the column mark comes from `mySlot` only [verified]
- **Where the 14 outlines came from.** They come from `myPicks`, which in the live room is the simulation's *assumed* seat 1 whatever `slotKnown` says.
- **The fix.** The column tint and badge come only from `mySlot` / `mySlotAssumed`. The per-cell `mine` class is removed.

### A7 (review #7): "landed vs projected" did not exist [verified]
- **What R3 got wrong.** It said "Today projected cells are lighter". **That was wrong.**
  - The live board blanks every cell past `picksMade`.
  - No CSS marks a cell "projected"; `.uncertain` means non-modal.
- **Decision (Claude's, conservative):** FR-008 is redefined per room:
  - live: landed vs empty;
  - projection room: projected vs your own pick;
  - mock: all real.
- **Out of scope.** Drawing the projection onto the live board.

### A8 (review #10): target saves under concurrency [inferred; T004 runs the two-PUT race]
- **Client:**
  - one PUT in flight at a time, coalescing to the latest list;
  - a PUT response is never applied over newer local state;
  - no refetch-on-focus while dirty, in flight or errored.
- **Server:** `replace` starts with `pg_advisory_xact_lock(hashtext(owner || ':' || scope))`.

### A9 (review #12): no `DraftRow` widening [verified]
`DraftRow` is constructed in 33 places and already has defaulted constructors. Instead, one query `DraftRepository.format(long draftId) → DraftFormat(String draftType, Integer pickTimerSeconds)`, shaped like the existing `reversalRound(id)`.

### A10 (review #9, #11): mock details [verified]
- **`userPicks`.** `MockDraftView`'s `userPicks` must count `USER | AUTO`; `:118` is the only site. `FeedPick` gains `auto?: boolean`.
- **What the list shows.** On COMPLETE, it shows an explained absence ("Draft complete").
- **FINISH loop cost.** The loop fetches `board` and `fit` **once per request** and calls `contexts.build` per iteration with the growing `completed` map. Candidates and targets are limited to `ctx.byId`.
- **FINISH order.** The mock's idle state is always "your turn", so FINISH's first iteration is always an AUTO pick.

### A11 (review #13): a new rule disagreement, recorded rather than fixed
- **The disagreement.** Auto-pick (backend) reads every position a player lists. The list's "fills a need" tag (`teamNeeds.ts`) reads only the first. In the same room they can disagree about the same NBA player. Locally, 1,679 of 2,091 NBA players list more than one position.
- **How it's handled.** The existing follow-up task (`positions[]` on `PlayerRef`) is the fix. It is owed in HANDOFF, not done here.

### A12 (review #14): where the pick card is anchored
- **At ≥1280:** the card anchors to a non-scrolling `position: relative` wrapper around the board region.
- **Below 1280:** it is `position: fixed`, so it is visible even with the Players tab selected.
- **Check.** Live-check it at 1024 and at 1440.

### A13 (notes #16–#24)
- **`DraftBoard.reversalRound`** becomes **required**. All four callers already pass it.
- **`api.ts` fields** are optional *and* nullable (`?: … | null`), because of the split deploy. `getTargets`/`autoMock` degrade on a 404 from an older backend.
- **Names:**
  - The TS type is `MockPick`; the Java record is `MockSessionState.PickView`.
  - No TS `LiveState` change is needed after A2.
- **Tests:**
  - ordering cases go in `MockDraftServiceTest` (Mockito with the real `SportRules`);
  - a slim real-Postgres IT covers the V30 insert;
  - ACL cases go in `AccessControlMvcIT`.
- **Targets API:**
  - **No identity:** GET with no identity returns an empty list. PUT with no identity returns 401, **even with the admin token**, because `owner_sleeper_user_id` is NOT NULL.
  - **Non-members:** a non-member gets 404 via `visibleDraft` on both.
  - **Targets no longer on the board** come back in a separate `missing: [{sleeperId, name}]`. No ADP is invented.
- **Mock limits:** mocks allow `teams in (8,10,12,14)`, and forking needs `status = drafting`. The FR-003 parity check therefore uses a 12-team league and an ordinary mock, not a fork of the 4-team league.
- **Accent folding** doesn't exist yet. One `fold()` (NFD, strip `\p{M}`, lowercase) is added to `targets.ts`. 91 local NBA names carry non-ASCII characters.
