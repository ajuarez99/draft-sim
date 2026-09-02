# draft-sim — position-colored pill cells, and the player list on top

Design note, 2026-09-02. Allan marked up the running draft view side by side with a
Sleeper draft room (Sleeper's board on top, its player list docked directly under it)
and gave five pieces of feedback in one message (plus one clarification, folded
into §E):

1. "Lets just color each box that whole color based on position and also add position."
2. "The 100% makes no sense in the box as it's not important when we make that pick
   anymore."
3. "How about we also add their rank as well as position, like RB1 RB2."
4. "Lets make the boxes less edgier and more circular, pill like Sleeper."
5. "That availability at your picks modal needs to come up more, like in Sleeper's,
   instead of two modals down the page." — clarified twice in follow-ups: first
   "put it on top ... to give the user more options to view players," then **"the
   board takes the full page, just behind the availability component."** See §E;
   the panel becomes a floating layer over a full-height board, which is neither
   the current split nor Sleeper's own docked list.

Same convention as `claude/board-first-layout-and-pick-latency.md`: this is the
reconciled brief, written to stand on its own for whoever builds next.

**Built and verified 2026-09-02 — see "Built" at the end of this file for what
changed from the plan and what was measured rather than assumed.**

Everything below lands in two shared components — `web/src/components/DraftBoard.tsx`
and `web/src/components/AvailabilityPanel.tsx` — plus `web/src/styles.css`. Both
components are rendered by **two** pages: `pages/DraftView.tsx` (mock) and
`pages/LiveDraftView.tsx` (live, `LiveDraftView.tsx:202` and `:320`). Every change
here is automatically a live-draft change too; check both pages before calling any
section done.

## How they relate, and recommended order

(1)–(4) are all one cell, and they fight each other if built separately: the position
fill (1) collides with the crimson "your seat" fill, dropping the percentage (2) frees
the vertical room that the rank badge (3) wants, and the pill geometry (4) is
incompatible with the hairline grid the cells sit in today. Build them as one pass.
(5) is independent and can go first or last.

**Recommended order: B → A → C → D → E.**

1. **B (drop the percentage and the bar)** first — pure deletion, and it is what makes
   room for everything else in a 5-line cell.
2. **A (position fill)** second, because it is the change that forces the "your seat"
   and "your pick" language to be re-decided, and C/D both build on the result.
3. **C (rank badge)** third — small, and it reads correctly only once A has given the
   cell its position color.
4. **D (pill geometry)** fourth. It touches the grid container, the sticky header row
   and the sticky round-label column, and it is the one with a real rendering gotcha
   (§D.2) — do it when the cell's contents have stopped moving.
5. **E (board full-page, player list floating over it)** last, or as a separate pass
   — it is layout only and touches none of the cell work.

---

## §A — Color the whole cell by position

Today `.pos` is a small tinted pill and the cell body is `var(--panel)` for everybody
(`styles.css:222-227`, `:155-161`). The ask is for the cell itself to carry the
position color, the way Sleeper's board does.

**Do:** tint the cell background from the position variable, reusing the existing
`--qb/--rb/--wr/--te/--k/--def` tokens so the board, the `.pos` badges, the picker and
the availability table all keep saying the same thing with the same six colors.

```css
.board .cell.pos-RB { background: color-mix(in oklch, var(--rb) 14%, var(--panel)); }
.board .cell.pos-RB:hover { background: color-mix(in oklch, var(--rb) 22%, var(--panel)); }
```

`DraftBoard.tsx` already computes the position for both branches
(`visible.player.position` and `chosen.position`); add `pos-${position}` to the `cls`
string it builds around line 108.

### A.1 — The tint percentage is the whole job

Sleeper's cells are near-fully saturated because their text is near-black on it. Ours
is `var(--text)` at `oklch(95%)` on a dark ground, so a fill anywhere near Sleeper's
saturation will fail contrast against the player name. **Start at 12–16% and check,
don't eyeball it.** The name must stay ≥ 4.5:1 against the tinted fill for all six
positions; `--k` and `--def` are deliberately near-gray and will pass trivially, so
`--qb` and `--wr` are the ones to measure.

If 14% reads as too washed to be worth doing at all, the fallback that keeps the
signal without the contrast fight is a **thick left edge** in the position color
(`box-shadow: inset 3px 0 0 var(--rb)`) over a lighter fill. Say so explicitly in the
handoff if you take the fallback — it is a visible departure from what was asked, not
a detail.

### A.2 — This collides with crimson, and crimson has to move

`.cell.mine` and `.col-head.mine` currently *fill* with crimson
(`styles.css:240-241`), and `.cell.chosen` adds a second inset ring (`:245`). If the
fill now belongs to the position, "your seat" can no longer be a fill.

**Resolve it this way:** position owns the **fill**, crimson owns the **ring**.

- `.cell.mine` → `box-shadow: inset 0 0 0 1.5px var(--crimson)`, no background override.
- `.cell.chosen` → keep the doubled ring (`inset 0 0 0 1.5px` + `inset 0 0 0 4px` at
  35%) and the `✓ yours` badge, which is what actually distinguishes a pick you made
  from a seat you own.
- `.col-head.mine` keeps its crimson fill — column headers have no position, so
  nothing is competing for it there, and your seat should still be the loudest thing
  in that row.

Verify the mine-but-not-yet-chosen cell is still findable at a glance across a full
14×16 board; that was the explicit reason the original styling kept `chosen` as an
addition rather than a recolor (comment at `styles.css:242-244` — update it, it
describes a fill that will no longer exist).

### A.3 — The position badge stays

Item (1) says "and also add position." The `.pos` badge is already there in both cell
branches; §C changes its *text*, not its existence. Keep it: the fill alone would make
position a color-only signal, which fails for a colorblind user and for anyone who has
not memorized six hues.

---

## §B — Drop the percentage and the confidence bar from the cell

Remove from the `visible` branch of `DraftBoard.tsx` (around line 137):

- `<span className="prob mono">{...}%</span>`
- the whole `.bar-track` / `.bar-fill` block

and the now-dead `.board .prob`, `.board .bar-track`, `.board .bar-fill` rules
(`styles.css:250-252`).

**Do not delete the number itself.** It is real model output and it still belongs in
two places, both of which are opened *from* the cell:

- the cell's `title` attribute (`titleAttr`, ~line 114) — leave it exactly as is
- `PlayerCard.tsx` — "…% of runs" in the modal body, leave as is

**Keep `.cell.uncertain`.** It is not the percentage; it is the separate, qualitative
statement that the most-likely player here was already gone (`styles.css:238-239`). It
stays as the faded/dotted name.

With the bar and the meta row's right half gone, `.meta` (`styles.css:248`,
`justify-content: space-between`) now holds only the team code — change it to a plain
flex row so the team code doesn't float to the far edge on its own.

