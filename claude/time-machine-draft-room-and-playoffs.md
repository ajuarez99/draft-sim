# Time Machine: draft room, pick pacing and playoffs (spec 063)

Design lives in `specs/063-time-machine-draft-room/` (spec, plan with its "Amended after review" table, data model,
contracts, tasks, `plan-review.md`). This note is the pointer and the shape in one screen.

## What it is

1. **Pick pacing**, browser only. The server still makes every computer pick in one request; `web/src/usePickReveal.ts`
   holds "revealed through pick N" and exposes a `view` cut at N. Everything in the room reads `view`, never the raw
   response, which is what keeps an unrevealed pick's player in the list and off the board (FR-002). Speeds are in
   `web/src/revealSpeed.ts` and are hand-set for feel.
2. **Playoffs**, in the pure `SeasonReplay`: a regular season (19 weeks NBA, 14 NFL, both league-format choices, not
   measurements), then three single-elimination weeks for the top six with byes for seeds 1 and 2. The stored result
   gains `format`, `playoffs`, `finalPlaces`, `userOutcome` and `weeks[].phase`. A result without `format` is the old
   format and is never re-scored; `isOldFormat` in `web/src/historicalCopy.ts` is the only place that asks.
3. **The room**: its own layout (`HistoricalRoomLayout`) with a sticky clock banner, a pick rail, a main column
   (Players / Queue / Board) and a roster panel. The queue is browser-only (`useDraftQueue`).

## Where things are (web)

| Piece | File |
|-------|------|
| Reveal hook, speeds | `usePickReveal.ts`, `revealSpeed.ts` |
| Snake slot for a pick (parity-tested against the backend) | `draftSlot.ts`, `__fixtures__/draft-slot-parity.json` |
| Queue | `useDraftQueue.ts`, `components/HistoricalQueue.tsx`, optional props on `HistoricalPlayerList` |
| Room | `pages/HistoricalRoom.tsx`, `components/HistoricalRoomLayout.tsx`, `HistoricalClockBanner.tsx`, `HistoricalPickRail.tsx`, `HistoricalRosterPanel.tsx` |
| Results | `components/HistoricalResults.tsx`, `HistoricalBracket.tsx`, copy in `historicalCopy.ts` |

## Choices worth knowing

- Deploy order is **web first, then backend**: every new field is optional in `api.ts`, and a new web against an old
  backend renders everything as the old format (`HistoricalOldBackend.test.tsx`).
- `DraftRoomLayout`, `useRevealedBoard`, `TeamStrip` and `teamNeeds` are untouched; the mock, live and simulator rooms
  depend on them. The roster panel reuses `computeTeamNeeds` for the seating rule.
- The pick rail shows no "needs" for computer teams: nothing here has checked that the computer teams draft for need.
- Auto-finish stays available while picks are landing (FR-006); it ignores the queue and says so.
- Results lead with the bust and the passed-on steal (Allan's note, "Queued for later" in the spec); a fuller
  story-first results page is for a later spec.

## Not verified by tests

Layout at 1366x768 and 375 px, sticky banner offset under the top bar, sideways scroll, reduced-motion behaviour,
real pick timing, and screen-reader output need a browser run (`verification.md`).
