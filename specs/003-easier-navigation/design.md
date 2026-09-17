# Design: Navigation that doesn't strand you

**Feature**: `003-easier-navigation` · **Created**: 2026-09-17

## Why this document exists

[spec.md](./spec.md) says what the navigation must *do*, [data-model.md](./data-model.md) says what
it is made of, and [tasks.md](./tasks.md) says in what order to build it. None of them says what any
of it looks like. Phase 3 was behaviour on a surface that already had a house style. Phases 4–6 are
four surfaces that did not exist:

| Surface | Tasks | State at the time of writing |
|---|---|---|
| 1. The Jump-to overlay | T025, T028 | **built** — `JumpTo.tsx`, `styles.css` §JUMP TO |
| 2. Its rail trigger | T027 | **built** — `.rail-jumpto` in `Rail.tsx` |
| 3. The league switcher + season flyout | T031, T032, T034 | **open** |
| 4. The phone return control | T035 | **built** — `.jumpto-fab` |

Three of the four landed while this was being written. So this document does two jobs: it reviews
what shipped against `styles.css`'s own house-style header, and it designs the one surface still
open. Four new surfaces is where an app starts looking like two apps; the review is how that gets
caught before it sets.

## What the house already decides

Most of the design is not a choice. These rules are quoted from the header of `styles.css` and they
pre-resolve the questions a new overlay usually re-litigates:

- **Shape by role, not taste.** 999px badges and chips, 10px controls, 12px floating sheets, 14px
  panels. The overlay is a panel: 14px, the same as `.modal-card`. ✅ as built.
- **Elevation is a claim about layering.** Only things that genuinely float carry a shadow. The
  overlay, the flyout and the phone control do. Result *rows* do not — "what keeps flat elements
  from reading as uniform boxes is tint and hover, not elevation." ✅ as built.
- **Density: match the layout to the shape of the content.** Search results are a list, so they are
  rows, not a grid of cards. ✅ as built.
- **Control hierarchy — three jobs, three treatments.** "navigation → a link, or `.chip` when it
  must sit in a control row. Never a filled button." Every row in the palette navigates. ⚠️ see
  finding A.
- **`--crimson` means "you".** It should appear in this feature exactly once — the signed-in user's
  own row in Managers, via the existing `.avatar-me` ring. Not currently used; see finding B.
- **Never encode with colour alone.**
- **Stacking is declared in one place.** `styles.css` keeps a register of page-level `position:
  fixed` layers and says "Add the next page-level fixed layer here, not inline." ⚠️ see finding C.

---

## Surfaces 1, 2 and 4 as built — review

The palette landed close to right: 560px, top-anchored, one shared highlight for mouse and keyboard,
`role="listbox"` with `aria-selected`, groups named **Pages · Seasons · Managers** (correctly
ignoring tasks.md, which says "Leagues / Pages / Managers" — there is no `league` kind), a 44px
phone control that takes no permanent band. The review below was the remaining gap, not a rewrite.

> **All six applied 2026-09-17.** 404 web tests and `npm run build` pass, and the result was checked
> against live data on `localhost:5173`: a search for `hist` returns five "History" rows each crested
> in its own league's hue, rows are `<a>` elements with real hrefs, Ctrl+K → type → ↓ → Enter
> navigates, a plain click navigates client-side without a reload, and the phone control sits at
> z-index 45 above the page at 375×812.

### A. Result rows are `<button>`, and they navigate

`JumpTo.tsx` renders each result as `<button type="button" role="option" onClick={() => go(row)}>`.
Every one of them ends in `navigate(row.href)`. The house rule is explicit: navigation is a link,
never a button.

The cost is not stylistic. A `<button>` has no `href`, so:

- ⌘-click and middle-click cannot open a destination in a new tab — the single most common thing a
  person does with a navigation list they are scanning.
- The browser shows no target URL on hover.
- Right-click offers no "copy link address".

**Fix**: `<a href={row.href} role="option" aria-selected={…} onClick={…}>`, with the handler calling
`preventDefault()` and `navigate()` only for an unmodified left click — letting the browser have
modified clicks. `a.chip` already establishes the `display: inline-flex; text-decoration: none`
idiom for a link wearing a control's clothes.

