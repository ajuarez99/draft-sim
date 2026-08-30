# claude/design/ — chosen visual direction, not yet implemented

2026-08-29. Five mockup directions for the draft board screen were explored on a
design canvas (interactive, pans/zooms): https://claude.ai/code/artifact/997a39a3-b6ef-4881-be52-e37d6f9f2ca1

Allan picked two, both variants of the same DNA — dark, rounded, card-based,
avatar-driven, mint accent — and rejected the other three (serif editorial,
light quant-terminal, dense broadcast-red scoreboard) as "look[ing] too AI
coded." **That verdict is itself worth remembering when proposing a direction
here again**: don't default to the flat minimal/editorial-serif look next time.

- `reference-synthesis.html` — **primary target.** Rounded scoreboard cards +
  avatars, dual accent (crimson = your pick / emphasis, teal = model
  confidence). This is the `Main` artboard on the canvas above.
- `reference-social-cards.html` — **secondary reference**, close second. Same
  card/avatar DNA, single mint accent, no crimson. Useful for anywhere a
  second accent color feels like too much.

Open either `.html` directly in a browser for a rough look — they're exported
straight from the design-canvas source, not a real page, so treat them as
*style* reference (colors, shapes, type, spacing), not markup to copy.
`web/src/*` is the real source of truth for actual components/logic.

**Not yet built.** This is the spec for whoever does the re-skin next.

## Tokens to adopt (from `reference-synthesis.html`)

Replace `web/src/styles.css`'s `:root` block:

    --bg:      oklch(13% 0.02 250)   /* was #0f1115 */
    --panel:   oklch(18% 0.025 250)  /* was #171a21 */
    --line:    oklch(28% 0.02 250)   /* was #262b36 */
    --text:    oklch(95% 0.005 250)  /* was #e6e8ee */
    --muted:   oklch(63% 0.02 250)   /* was #8b93a7 */
    --crimson: oklch(58% 0.19 25)    /* new — "this is my pick" / emphasis only */
    --teal:    oklch(72% 0.14 175)   /* new — replaces --accent for confidence/positive/controls */
    --qb: oklch(68% 0.14 45); --rb: oklch(68% 0.14 150);
    --wr: oklch(66% 0.15 235); --te: oklch(66% 0.14 300);

Fonts (Google Fonts `@import` at the top of `styles.css`): **Plus Jakarta
Sans** for everything (replaces the current system-ui stack), **Oswald**
condensed for round labels only (`R1`/`R2`/... instead of a plain number).
Give both real fallback stacks — PNG/PDF export of the mockups can't embed
Google Fonts, but that doesn't matter here since this is a live app, not an
export.

**The accent split is a rule, not decoration** — keep it that way when
implementing: crimson touches only "this is your pick" and emphasis states;
teal is confidence bars, links, and primary buttons. Don't let them bleed
into each other's job.

## Component changes

1. **`DraftBoard.tsx`** — currently a `<table>`/`<td>` grid
   ([DraftBoard.tsx](../../web/src/components/DraftBoard.tsx)). Re-skin cells
   as rounded div cards (`border-radius: 10px`, `background: var(--panel)`)
   inside a CSS grid, not table cells — matches the mockup and survives
   direct styling better than table cell borders. Add a thin teal bar under
   each cell scaled to `probability` (see `.bar`/`.bar-cell` in
   `reference-synthesis.html` for the pattern — 2px height, `var(--line)`
   track, `var(--teal)` fill). Replace `.cell.mine`'s background tint with a
   `box-shadow: 0 0 0 1.5px var(--crimson)` ring instead. Leave the snake-index
   math, `title` tooltip, and `uncertain` dotted-underline logic untouched —
   this is a re-skin, not a logic change.
2. **`SeatList.tsx`** — add a circular initials avatar before `.who` in
   `.seat-head` ([SeatList.tsx:126-136](../../web/src/components/SeatList.tsx)).
   Color per-manager by hashing `manager` (or `managerId`) to a hue, same
   approach as the mockup's per-seat avatar colors — don't hardcode a palette
   per manager name, the real roster changes per league. Keep the existing
   `LABEL`/provenance branching logic exactly as-is (`3d65302`/`39f4a72`
   already got this right, verified live) — only the chip colors move to the
   new tokens (`your call` → teal, `from history`/`both` → rb hue).