---

## §C — `RB1`, not `RB`

`PlayerRef.positionalRank` already exists on the API type (`api.ts:14`) and is already
formatted once, in the picker:

```ts
// PlayerPicker.tsx:22
return p.positionalRank === 999 ? `ADP ${Math.round(p.adp)}` : `${p.position}${p.positionalRank}`
```

**Extract that into `web/src/posRank.ts` and call it from both places** rather than
copying it — 999 is Sleeper's "no rank" sentinel and there should be exactly one place
that knows it (`api.ts:11-13`).

The board's pill is ~24px wide and cannot hold `ADP 47`. Give the shared helper a
compact mode, or a second export, whose 999 fallback is the bare position (`RB`)
rather than the ADP string. The picker keeps the ADP fallback it has today — it has
the width, and there the ADP is genuinely more useful than a blank.

One badge, not two: the pill reads `RB4`, not `RB` next to `RB4`. The position is
already in the letters *and* in §A's fill.

Apply the same badge to the two other places a player is named, so the board and the
shopping list agree:

- `AvailabilityPanel.tsx` player column (line ~89)
- `PlayerCard.tsx` header and alternatives rows

`.pos` has `min-width: 24px` (`styles.css:155`); `RB12` will overflow it. Widen to
`min-width: 30px` and confirm `DEF` and a two-digit rank both still fit without the
pill wrapping.

---

## §D — Pill geometry

`.pos` is already `border-radius: 999px`. The ask is for the **cells** to read that
way: `border-radius: 10–12px`, not the hard 0 they have now.

### D.1 — The hairline grid has to go with it

The board draws its grid lines by giving `.board` a `var(--line)` background and a
`gap: 1px`, so the container shows through between flush cells (`styles.css:182-184`).
Rounded corners on that produce four little dark notches at every cell corner — it
does not degrade gracefully, it just looks broken.

