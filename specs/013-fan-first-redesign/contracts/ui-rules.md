# Contract: UI rules

This is the contract every changed page is checked against. It becomes the new
house-style header in `web/src/styles.css`. That comment is what future sessions
read, so it's updated in the same change that implements it.

## Surfaces
- At most **two** nested bordered/filled surfaces: the page (`--bg`), and one raised
  surface (`--panel`). A table or list inside `--panel` uses row separators, never
  per-row borders.
- A card is allowed only for card-shaped content: a matchup, a player, a seat. A
  one-line record is a row.
- Section titles are headings on the surface, not the header strip of a box.

## Type
- Oswald uppercase: the page H1 and hero numbers only.
- Section titles: Plus Jakarta 600, sentence case.
- Numbers: Plus Jakarta with `tabular-nums`. The code-style font is used only for
  things that really are code (ids, URLs).

## Color: one meaning each
| Meaning | Token | Never used for |
|---|---|---|
| You | `--crimson` (ring/text), `--crimson-fill` (behind text) | anything not the viewer's own |
| Interaction | `--teal` | primary actions, data |
| The page's primary action (max one per page) | `--volt` with `--bg` text | links, navigation, secondary actions |
| Better / worse | `--up` / `--down` | identity |
| Earned / awards | `--fitted` | positions |
| Position | position tokens | anything else |
Every text/background pair is at least 4.5:1 (measured, research R10).

*Amended during build (Phase 2):* hue alone can't keep these meanings apart. There
are 18+ tokens in one dark palette: `--up` (150) is RB's hue exactly, `--down`
(350) sits 9° from NBA's C (359), and `--volt` (125) is near the NFL sport tag
(128). Context does the separating:
- position colors appear only on position badges and tinted cells that always
  carry the position as text;
- `--up`/`--down` always come with a sign, an arrow or the words Won/Lost;
- `--volt` only ever fills the page's one primary button.


## Words
- Subtitle: one sentence, the takeaway. Forbidden in subtitles: "computation",
  "computed", "stored", "snapshot", "simulation run", "standard error",
  "endpoint", "payload".
- Methodology goes in a `<details>` "How this works" disclosure at the end of its
  section. It keeps the original text (edited only for clarity, never for claims).
- A caveat that qualifies one number stays beside that number, as a badge.
- Before any page's copy changes, its caveats are inventoried (quickstart §2), and
  the inventory is diffed after the change.

## Navigation
- Groups: League home · This week · The season · Draft · History. The group holding
  the current page is expanded.
- The league + season switcher is at the top of the league block.
- Collapsed rail (draft views): glyph plus `title` and `aria-label` from the
  destination's label. Never an unlabeled mark.
- All addresses unchanged. Jump-to search matches both the fan name and the former
  name.

## Player faces
`photo → team logo → initials`, fixed size, lazy loading, no layout shift, never a
broken-image icon. Same for NFL and NBA.

## Screen sizes
Every changed page is checked at 1440×900, 768×1024 and 375×812:
- no horizontal page scroll (the draft board scrolls *inside* its own frame, as today);
- nothing clipped;
- long team names truncate with the full name in `title`.

## Commissioner controls
Shown only when the page's payload says `canCommission`. They're never inferred on
the client.
