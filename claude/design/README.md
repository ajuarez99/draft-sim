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