So this is a two-part change:

```css
.board { gap: 4px; background: var(--bg); }
.board .cell, .board .col-head { border-radius: 10px; }
```

The long comment above `.board` (`styles.css:174-181`) argues *for* the flat,
contiguous, hairline look and against "a wall of floating cards." **Rewrite it.**
Leaving a comment that argues the opposite of what the code does is worse than having
no comment. The honest version of the new rationale: separated rounded tiles, but with
a small gap (4px, not 12) and no drop shadows, so the board still reads as one dense
board rather than a scattering of cards. That distinction is the standing note in
`feedback_avoid_flat_uniform_cards` — the position fill and the hover state are what
keep these from being uniform cards; do not add per-cell shadows on top.

### D.2 — The gotcha: sticky seams

`.col-head` is `position: sticky; top: 0` and `.rnd` is `position: sticky; left: 0`
(`styles.css:191-196`, `:217-221`). Today they are flush, so a scrolling cell passes
cleanly behind them. Once there is a 4px transparent gap between adjacent sticky
headers, **rows scrolling underneath will be visible through the seams** — a strip of
moving player names between every pair of column headers.

Two ways out; take the first:

1. Paint the seam from the sticky element: `box-shadow: 0 0 0 4px var(--bg)` on
   `.col-head` and `.rnd` (and both directions on `.corner`), so each sticky tile
   carries its own opaque bleed into the gap. This combines with the crimson ring on
   `.col-head.mine` — list both shadows, ring first.
2. Give the header row its own opaque sticky backing element behind the tiles. More
   markup, and it fights the grid; only if (1) misbehaves.

Check this by actually scrolling a 16-round board both ways, not by reading the CSS.
`.board-scroll` keeps its own `border-radius: 10px` and stays the scroll container —
do not move overflow onto `.board` itself; the comment at `styles.css:256-261`
explains why (it would break sticky entirely).

### D.3 — Budget

4px of gap × 15 column gaps is 60px of width the board did not previously spend, and
the same again vertically per round. If the board gets tight at 14 teams, drop to 3px
before touching the `minmax(96px, 1fr)` column floor (`DraftBoard.tsx:44`).

---

## §E — Board fills the page; the player list floats over it

Today `.content` is a vertical flex where the board is `flex: 5 1 0` and the
availability row is `flex: 2 1 0` (`styles.css:277`, `:313-316`), inside an `.app`
that is `height: 100vh`. So the panel is already *supposed* to be on-screen — the
complaint is that in practice it reads as a second thing far below the board.

**Decision (2026-09-02 follow-ups, in order):** first "put it on top ... to give the
user more options to view players," then "the board takes the full page, just behind
the availability component." The second supersedes the first. **The two panels stop
splitting the vertical budget entirely: the board gets the whole content area, and
the availability panel becomes a layer floating over it.**

That is what resolves the tension the earlier draft of this section had to flag —
there is no longer a ratio to trade off against `board-first-layout-and-pick-latency.md`
§4 ("let the predicted board take up most of the space"). The board gets all of it.

This is deliberately *not* Sleeper's arrangement (Sleeper docks its player list under
the board in a fixed split). It is closer to Sleeper's mobile queue sliding over the
room.

### E.1 — Structure

`.board-stage` is already `position: relative` and already hosts one absolutely
positioned child, `.start-overlay` (`styles.css:282-296`). Put the availability panel
in there as a second one.

- `.board-panel` → `flex: 1 1 0` (the whole `.content`), `.lower-grid` deleted
  outright rather than renamed — it is a one-cell grid whose only job was the split
  (`styles.css:309-317`).
- `AvailabilityPanel` moves inside `.board-stage`, in both `DraftView.tsx` (~line 509)
  and `LiveDraftView.tsx` (~line 320).
- Keep the scrubber and PickPrompt where they are — above `.board-stage`, in the
  board's own `<section className="panel">` (`DraftView.tsx:426-451`). They describe
  the board's reveal state, they are one line tall, and putting them under the
  floating layer would bury "you're on the clock."

### E.2 — Anchor it to the top, below the sticky header row

> **Wrong, corrected in the build below.** The sheet is anchored to the
> **bottom**. This section's reasoning ("the top rows are already resolved")
> is backwards for most of a draft: at pick 18 the only rows with anything in
> them are the top ones. Kept as written so the mistake is legible.