3. **Round header** — swap the plain round-number `<th>` for the condensed
   Oswald "R1" style shown in both mockups.
4. **Top control bar** (`App.tsx`) — re-run button and control chips move to
   `var(--teal)` (was `var(--accent)`); no structural change.

## Open, unverified before landing this

Both mockups only mocked a 10-team league at 4 of 14 rounds with placeholder
players, to make a fair side-by-side on the canvas. The real board is
12–14 teams × 14–15 rounds (`claude/README.md` league facts). Card sizing at
the real column count/row count is **not checked** — confirm it doesn't force
horizontal scroll or shrink cards below readable size before calling this
done, per this project's own verified-vs-assumed convention.

---

## Status update — 2026-08-30: built, then substantially reworked past this spec

The original re-skin above (tokens, fonts, card/avatar DNA) **landed and is
in place** — `styles.css`'s `:root` block, Plus Jakarta Sans + Oswald, the
crimson/teal accent split, per-position hues. But two rounds of Allan's own
live feedback took the actual board and page layout well past what this doc
originally specified, and are the more current source of truth for anyone
touching `DraftBoard.tsx`, `SeatList.tsx`, `AvailabilityPanel.tsx`, or the
page shell (`App.tsx` / `pages/DraftView.tsx` as of the same-day router
split — see below).

### Round 1 feedback: flat card grids still read as generic/"AI coded"

Fixed two things, now baked into `styles.css`, not just this board:
- **Position pills** went from solid saturated badges (dark text on a bright
  fill) to soft tinted pills — `color-mix(in oklch, var(--hue) 24%,
  var(--panel))` background, the hue itself as the text color. Applies
  everywhere `.pos` is used (board cells, `AvailabilityPanel`'s player rows).
- **Every repeated card unit** (board cells, `.seat` cards) got a resting
  `box-shadow` plus a hover-lift transition, instead of sitting perfectly
  flat with zero interaction feedback.
- Full reasoning kept in memory as `feedback-avoid-flat-uniform-cards` — read
  it before proposing card-grid UI for this user again; it also covers the
  round-2 correction below.

### Round 2 feedback: the actual reference is Sleeper's own mock-draft room, not a card grid at all

Allan named Sleeper's mock draft room directly. Pulled the real thing —
`sleepercdn.com/.../draft_web_dark.webp` off Sleeper's public
`/fantasy-football` marketing page, no login needed — rather than guessing
from memory. It is **not** a grid of floating shadowed cards: it's a dense,
contiguous spreadsheet-style grid (flat cells, 1px hairline dividers, zero
per-cell radius, highlight by fill color not shadow/ring), with avatar+name
column headers, sitting above a wide "players" table + a narrow
roster/chat sidebar.

`DraftBoard.tsx`/`styles.css` were reworked to match the board specifically:
- `.board` uses a `gap: 1px; background: var(--line)` grid so cells never
  set their own border (no doubled lines at shared edges) — this is what
  draws the spreadsheet look, not per-cell borders.
- Column headers are per-manager avatars (hashed hue via the new
  `web/src/hue.ts`, shared with `SeatList.tsx` so the same manager is the
  same color in both places) instead of bare slot numbers.
- `.cell.mine` is a solid crimson-tinted fill (`color-mix`) + inset ring,
  not just a ring on a flat panel — matches how Sleeper highlights by color.
- Pick number moved into the cell corner (`.pickno`), matching the
  reference, freeing the old separate `.prob` line into a `.meta` row
  (team code + probability, flex `space-between`) so each cell carries a
  bit more real information instead of two stacked identical-looking lines.

**This intentionally does not apply to every card on the page** — `.seat`
cards and the round-1 depth/hover treatment still stand for genuinely
card-shaped content. The grid treatment is for the board specifically,
because the board is genuinely grid-shaped and Sleeper's own reference
treats it that way. Match the visual language to the content's real shape;
don't apply one treatment everywhere on the page.

### Round 3 feedback: page was too long, Seats was a squeezed single-column list, not "side by side"

First attempt at fixing round 2 put `AvailabilityPanel`/`ConfidenceNote`/
`SeatList` into a wide-main + narrow-320px-sidebar split. **Wrong move for
Seats specifically** — 14 manager cards forced into a 320px column just
became a long single-column list, the opposite of "side by side." Sleeper's
own narrow sidebar is short (≈9 roster slots, single-line rows); draft-sim's
14-manager tendency cards aren't that shape and don't belong squeezed
narrow.

Reworked into a viewport-fitting shell instead of a long scrolling page:
- `.app` claims `height: 100vh`; `.content` is a flex column whose three
  bands (`.board-panel`, `.lower-grid`, `.seats-panel`) split the remaining
  height by flex-grow ratio (3 / 2 / 2) rather than each growing to its
  natural content height and stacking into one long page.
- Each band's actual content area (`.board-scroll`, `.avail-scroll`,
  `.seats`) carries the shared `.panel-body` class — `flex: 1; min-height:
  0; overflow: auto` — so it scrolls internally within its band. `min-height:
  0` has to be threaded through every flex/grid ancestor in this chain or
  the child never actually shrinks and the inner scroll never kicks in —
  that's the one non-obvious CSS mechanic behind this whole section.