### B. A row carries no mark, so five identical labels are told apart at the far end of the row

A query of `hist` produces five rows that all read **History**. The only thing distinguishing them
is `.jumpto-row-context`, which is `margin-left: auto` — roughly 400px to the right of the label,
in `--muted`, at the small size. The eye scans labels down the left edge and finds five identical
words; the discriminator is where the eye is not.

**Fix**: put the league's crest in the row, left of the label — 18px, the same
`oklch(30% 0.05 H)` / `oklch(84% 0.12 H)` treatment `hueForName` already gives it on the home grid
and in the rail. Keep the context text as well; the crest is a second channel, not a replacement.

This inverts the rail on purpose, and it is the decision in this document most worth arguing:

> In the rail you are already inside one league. The thing you are choosing between is the
> destination, so the destination gets the mark — `◷` for History, `▲` for Power rankings.
> In the palette you are choosing between *five* Histories. The thing you are choosing between is
> the league, so the league gets the mark.

Same destination, different question, different mark. Manager rows take the existing `Avatar`
component, which already carries the real Sleeper photo.

The crimson "it's you" ring was **left off deliberately**. `Avatar` takes `isMe`, but nothing in the
search index can answer it: `ManagerSummary` has no such flag and `useUser()` carries a username, not
a manager id. Matching the two by display name would be a second, weaker implementation of an
identity rule the backend already owns — the exact shape of bug this feature exists to remove. It
goes in when the manager list carries `isMe`, and not before.

### C. Two page-level fixed layers were added without updating the register

`.jumpto-backdrop` is `z-index: 60` and `.jumpto-fab` is `z-index: 50`, both written inline. The
register in the house-style header still says only `.modal-backdrop` at 100 and `.rankboard-ghost`
at 50, and it ends "Add the next page-level fixed layer here, not inline."

The ordering itself is right — the palette is below a modal, the phone control is below the overlay
it opens. Two things to fix:

- Add both to the register, with the reason for each level.
- `.jumpto-fab` at 50 **ties `.rankboard-ghost` at 50**, which leaves DOM order deciding. It does not
  bite today (RankBoard only drags inside a `.modal-card.wide`, which is above both), but a tie is
  not a decision. The FAB is phone-only and belongs below the ghost: use 45, or move the ghost.

| Layer | z-index | Why there |
|---|---|---|
| `.modal-backdrop` | 100 | unchanged |
| `.jumpto-backdrop` | 60 | above the page, below a modal — "Mock it" can open one |
| `.rankboard-ghost` | 50 | unchanged |
| `.jumpto-fab` | 45 | above content, below the overlay it opens and below a drag in progress |

### D. A third backdrop recipe

`.jumpto-backdrop` is `color-mix(in oklch, var(--bg) 70%, transparent)` plus `backdrop-filter:
blur(2px)`. `.modal-backdrop` is `rgba(0, 0, 0, 0.55)`. The file already documents why
`.start-overlay` (72%) and `.avail-sheet` (near-opaque) differ from each other — each carries its
reason inline. A third recipe should either reuse `.modal-backdrop` or say in a comment what makes
the palette's dimming a different job. The blur is the part that earns a sentence: nothing else in
the app blurs its backdrop except `.start-overlay`.

### E. Two vocabularies for one thing: the group label

| | font | size | tracking | weight |
|---|---|---|---|---|
| `.app-rail-label` ("League", "Menu") | Plus Jakarta | 10.5px | 0.14em | 700 |
| `.jumpto-group-label` ("Pages", "Seasons") | Oswald (`.cond`) | 10px | 0.1em | — |

Both say "this is the name of a group of navigation rows", and at 70% backdrop opacity the rail's
are still visible behind the overlay's. Pick one and use it in both places. `.app-rail-label` is the
older and more used of the two.

### F. `font-weight: 550` against a static font request

`.jumpto-row-label` asks for 550. `styles.css` imports Plus Jakarta Sans at `wght@400;500;600;700` —
named instances, not a variable axis — so 550 synthesises or snaps rather than rendering as drawn.
Use 600, which is the weight `.app-rail-row.on` already uses for the same "this is the live one"
meaning.

