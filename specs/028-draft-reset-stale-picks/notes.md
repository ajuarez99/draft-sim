# Spec 028: Sleeper draft reset leaves stale picks

## Cause (from code reading; never reproduced live)
- `DraftRepository.upsertPicks` only inserts/updates and returns early on an empty list.
- `LiveDraftPoller.pollOnce` returned early on `pre_draft` without fetching picks, so a reset draft (back to pre_draft, zero picks) never cleared anything. A new `/track` on a completed draft hits the same branch.
- `LeagueIngestService.ingestDraft` returned 0 on an empty Sleeper pick list without touching stored picks (same shape).

## The rule (deliberately narrow)
`LiveDraftPoller.shouldClearStalePicks(status, picks)`: clear ONLY when Sleeper status is `pre_draft` AND the picks endpoint returned a parsed, empty list. Null (empty/unparseable body), an exception, or status drafting/paused/complete never deletes. No partial "delete picks not in list" mid-draft (note: the pre-existing `replacePicks` prune on a NON-empty ingest list is unchanged).
Why: an NBA draft runs on this poller; a transient Sleeper hiccup must never be able to wipe picks.

## Changes
- `DraftRepository.countPicks`, `clearPicks` (single `delete ... where draft_id = ?`).
- Poller pre_draft branch: only asks Sleeper when we hold stored picks (no extra call for ordinary pre_draft drafts); fetch failure is logged and swallowed; INFO log with cleared count. The tick published right after carries picksMade=0, which is the same SSE fan-out a new pick uses. Sim start state / board are read from `draft_pick` per request, so there is no cache to invalidate.
- `LeagueIngestService.ingestDraft`: same rule.
- Frontend `LiveDraftView`: `realPicks` was merge-only (a falling count never shrank it). A `pre_draft` frame with `picksMade === 0` now empties it; the pick-card "seen through" marker rewinds when the list shrinks. `useLiveDraft` just stores the latest frame and `useRevealedBoard` is mock-draft only, so neither needed a change.

## Tested
- `LiveDraftPollerResetTest` (7): rule truth table; pre_draft+empty+stored clears and publishes picksMade=0; no stored picks -> no Sleeper call; fetch error, null body, drafting/paused/complete + empty -> no delete.
- `DraftRepositoryUpsertPicksIT` + `clearPicksDeletesOnlyThisDraftsPicksAndReturnsTheCount` against Postgres 5433.
- vitest `LiveDraftView.landed.test.tsx` new case (fails without the fix, verified). Full vitest 1705 pass, `tsc -b` clean.

## NOT verified
- No live Sleeper reset repro: that a reset draft really reports `pre_draft` + `[]` is an assumption from the API's documented shape.
- `LeagueIngestService` change has no dedicated test (same static rule, shared with the poller).
- Full backend suite not run (shared DB).
