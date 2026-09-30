# Contract: live room UI

This contract fixes what a reviewer or verifier can check on screen. Layout
details beyond this are left to the build.

## Pick insight card (`PickInsightCard`)

**Where.** Overlays the top of `.board-stage` in `LiveDraftView`. It never
overlaps `LiveStatusBar`, `PickFeed`, the "Your team" strip, or the scarcity
row, all of which render above the stage. At ≤ 480px it spans the stage width.
There is no horizontal scroll at 375px.

**Content, top to bottom.** A section whose data is missing is left out, with at
most one muted line saying why.

1. Avatar, manager, `R.PP` pick label, player name, position chip in that position's colour, and team.
2. **Fit**: "Fills RB2" or "Depth". Then "Still needs: TE · FLEX · K", or "Roster complete".
3. **Summary**: one or two sentences, every clause backed by row 2 (DM-5).
4. **Tags** (inline chips): the ADP delta ("14 before ADP" / "9 past ADP" / "On ADP" for |Δ| ≤ 1), the model's share ("Model had this at 31%" / "under 4%" / "Surprise"), and "as of pick N" when the projection was older than the previous pick.
5. **On brand** (this manager): reach so far vs profile, and lean vs room, with verdicts or the reason there are none.
6. **Likely next @ R.PP**: top candidate and share, plus up to 2 more. "Wide open" when the top share is under 25%. States: updating / busy ("projection server busy, will retry next pick") / none. Includes the provenance label.
7. **Scarcity line**, when it applies. It links to the scarcity row.

**Behaviour.** As in data-model.md's state transitions. `role="dialog"`,
`aria-modal="false"`, `aria-live="polite"` on open. Esc closes it. Focus is
**not** moved into it on auto-open, because that would steal focus mid-draft;
it is moved in when opened from a feed-row click.

## Scarcity row (`ScarcityMeter`)

Sits under "Your team" (or where it would be when the seat is unknown). One chip
per runnable position: `RB 9 / 34` and, when available, `· ~5 at 3.04`. A
position with a run gets a running marker, the same wording as the feed's run
callout. It has one muted definition line with the starter-pool sentence. When
`expectedAtNext` is gated off (R7), there is one muted line: "projected count
from pick ~N".

## Room read (`OnBrandPanel`)

A collapsed-by-default disclosure beside the scarcity row. It holds one row per
seat: avatar, name, mix counts, reach so far vs profile, and both verdicts or
their reasons. The same content also appears in `SeatPopover` for that seat.

## Preference

A chip in `.live-panel-head` next to "Announce picks": `Pick cards on` / `Pick
cards off`. Stored at `bk.pickCards.v1`, default on, read and written in
try/catch. It controls cards only; the meters always show.

## Feed

Every row is clickable and opens that pick's card. The newest row's existing fit
clause is computed by the **same** function the card uses (DM `fills`), so the
two cannot disagree.