- Board column headers and round labels are `position: sticky` (top and
  left respectively) within `.board-scroll`, and the `AvailabilityPanel`
  table header is sticky within `.avail-scroll`, so scrolling either region
  doesn't lose the headers that make the numbers legible.
- **`SeatList` went back to full width**, not the sidebar — `.seats` is a
  `repeat(auto-fill, minmax(240px, 1fr))` grid, so at the app's normal width
  it lays out several manager cards per row ("side by side") instead of one
  per row.
- Verified in-browser against a throwaway mock backend (real backend needs
  Postgres, not running) at the real 14×15 scale: the no-result state fits
  exactly one 900px viewport with zero page scroll (`document.body.
  scrollHeight === window.innerHeight`). **Not yet re-verified after the
  same-day router split below moved this code from `App.tsx` into
  `pages/DraftView.tsx`** — the JSX/CSS carried over unchanged in the diff,
  but confirm the one-screen fit and sticky headers still hold there before
  treating this as done.

### Unrelated same-day change worth knowing about: the page got a router

While this was in progress, a **separate concurrent Claude Code session**
built Feature B from `claude/next-features-roadmap.md` (app shell + draft
picker) — `App.tsx` is now just the router shell (`<Link>` header +
`<Routes>`), `pages/DraftPicker.tsx` lists ingested drafts, and everything
described above now lives in `pages/DraftView.tsx` instead of `App.tsx`
directly. It cleanly absorbed the layout work above rather than conflicting
with it — the `.content`/`.board-panel`/`.lower-grid`/`.seats-panel` JSX
moved into `DraftView.tsx` verbatim. `DraftPicker.tsx` also extended the
same design language correctly on its own (`.draft-list`/`.draft-row` — a
compact row list, explicitly *not* a card grid, per its own comment,
because a draft-picker row is one line of real content, not card-shaped
content either — same "match the treatment to the content's real shape"
principle as the board).

**Multiple concurrent sessions were active in this repo at the same time**
(this one, the router-refactor session, and a backend-focused session — see
`HANDOFF.md`'s "live poller" section for that one). If you're picking this
up next, diff carefully against what's described here rather than assuming
it's still accurate — this is exactly the kind of doc that goes stale fast
under concurrent editing.

## What to move on to next

1. **Re-verify the viewport-fitting shell inside its new home.** Confirm in
   a real browser (with `seats={seats?.seats}` wired, a real or mock
   backend) that `DraftView.tsx` still fits one screen and that sticky
   headers/internal scroll still work post-move — this was verified once,
   in `App.tsx`, before the router split relocated the code.
2. **fantasy(heart)'s real draft is 2026-08-31 21:15 CDT** — one day out as
   of this writing. `HANDOFF.md`'s live-poller section flags its own open
   risk (never observed a truly live Sleeper `drafting`-status draft) as the
   thing that most needs a dry run before then; that's a backend/ops
   priority ahead of further design work, not a UI task, but it does mean
   the reserved `/drafts/:draftId/live` route in `App.tsx`'s own comment is
   the next real UI surface once the poller's live-fire dry run is done.
3. **Mock draft room (Feature C)** should reuse `DraftBoard`'s grid per
   `claude/next-features-roadmap.md` §3.5's own resolved decision — feed it
   real committed picks with `landed: true` throughout, no probability
   data. The sticky-header/spreadsheet-grid work above is exactly the
   component C is supposed to reuse rather than rebuild.
4. **Ad-hoc league-size UI (Feature A's frontend piece) stays last**, per
   `feature-priority-league-size-last` memory — unchanged by anything above.