Anchor the floating panel to the **top** of the stage, `left: 0; right: 0`, about
40–45% of the stage height, and let the board run full height behind it.

Top is the right edge to cover: the rows it hides are the earliest rounds, which are
already resolved and are the least useful part of the board while you are on the
clock. The live action is at the current pick, further down.

**But do not cover the column headers.** `.col-head` is the seat identity row —
avatar, manager name, your crimson seat — and it is what makes every cell below it
legible. Offset the panel's `top` by the header row's height so that row stays
visible above it. Measure the height rather than hard-coding a guess; if that proves
awkward, a CSS custom property set once on `.board` and read by both is fine, but say
in a comment that the two are coupled.

### E.3 — It is a layer, not a modal

Allan's original complaint was about "modals down the page." This must not become a
third one:

- **No backdrop, no dimming, no focus trap, no `aria-modal`.** The board behind it
  stays fully interactive wherever it is not covered.
- **No Escape handler.** `PlayerCard` already binds Escape on `window`
  (`PlayerCard.tsx:14-19`); a second binding would fire both. Collapse is a button.
- **A visible collapse control**, in the panel header next to the position chips —
  this is the whole point of "the board takes the full page ... behind." Collapsed
  state leaves only the header bar (chips + depth slider + chevron) floating, and the
  board is unobstructed. Component state is enough; persisting the collapsed flag to
  `localStorage` is a nice-to-have, not required.
- **Collapsed by default until `started`**, so it never sits on top of the
  "Ready when you are" CTA in `.start-overlay`. This also replaces the old problem of
  the panel being gated on `started` and shoving the layout when it appeared: it is
  now always rendered, and starting a draft expands it in place without moving
  anything.

### E.4 — Legibility over the board

`.start-overlay`'s recipe (`bg` at 72% + `blur(2px)`) is right for a centered CTA and
wrong for a data table — a translucent ground under 60 rows of small text is hard to
read and the board's own colors will bleed through the position pills.

Use a **near-opaque** surface (`var(--panel)` at ~94–96%) with a real drop shadow and
a rounded lower edge, so it reads as a sheet lying on the board. This is the one place
in this whole brief where a drop shadow is correct — §D bans them on cells because
cells are not floating; this genuinely is. Keep `backdrop-filter: blur(3px)` for the
few percent that does show through.

Give the scroller `overscroll-behavior: contain` so reaching the end of the player
list does not start scrolling the board underneath it.

### E.5 — Stacking

There are now four layers over the grid, and the order has to be written down rather
than discovered: cells (`z-index: 3` on `:focus-visible`), sticky `.col-head`/`.rnd`
(`1`), the availability sheet, and `.start-overlay` on top of everything. Define them
as one short block of adjacent rules with a comment naming the order, not as four
numbers scattered through the file.

### E.6 — Keep the panel's own chrome

Keep `.panel-head` sticky inside the sheet with the position filter chips and the
depth slider in it, and give those chips the same position-colored treatment as
everywhere else (`QB`/`RB`/`WR`/`TE` tinted by position, `ALL` neutral) so the sheet
reads as part of the same board it is lying on.

**Out of scope, worth noting for later:** Sleeper also scrolls its list to what you
need when you are on the clock. When `reveal.pausedAt` is set we know exactly which
pick the user is deciding — auto-focusing that column, or filtering to the roster hole
`teamNeeds.ts` computes, is the obvious follow-up. Not part of this pass.

---

## Verification

Static build first — both pages and both components are TypeScript, and the shared
`posRank` extraction will surface any type drift:

```
cd web && npm run build
```

Then the running app. `.claude/launch.json` already has `draft-sim-web` (vite, 5173)
and `draft-sim-api` (Spring, 8080); the backend must be up or the board renders empty.
Several Claude sessions share this tree, DB and server — check whether 8080 and 5173
are already listening before starting anything (`AGENTS.md:58`).

Check all seven, with screenshots:

1. A full board mid-reveal: six position colors readable, player names contrasting
   against every one of them.
2. Your own column and one of your own picks: the crimson ring still the most findable
   thing on the board (§A.2).
3. Scroll the board down *and* right to the far corner: no rows bleeding through the
   sticky seams (§D.2).
4. An `uncertain` cell: still visibly faded, now that the percentage is gone.
5. A player with `positionalRank === 999`: badge reads `RB`, not `RB999` and not
   `ADP 47` overflowing the pill.
