# Propagating the home shell to the rest of the site

**Status:** DONE — all five phases implemented 2026-09-14, in four commits
(`e9f2d70`, `b0ed7b2`, `6174958`, and this one). Written against `main` @
`1543c3d`. The "How it actually landed" notes under each phase are the record
of where the built thing differs from the plan, and why.

**Supersedes the scoping decision in** `0c6f0ab` ("this is a Home-only layout swap,
not a site-wide nav redesign") and the comments in `App.tsx:76-79`,
`components/Sidebar.tsx:27-31` and `styles.css:186-190` that state the same thing.
Those comments were correct when written — they were a scoping fence, not a design
preference — and this plan is the other half of that work.

---

## A. What actually differs today

The redesign landed one new shell and left the old one in place everywhere else.
The site now has two, and the split is exactly one route wide.

**Home (`/`)** — `App.tsx` sets `isHome` and suppresses `.top`; `DraftPicker`
renders `.home-shell` = a 200px vertical rail (`components/Sidebar.tsx`) beside a
`.home-main.content` pane that scrolls on its own. `.app:has(> .home-shell)` zeros
`.app`'s padding so the rail runs edge to edge against `--panel` with a right
hairline. The rail carries, top to bottom: a two-line stacked wordmark, a **Sports**
section (filter rows with a sport-colored dot and a count), a **Menu** section
(Home / Managers / Mock drafts), a spacer, then the account avatar and Sign out.

**Every other route** — the horizontal `.top` bar: single-line wordmark at left,
two `.chip` links, avatar + Sign out at right, a `.top-slot` portal target between
them, then `.content` inside `.app`'s 16/20 padding.

Verified live on ballknowers.co: `/managers`, `/leagues/:id/history`,
`/leagues/:id/power`, `/drafts/:id/board` and `/mock/:id` all still render the old bar.

The rest of the gap follows from that one structural difference:

| | Home | Rest of site |
|---|---|---|
| Navigation | persistent vertical rail | horizontal bar, 2 chips |
| Where you are | rail row is `.on` | nothing — no current-page marker off `/managers` |
| Way back out | rail is always there | `/managers`, `/mock/:id`, `/drafts/:id/board` have **none** except the wordmark; only PowerRankings has a real back link (`← League history`, `PowerRankings.tsx:751`) |
| Page identity | `.home-eyebrow` / `.home-title.cond` / `.home-header-sub` + primary CTA | `.panel h2` — 15px uppercase muted, *inside* the box, so the page has no title of its own |
| Explanatory copy | one 13px `.home-header-sub` line under the title | a `.muted.small` paragraph stuffed inside the first panel (`ManagerTendencies.tsx:281`, `LeagueHistory.tsx:104`, `MockSetup.tsx:133`) |
| Screen edges | edge-to-edge, pane scrolls internally | padded column |

**The thing to fix first is the rail.** Everything in that table below row one is
downstream of it: a persistent rail is what gives every page a current-location
marker and a way back, and it is what frees the page header to stop being a panel
heading. Do not start with the typography.

---

## B. The one real obstacle: draft rooms do not have 200px to give

Measured on the deployed board at a 1440px viewport (14-team league,
`/drafts/1389361939561332737/board`):

- `.board-scroll` client width **1345px**, scroll width **1444px** — the board
  already overflows by 99px with no rail at all.
- One column is **96px**.

A 200px rail makes that overflow 299px — two more columns pushed off-screen on a
1440px laptop, on the app's single most important screen. `.app`'s own comment
(`styles.css:180-182`) records that a 1400px cap was already found to be the cause
of "squished" at wide monitors; re-introducing a 200px permanent inset is the same
mistake wearing a different hat.

So the rail is not one component, it is **two states**:

- **Full rail, 200px** — Home, Managers, Manager history, League history, Power
  rankings, Mock setup. Content-shaped pages with room to spare.
- **Icon rail, 56px** — the four draft-room routes (`DraftView`,
  `LiveDraftView`, `MockDraftView`, `CompletedDraftBoard`). Wordmark mark only,
  icon rows with `title`/`aria-label`, avatar at the bottom. Costs the board 56px
  instead of 200px, and buys the draft rooms the one thing they have never had: a
  visible way back to the league without the browser's Back button.
- **Horizontal bar, < 860px** — already built and shipping
  (`styles.css:1685-1698`); it just needs to apply on every route rather than only
  where `.home-sidebar` exists.

The collapse is route-driven by default, plus a user toggle persisted to
`localStorage` alongside the existing `bk-sport-filter` key
(`DraftPicker.tsx:43`) — so someone on a 27" monitor can pin the full rail open in
the draft room, and someone on a 13" can collapse it everywhere.

---

## C. Work plan

### Phase 1 — Lift the shell out of Home (no visual change to Home) — DONE

This is a refactor phase. Home should look pixel-identical when it lands; that is
how you know it worked.

1. **`components/AppShell.tsx` (new).** Renders `.app-shell` containing `Rail` plus
   `<main className="app-main content">{children}</main>`. `App.tsx` wraps
   `<Routes>` in it and **deletes the whole `.top` header** and the `isHome`
   branch.
2. **`components/Sidebar.tsx` becomes `components/Rail.tsx`.** Today it takes four
   home-only props (`sportFilter`, `onSportFilterChange`, `counts`,
   `onOpenMockModal`) and hardcodes `Home` as `.on` (`Sidebar.tsx:73`). Split it:
   - *Chrome* — wordmark, Menu rows, account, Sign out — always rendered, and the
     `.on` row derives from `useLocation()` instead of being hardcoded.
   - *Context slot* — a `children` region between wordmark and Menu that the
     current page fills. Home fills it with today's Sports filter, unchanged.
     League pages fill it per Phase 3. Pages with nothing to say render nothing and
     the rail just gets shorter.
3. **The `top-slot` portal.** `topSlot.tsx` targets a div inside `.top`;
   `DraftView.tsx:449-460` is the only consumer (the settings gear). With `.top`
   gone the node must move. Put it in the **page header's action area**, not the
   rail — the gear is draft-room scoped, and the rail is global. Update
   `topSlot.tsx`'s doc comment, which currently says "inside App's persistent
   header (`.top`)".
4. **CSS rename sweep** in `styles.css`: `.home-shell` to `.app-shell`,
   `.home-sidebar*` to `.app-rail*`, `.home-main` to `.app-main`. The
   `.app:has(> .home-shell) { padding: 0 }` special case becomes the plain `.app`
   rule and is deleted. Retire the `.top*` block (`styles.css:192-208`, plus
   `.top-nav` at 1516) once nothing references it.
5. **Tests.** `App.test.tsx:110` ("hides the Home/Managers nav chips when signed
   out…") and `:129` ("does not render the app-wide header chrome on the
   redesigned home screen") both encode the two-shell split and will fail by
   design. Rewrite them as: signed out means no rail at all (SignIn is still the
   whole screen); signed in means exactly one rail, on every route.

### Phase 2 — Rail states — DONE

6. Add `.app-rail.collapsed` (56px, icons only) and a `useRailMode()` hook that
   picks the default from the route and honours the persisted override.
7. Icons for the Menu rows. The app ships no icon set — follow the approach the
   sport dots already use (`.home-sport-dot`: a colored token, not an icon font),
   plus a minimal inline SVG per row. Do **not** add an icon dependency for four
   glyphs.
8. Extend the `860px` horizontal-bar media query to `.app-rail` generally.

### How Phases 1-2 actually landed

Four things differ from the plan above, each for a reason worth keeping:

- **`topSlot.tsx` became `appSlots.tsx` with two contexts, not one retarget.**
  The rail needs a page-context region (step 2) and that is the same mechanism
  as the gear's portal, so they are one file: `PageActionSlotContext` (the
  `.app-main-head` row) and `RailContextSlotContext` (the rail's region, which
  also carries `collapsed`, because portaled content can't read a CSS class on
  its target's ancestor).
- **`Sidebar` split into two components, not one.** `Rail` is the global
  chrome; `SportFilterRail` is Home's filter, which portals in. The counts come
  off `drafts`, which only DraftPicker fetches — the alternative was hoisting
  that fetch into the shell so the rail could own a control that filters
  nothing on nine of ten routes.
- **`.content`'s gap went 12px → 16px, and `.content.scrolls` no longer
  scrolls.** One shell means one rhythm, and `.app-main` is the scrolling pane
  now; leaving the nested scroller in place put a second scrollbar inside the
  first and stranded the pane's bottom padding.
- **The mobile bar's wordmark is one line.** Two spans with a CSS-controlled
  break rather than a `<br>`, which CSS can't un-break. Measured 149px → 125px
  of permanent chrome at 375×812 — on an 812px screen that is worth the markup.

Verified live against the real backend and Postgres at 1024px and 375×812:
all nine routes, `document.body` horizontal overflow 0 on every one of them
(the flex-chain risk below), the gear portal landing in `.app-main-head`, the
rail's cross-page "Mock drafts" row, the collapse toggle and its persistence,
and the route defaults (`/mock/new` expanded, `/mock/4` collapsed). The board
measures 865px visible with the icon rail against 736px with the full one at
1024px — the 129px the §B argument was about. 191 frontend tests pass.

### Phase 3 — League context in the rail — DONE

This is where the redesign starts paying for itself on pages other than Home.

The home league card already offers exactly the right menu — *Draft board ·
History · Power rankings · Mock it* (`DraftPicker.tsx`). Once you are inside one of
those pages, that menu vanishes. Fill the rail's context slot on every
league-scoped route (`/leagues/:id/*`, `/drafts/:id/*`) with:

```
[crest]  West Coast Fantasy Football
         NFL · 14 managers

   Draft board
   History
   Power rankings
   Mock it
```

Crest, sport badge and the four actions are all existing markup (`.league-crest`,
`.league-card-mockit`, `.league-link`). The current row is `.on` the same way the
sport rows are. This replaces the ad-hoc one-off back links
(`PowerRankings.tsx:751`, `LeagueHistory.tsx:98`) — delete those once the rail
carries them, rather than having the same navigation in two places.

`/managers/:id/history` gets the analogous manager context block.

### Phase 4 — The page header pattern — DONE

Home and Power rankings independently invented the same three-part header:

| | eyebrow | title | sub |
|---|---|---|---|
| Home | `.home-eyebrow` "Viewing" | `.home-title.cond` | `.home-header-sub` |
| Power rankings | `.pr-eyebrow` "Week 1 · League · 14 teams" | `.pr-headline.cond` | `.pr-deck` |

9. Extract `components/PageHeader.tsx` giving `.page-eyebrow` / `.page-title.cond` /
   `.page-sub` plus an actions slot (which is where `top-slot` lands, per step 3).
   Redefine `.home-*` and `.pr-*` as aliases of it, or sweep the call sites — but
   there should be **one** definition of this pattern.
10. Give the headerless pages a real one, and move the explanatory paragraph out of
    the panel and into `.page-sub` while you are there:

| Route | eyebrow | title | sub (moves out of the panel) |
|---|---|---|---|
| `/managers` | Across your leagues | **Manager tendencies** | `ManagerTendencies.tsx:281` |
| `/managers/:id/history` | Manager | *manager name* | — |
| `/leagues/:id/history` | League | *league name* | `LeagueHistory.tsx:104` |
| `/mock/new` | New mock | **Mock draft setup** | `MockSetup.tsx:133` |
| `/drafts/:id/board` | *league · season* | **Draft board** | keeps "What actually happened" |

### Phase 5 — Content shape, page by page — DONE

The rail alone will not make `/managers` look like it belongs to the new home. It
is currently a four-across grid of identically-shaped cards — precisely the failure
mode the house style at the top of `styles.css` warns about ("Padding a one-line
record out into a card is how this stops looking hand-built"). The home league
cards earned their card shape by carrying a crest, season chips, a tinted action
and a live-status line; a manager tendency card carries a name, a badge, one bar
and two position pills.

Recommended: **manager rows, not cards** — the same row treatment the mock-drafts
list now uses, with the reach bar as the row's inline meter (`.mock-row-progress`
is already the right component). That also fixes the current squeeze where "8.3
picks early" and two position pills fight for a 190px column.

Second: `/managers` shows an NFL/NBA badge per card but has **no sport filter**,
while the rail on Home has one that is doing nothing on this route. Wire the rail's
existing Sports section to filter this page too — same component, same persisted
key, one more page it applies to.

### How Phases 3-5 actually landed

- **League context is resolved from the path, manager context is portaled by
  the page.** Six routes share two URL shapes, so the rail matches the path
  itself (`railLeague.ts`, hand-written because the rail sits outside
  `<Routes>` and `useParams` returns nothing there). A manager's name is not
  in the URL and `ManagerHistory` has already fetched it, so that one portals
  in. The rule that fell out: the rail resolves what the path can tell it; a
  page hands over only what the page alone knows.
- **Board-first routes deliberately got no page header.** Phase 4's table
  listed one for `/drafts/:id/board`. An eyebrow, a 28px title and a sub cost
  the board ~70px of height — the same trade §B refused when it gave draft
  rooms a 56px rail instead of a 200px one. The panel head already names the
  page and the rail already names the league. The code says so where the
  header would have gone.
- **Power rankings kept its scale.** `.page-title.hero` (38px) and
  `.page-eyebrow.accent` are variants of the one vocabulary rather than a
  second one: a story headline that changes with the week is a different thing
  from a page title, and flattening it to 28px would erase a real distinction.
- **The phone scroll model changed.** League context took the mobile bar to
  252px of 812 — a third of the viewport, permanently. Below 860px the chrome
  now scrolls away with the page, with `.board-scroll` given a 60vh floor
  because it was the one thing relying on the pane's fixed height for its own.
  The rail also never collapses below 860px: collapsed picks *content* (a "BK"
  mark), not just layout, so a phone in a draft room was getting the
  abbreviation in a bar with room for the whole name.
- **Two bugs surfaced while testing the seeded "Mock it".** `StartMockModal`
  derived its selection in a `useState` initializer, so opening it before
  `/api/drafts` resolved settled on null and never recovered — which is
  exactly what the rail's "Mock it" does, since it navigates and opens in the
  same tick. The selection is derived per render now. And the seeded league
  scrolls into view: the list scrolls at 220px, so a league below the fold
  enabled "Start NFL mock" for something you could not see.
- **`/managers` rows, and why it mattered more than taste.** The four-across
  card grid was breaking the reach axis: the page's own code comment says its
  question is comparative ("who is the biggest reacher in my league"), and
  bars sitting in three or four different columns share no baseline, so the
  one comparison the axis exists for could not be made with it. One column,
  one scale. The no-reach explanation is clamped to two lines with the full
  text on hover — every basketball manager gets a near-identical one, and
  thirteen four-line paragraphs said the same thing thirteen times.
- **The sport filter is shared, not duplicated.** `sportFilter.ts` holds the
  hook and the one `bk-sport-filter` key, so the choice follows you between
  Home and `/managers` instead of each page remembering its own.

---

## D. Sequencing and risk

Phases 1 and 2 must ship together: Phase 1 alone puts a 200px rail on the draft
board and costs it two columns (see B). Phases 3, 4 and 5 are independent of each
other and can land one at a time.

Risks worth naming before starting:

- **`.app` is a 100vh flex chain.** The house style (`styles.css:46-52`) is
  explicit that every level needs `min-height: 0` / `min-width: 0` or the inner
  `overflow: auto` never engages. Adding a shell level between `.app` and
  `.content` is exactly the kind of change that breaks it. Check each draft room
  scrolls internally after Phase 1 — that is the screen where it will show.
- **Four `Keyed*` route wrappers** (`App.tsx:26-51`) exist to force remounts and
  prevent cross-draft resim leaks. The shell must wrap *outside* `<Routes>`, so the
  rail persists and the keyed remount behaviour is untouched. If the rail ends up
  inside a route element, that machinery silently stops working.
- **Two `.top`-era tests** will fail by design (step 5). That is the signal the
  scoping fence came down, not a regression.
- **Verify live, not just in tests.** Walk all nine routes at 1440px and at 375px
  against the real backend before calling any phase done.

## E. Explicitly not in scope

- No new color palette or font. The redesign deliberately kept the app's own tokens
  over the handoff's Barlow palette (`0c6f0ab`); this plan inherits that.
- No change to the draft board grid itself, the power-rankings ballot flow, or any
  backend surface. This is chrome and page headers.
- NBA mock drafts stay gated ("Soon") — a real backend gap, not a UI one.