---

## Surface 3 — the league switcher and the season flyout (open)

Today `.rail-league-id` is a `<Link>` to the current board. It becomes the switcher trigger.

**Nothing is lost by taking the link.** "Draft board" is already a row in the destination list three
lines below it, so the crest's link duplicates a link on screen at the same time. Check made because
this codebase has a habit of finding out afterwards.

### Anatomy

```
    rail                           flyout — 260px, fixed, radius 12, shadow
 ┌────────────┐  ┌──────────────────────────────────────────┐
 │ (BK) Ball  │──│  SEASONS                                 │
 │      Knowe▾│  │  [2026] [2025] [2024]                    │  .league-season-link, current .on
 │            │  │  ──────────────────────────────────────  │
 │  ▦ Board   │  │  SWITCH LEAGUE                           │
 │  ◷ History │  │  (RD) Red Dawn                    NFL    │
 │  ▲ Power   │  │  (DY) Dynasty Warriors            NFL    │
 └────────────┘  │  (HC) Hardwood Classic      → History    │
                 └──────────────────────────────────────────┘
```

### Decisions

**It must be `position: fixed`, not `absolute`.** `.app-rail` sets `overflow-y: auto`, which makes it
a clipping box: an absolutely-positioned child extending past the rail's right edge is cut off at it.
Position from the trigger's `getBoundingClientRect()`. This looks fine in an expanded rail on a tall
screen and is discovered broken in a collapsed rail in a draft room.

**It costs zero layout width**, which is what satisfies NFR-004 — a 14-team board already overflows
1440px by 99px with no rail at all, and the flyout is over the page, not beside it.

**Seasons move into it unconditionally**, not only when the rail is collapsed. Today they render
inline and only when expanded, which is FR-009's bug: they are hidden exactly in the draft room,
where an older season's board is most wanted. Two places to find seasons depending on rail state is
worse than one place that is always the same.

**The affordance is a `▾` after the league name**, `--muted`, 9px, plus the existing hover tint. At
56px there is no room for it: the crest becomes the whole target and takes a 2px `--teal` ring on
hover instead. These are two affordances for one control — say so in the code, and why.

**The switch preserves your destination (FR-008).** On `/leagues/A/history`, switching to B lands on
`/leagues/B/history`. From Analysis to an NBA league it lands on that league's History, because
`destinationsFor()` says that league has no Analysis — read from the table, never restated. **The row
says so before you click it**: when the current destination will not survive, the target's row shows
`→ History` in `--muted` at 10.5px where the sport pill would otherwise sit. A silent redirect is how
someone learns not to trust a control.

**Opens on click; closes on** Escape, outside click, a route change, or choosing anything in it. Not
on mouse-out — a 260px panel that evaporates when the pointer strays is a panel you cannot use.

**Reuses**: `.league-season-link` verbatim for the season chips, `.league-crest` and `.sport-pill`
for the league rows, `.modal-card`'s shadow, the 12px floating-sheet radius, and `.app-rail-label`
for the two group labels (see finding E).

---

## What this changes in tasks.md

**T031 and T032 build the same surface.** T031 makes the crest a switcher; T032 replaces the season
gate with "a flyout from the crest". That is one component opened by one trigger, not two, and T032
follows T031 without saying they share a surface. Merge them, or make T032 read "add the Seasons
group to T031's flyout". Built separately, the crest gets two flyouts.

**T025's group names are wrong and the implementation already ignored them.** The task says
"Leagues / Pages / Managers"; `kind` is `league-page | season-board | manager` and `JumpTo.tsx`
correctly labels them Pages / Seasons / Managers. Fix the task text so the next reader does not
"correct" the code back.

Neither is a spec change: FR-005, FR-008 and FR-009 are unaffected.

## Open

- **Resting state.** With an empty query the palette currently shows the whole index (capped at 40).
  One row per league — crest, name, sport pill, season count — teaches what the palette holds in five
  rows instead of forty. Proposed, not decided.
- **Match highlighting in result labels.** Helps at 80 rows, noise at 5. Left out.
- **Recency.** "Where you were last" would be the best first row and there is nowhere to persist it
  today. Not worth a store for v1.