6. The floating sheet: collapse it and confirm the board behind is genuinely
   unobstructed and still scrollable; expand it and confirm the column headers are
   still visible above it (§E.2) and that scrolling to the end of the player list
   does not start scrolling the board (§E.4).
7. The live page (`LiveDraftView`), not just the mock page — same board, same
   floating sheet, and the collapsed pre-start state from §E.3.

---

## Built, 2026-09-02

All five sections implemented and verified in the running app (mock page and live
page, backend on 8080). `npm run build` clean. Two deliberate deviations from the
brief above, both found by looking at the result:

1. **The `.pos` badge became solid, not tinted.** It was a 24% tint of the position
   color on `--panel`, which worked only while every cell behind it was `--panel`.
   Once the cell is itself a tint of the same color the badge sinks into it. Solid
   fill with `--bg` text instead — measured 4.7:1 at the tightest (`--def`), and it
   makes the badge the one thing in a dense cell readable at a glance, which is what
   now carries the rank.
2. **The sheet is anchored to the bottom of the stage, not the top.** §E.2 argued
   for the top on the grounds that the rows up there are already resolved. Shipped
   that way and looked at in a real paused draft, it was exactly backwards: at pick
   18 the sheet covered every pick that had happened and left seven empty rounds on
   show. The deep rounds are what stays empty for almost the whole session, so they
   are what the sheet lies on. `bottom: 18px` rather than `8px` so it clears the
   board's own horizontal scrollbar instead of sitting on it. This also deleted the
   `--col-head-h` constant §E.2 needed — nothing is offset past the header row any
   more.
3. **The `✓ yours` badge is gone.** §A.2 kept it as the thing distinguishing a pick
   you made from a seat you own. In practice your whole column is already ringed
   crimson, so a badge on every cell in it repeated the same claim — Allan called it
   redundant on sight and it was. What separates a made pick from a predicted one is
   now the doubled inner ring, the cell's `title` ("Your pick — <name>"), and the
   player in it being the one you took. Dropping it also collapsed the two cell
   branches in `DraftBoard` into one: a chosen cell and a predicted cell render
   identically now, and only `cls`/`titleAttr` differ.
4. **The collapsed sheet is a corner pill, not a full-width bar.** §E.3 assumed
   collapsing to "only the header bar." Built that way it still covered the whole of
   round 1 — not what "the board takes the full page behind it" means. Collapsed now
   drops the title, filters and depth slider and leaves a single `▾ availability`
   pill in the stage's top-right corner.

**Follow-up fix, same day: the deep rounds were unreachable, not just awkward.** The
board scrolls *behind* the sheet, so at maximum scroll the last rounds were still
underneath it — no amount of scrolling could bring round 13-15 into the clear. `.board`
now carries `padding-bottom: var(--avail-sheet-reserve)`, which `AvailabilityPanel`
publishes onto `.board-stage` from a `ResizeObserver` on itself (removed when
collapsed, so a corner pill doesn't reserve a row of dead space). Verified at max
scroll on a full 210-pick board: R15's bottom sits 22px above the sheet's top.

Allan asked for this explicitly as *the ability* to scroll, **not** auto-scrolling:
"not saying to do it automatically but i need the ability to scroll down the draft
when it gets down there." Auto-scrolling the board to the paused pick therefore stays
unbuilt on purpose — do not add it without asking.

Measured rather than eyeballed, in the running board:

- Cell tint went to **22%**, not the 12–16% §A.1 guessed at. At 22% the player name
  clears **12:1** against every position (`--rb` tightest) and the faded `.uncertain`
  name clears **5.5:1** — the section's worry about washing out the name was
  well-founded in principle and simply wrong about where the ceiling was.

Not exercised: **no player in the current pool has `positionalRank === 999`**, so the
sentinel fallback (`RB`, not `RB999`) is verified by code path only — 0 bare-position
badges across a full 210-pick board, which is consistent with both "the fallback
works" and "it never fired." Worth re-checking after an ingest that adds fringe
players.

Also added, at Allan's request: a **house-style header at the top of `styles.css`** —
the position-color system and its three usages, the crimson-yields-the-fill rule, the
radius-by-role scale, the elevation rule, the 100vh/min-height layout rule and the
stacking order. Future components should read it before inventing a color or a shadow.
