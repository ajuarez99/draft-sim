# 03 — The manual pick writes into finished drafts, and accepts the same player twice

**Severity: Medium. Verified locally,** and the state was restored.

## What's there now

`POST /api/drafts/{id}/picks` is `LeagueController.recordPick`
(`backend/.../api/LeagueController.java:537`). It has no UI caller (`grep -rn "/picks" web/src`
finds none); it's the curl escape hatch for a pick the poller lags.

- **No status check.** The Javadoc (`:522-535`) describes a live `drafting` draft that
  "self-heals" because the poller re-upserts every tick. But nothing between `:541` and
  `:591` reads `draft.status()`. A `complete` draft accepted a write.
  - No poller loop runs for a complete draft (`LiveDraftPoller.java:198-200`), so there's
    no self-heal every tick.
  - `DraftRepository.allCompletedPicks` (`store/DraftRepository.java:393`) selects
    `d.status = 'complete'`, so the bad row goes straight into manager-profile fitting. The
    Javadoc's safety argument holds only for drafts that *aren't* complete.
  - Correction to the finding: the manual pick *is* overwritten by the next full league
    ingest. `LeagueIngestService.java:205` calls `replacePicks` for every draft in the
    chain, which re-upserts every `pick_no`. That happens only if someone runs an ingest,
    and fitting can read the bad row before then.
- **No duplicate-player check.** `upsertPicks` (`DraftRepository.java:112`) conflicts on
  `(draft_id, pick_no)` only, and `V1__init.sql:82` has no `(draft_id, player_id)`
  constraint. Posting player X at pick 2 while X already sat at pick 1 left X on both.
- **Seat check:** `OwnerSlot.mayActAsSlot` (`engine/OwnerSlot.java:87`) returns `true` for
  a blank header (`:89`). That's plan 01's fix; this plan doesn't change it.

## Fix

1. **Status guard.** Right after `visibleDraft` (`:541`), return **409** unless
   `"drafting".equals(draft.status())`, with a message that names the actual status. This
   matches the wording of `MockDraftService.java:238`.
   - **Question for Allan:** should `pre_draft` also be allowed? The poller does run during
     `pre_draft`, but a pick recorded before the first real one has nothing to reconcile
     against. The recommendation is to refuse it. Also: does Sleeper have other live
     statuses (for example a paused draft)? That hasn't been checked, so check it before
     hard-coding a single allowed value.
2. **Duplicate-player guard.** Before the upsert (`:591`), look up whether `playerId` is
   already on a different `pick_no` in this draft. Add a small `DraftRepository` query
   rather than scanning `drafts.picks()`.
   - Different pick → **409**: "player {sleeperPlayerId} is already pick {n}".
   - Same player at the same pick → still **200**. That keeps
     `LeagueControllerManualPickTest.rePostingTheSamePickIsIdempotent` (`:132`) true.
3. **Keep the unmapped-seat exception.** `mayActAsSlot` stays open for a seat with no
   manager (`OwnerSlot.java:92`). The new guards go before the seat check and don't
   depend on it.
4. **Rewrite the Javadoc** (`:522-535`): drafting-only, 409 on any other status, 409 on a
   duplicate player, and the heal comes from the poller while live (or a re-ingest after).
5. **`PUT /drafts/{id}/reversal-round`** (`:198`). This also has no status check (`:202-214`).
   It's a lower risk:
   - It writes `draft.reversal_round_override`, not `draft_pick`.
   - `allCompletedPicks` reads the stored `draft_slot`, so fitting isn't affected.
   - Its only UI caller is DraftView's settings popover (`DraftView.tsx:409`).
     `CompletedDraftBoard.tsx:94` only *reads* the value to lay out the real board.
   - So on a `complete` draft, an override can only mis-draw the finished board for
     everyone. The recommendation is **409 on `complete`**, keeping `pre_draft` and
     `drafting` open.
   - Not verified: whether `DraftBoard` places real picks by stored `draftSlot` or
     recomputes their position from `reversalRound`. Check this before deciding how
     much the override matters.

## Not in scope

- The header-less seat bypass (plan 01).
- A DB unique constraint on `(draft_id, player_id)`. A single poller batch can briefly
  hold a stale manual row and Sleeper's true row for the same player, and a constraint
  would fail the whole tick. Keep the guard in the endpoint only.
- Cleaning up existing rows. The earlier session restored its test write, but run a
  one-off `select draft_id, player_id, count(*) ... having count(*) > 1` against prod
  and report the result.

## Acceptance criteria

Run each check against a real `bootRun`, not only tests:

- [ ] `curl -X POST .../drafts/{completeDraft}/picks` → 409, and the `draft_pick` row is
      byte-identical before and after.
- [ ] On a `drafting` draft: a valid pick → 200. The same body again → 200. The same
      player at a different pick → 409 naming the first pick, with no second row written.
- [ ] A seat Sleeper hasn't mapped yet is still recordable.
- [ ] `PUT .../reversal-round` on a complete draft → 409 (if adopted). On a `pre_draft`
      draft → 200, and the DraftView popover still works in the browser.
- [ ] A new `store/ManualPickGuardsIT.java` next to `DraftRepositoryUpsertPicksIT`, against
      real Postgres, covering: the status gate for `complete` and `pre_draft`, a duplicate
      player at another pick, the same-pick re-post, and that `allCompletedPicks` is
      unchanged after a refused write. Extend `LeagueControllerManualPickTest` for the 409s.
- [ ] Backend suite: **0 skipped**.
