# Handoff: Multi-Sport League Home & Mock Drafts

> **BUILT — this is an archive, not a work order.** Shipped in two passes:
> `0c6f0ab` (2026-09-08) delivered the sidebar sport filter, the league/mock
> cards and the two-step modal; `1611ba1` (2026-09-14) delivered the half that
> depended on a backend that did not exist yet — NBA mocks are startable, mock
> rows carry a real sport badge, and the sport filter scopes the mock list
> instead of blanking it. Do not re-implement from this file.
>
> **Two deliberate deviations from the spec below**, both still in force:
> - **The palette was not adopted.** The app's own tokens won over this
>   document's Barlow/`#2ee6c5` values (`claude/site-wide-shell-propagation.md`
>   §E). Read the *structure* here, not the hex codes.
> - **Submitting the modal navigates to `/mock/new`, not straight into a
>   room.** That screen's per-seat real-manager assignment is valued
>   functionality the spec's one-step flow would have skipped.
>
> Both of the spec's must-not-change behaviours held: the sport is always
> explicit (enforced backend-side too — a league whose sport disagrees with the
> stated one is refused), and every mock row is identifiable at a glance.
> See `claude/nba-mock-drafts.md` for what the backend half took.

## Status: approved — please build this

This design is **signed off by the product owner**. It is the direction we want implemented, not one option among several. Build it as specified below.

If something here conflicts with the codebase's existing patterns or is technically impractical, **ask before deviating** — the two behaviors that must not change are: (1) the sport of a mock draft is always chosen explicitly, never defaulted to football, and (2) every mock draft row is uniquely identifiable at a glance (sport, source league, size, progress, recency).

## Overview
Ball Knowers is a fantasy draft app that supports multiple sports (currently NBA and NFL). Two problems in the shipped home screen:

1. **Mock drafts always default to football.** "Start a mock draft" produced an NFL mock regardless of which league or sport the user was working in.
2. **Mocks are indistinguishable.** The Mock Drafts list rendered identical rows ("12-team mock from 1.05 / through 1.04") with no sport, league, progress, or recency signal.

This redesign fixes both:
- A **persistent left sidebar** with a sport filter (All sports / NBA / NFL, each with a league count) that scopes the leagues grid, the mock list, and the default sport of any new mock.
- A **two-step "Start a mock draft" modal**: step 1 picks the sport, step 2 picks which league's settings to clone. The sport is never implicit.
- Each league card has its own **"Mock it"** action that opens the modal pre-set to that league's sport and settings.
- **Mock rows are fully identified**: sport badge (color-coded), team count, source league name, round count, user's pick slot, paused/finished state, a progress bar with picks-made/total and percentage, relative timestamp, and a Resume/View board CTA.

## About the Design Files
`Ball Knowers Home.dc.html` is a **design reference created in HTML** — a working prototype of the intended look and behavior, not production code to copy. It uses a small streaming-component runtime specific to the design tool; the logic class and templating syntax are *not* meant to be ported.

The task is to **recreate this design in the target codebase's existing environment** (React, Vue, SwiftUI, native, etc.) using its established component library, routing, state, and styling patterns. If no environment exists yet, choose the most appropriate framework and implement there. `before-current-home.png` shows the current production screen for comparison.

## Fidelity
**High-fidelity.** Final colors, typography, spacing, and interaction behavior are specified below and should be matched closely. Data shown in the prototype is mock data; wire to real APIs.

## Screens / Views

### 1. Home (single screen, two panes)

**Purpose:** The user sees their leagues grouped/filterable by sport, sees their in-progress and finished mock drafts at a glance, and starts a new mock with an explicit sport.

**Layout:** Root is `display:flex; align-items:stretch; min-height:100vh` on background `#06090d`.
- **Sidebar:** `width:196px; flex:0 0 196px`, `position:sticky; top:0; height:100vh`, `padding:18px 14px`, `background:#080c12`, `border-right:1px solid #141c26`, `box-sizing:border-box`, vertical flex with `gap:22px`.
- **Main:** `flex:1; min-width:0`, `padding:24px 26px 70px`, vertical flex with `gap:26px`.

#### Sidebar components

**Wordmark** — "Ball / Knowers" on two lines. Barlow Condensed 700, `23px`, `letter-spacing:.04em`, `text-transform:uppercase`, `line-height:1.05`, `padding:0 6px`.

**Section label** ("SPORTS", "MENU") — `10.5px`, weight 700, `letter-spacing:.16em`, uppercase, color `#4d5a69`, `padding:0 6px 6px`.

**Sport filter rows** — one per entry: `All sports` (count 5), `NBA` (2), `NFL` (3).
- Row: `display:flex; align-items:center; gap:9px; padding:8px 10px; border-radius:9px; font-size:14px; cursor:pointer`.
- Inactive: color `#7b8a9b`, weight 500, transparent background.
- Active: color `#eef4f9`, weight 600, background `#101821`.
- Leading dot: `7px` circle. Active = the sport's accent (`All sports` → `#2ee6c5`, NBA → `#f0803c`, NFL → `#7ec96b`); inactive = `#2b3a4b`.
- Trailing count: `11.5px`, tabular numerals, `#8b9aab` active / `#4d5a69` inactive.

**Menu rows** — Home (active: `#101821` bg, `#e7edf3`, weight 600), Managers, Mock drafts (both `#7b8a9b` weight 500; hover `color:#e7edf3; background:#0d141c`). Same padding/radius as sport rows. "Mock drafts" opens the new-mock modal.

**Account footer** — pushed down by a `flex:1` spacer. `border-top:1px solid #141c26`, `padding:8px 6px`, row with a `26px` circular avatar (`#6b3fa0`, white initial `12px/700`) and the username at `13.5px` `#c6d2de` (truncating). Below it, "Sign out" at `12.5px` `#5a6878`, hover `#e7edf3`.

#### Main pane

**Header row** — flex, `align-items:flex-end`, `gap:14px`, wraps.
- Eyebrow "VIEWING": `11px`, 700, `letter-spacing:.16em`, uppercase, `#5a6878`.
- Title: Barlow Condensed 700, `34px`, `line-height:1.1`. Text is `All sports` or `<SPORT> leagues`.
- Right: primary button "Start a mock draft" — `padding:10px 20px; border-radius:999px; background:#2ee6c5; color:#06231f; font-size:14px; font-weight:700`; hover `#5ff2d9`.

**Section header pattern** (used for "Your leagues" and "Mock drafts") — flex row, `gap:10px`: uppercase label `12px/700`, `letter-spacing:.16em`, `#8b9aab`; a count/context line `12.5px` `#4d5a69`; then a `flex:1` `1px` rule in `#121a23`. The Mock Drafts header additionally ends with a secondary "New mock" pill (`13px/600`, `#2ee6c5`, `padding:5px 12px`, `border-radius:999px`, `border:1px solid #17493f`, hover background `#0d2f2a`).

**Leagues grid** — `display:grid; grid-template-columns:repeat(auto-fill,minmax(290px,1fr)); gap:12px`.

**League card** — `background:#0a1017; border-radius:14px; padding:15px; border:1px solid #141c26`; a league with a live/unstarted draft uses `border-color:#17493f`.
- Top row: `34px` rounded-square avatar (`border-radius:10px`, per-league background color, initial `15px/700`), then a flexible column, then the season year in Barlow Condensed 700 `21px` `#2b3a4b`.
- Name: `16px/700` `#f1f6fa`, single line with ellipsis.
- Under the name: sport badge + `12.5px` `#7b8a9b` meta string `"<n> managers · <n> rounds"`.
- Action chips (`margin-top:14px`, gap 7, wrapping): first chip is primary — `background:#101821; border:1px solid #2b3a4b; color:#eef4f9`; the rest are `color:#8b9aab`, transparent border/background. All: `padding:5px 11px; border-radius:8px; font-size:12.5px; font-weight:600`.
- Footer (`margin-top:13px; padding-top:12px; border-top:1px solid #141c26`): a `6px` status dot (`#2ee6c5` live / `#2b3a4b` otherwise), status text `12.5px` (`#2ee6c5` weight 600 when live, `#5a6878` weight 500 otherwise), spacer, then the **"Mock it"** button — `12.5px/600`, `#2ee6c5`, `padding:4px 10px`, `border-radius:8px`, `border:1px solid #17493f`, hover background `#0d2f2a`, `white-space:nowrap`.

**Sleeper import callout** (shown for All sports and NFL only) — `max-width:520px`, `padding:13px 15px`, `border-radius:12px`, `border:1px dashed #1d2833`, `background:#080d13`. `30px` rounded-square avatar `#7a4a1e`; title `14.5px/700` `#c6d2de` with a `#5a6878` weight-500 suffix "— from Sleeper"; meta `12.5px` `#7b8a9b`; right-side "Set up" button `13px/600`, `padding:6px 13px`, `border-radius:8px`, `border:1px solid #2b3a4b`, hover border/text `#2ee6c5`.

**Mock draft row** — `display:flex; align-items:center; gap:14px; flex-wrap:wrap; padding:13px 16px; border-radius:12px; background:#0a1017; border:1px solid #141c26`; hover `border-color:#233040`.
1. **Sport badge** (large variant).
2. **Identity block** (`flex:1; min-width:180px`): title `15px/700` `#f1f6fa` = `"<teams>-team <SPORT> mock · <league name>"`; subtitle `12.5px` `#7b8a9b` = `"<rounds> rounds · your pick <slot> · <paused|finished> at <pick>"`.
3. **Progress** (`min-width:130px`): track `height:5px; border-radius:999px; background:#141c26; overflow:hidden`; fill width = `picksMade / (teams × rounds)` as a percentage, minimum 3% so it's always visible; fill color = sport accent for in-progress, `#2b3a4b` for finished. Caption below at `11.5px` `#7b8a9b`: `"<made>/<total> picks · <pct>%"`.
4. **Timestamp** — `12.5px` `#5a6878`, `min-width:96px`, right-aligned (relative: "2 hours ago", "Yesterday", "Sep 6").
5. **CTA** — in progress: "Resume", `background:#2ee6c5; color:#06231f`. Finished: "View board", `border:1px solid #2b3a4b; color:#c6d2de`. Both `padding:7px 15px; border-radius:999px; font-size:13px; font-weight:700; white-space:nowrap`.

**Empty state** (filtered sport has no mocks) — `padding:26px; border-radius:12px; border:1px dashed #1d2833`, centered `14px` `#5a6878`: "No <SPORT> mocks yet — start one to get reps before the real draft."

### 2. Start a Mock Draft (modal)

**Purpose:** Force an explicit sport choice, then clone a specific league's settings. This is the fix for "mock draft is always football."

**Overlay:** `position:fixed; inset:0; background:rgba(3,6,9,.78); backdrop-filter:blur(3px)`, centered, `padding:24px`, `z-index:60`. Clicking the overlay closes; clicks inside the dialog must stop propagation.

**Dialog:** `width:100%; max-width:520px; background:#0b1119; border:1px solid #1d2833; border-radius:16px; padding:22px`, vertical flex `gap:18px`.

- **Title block** — eyebrow "PRACTICE ROUND" (`11px/700`, `.16em`, uppercase, `#5a6878`); heading "Start a mock draft" in Barlow Condensed 700 `28px`, `line-height:1.15`.
- **Step label** — "1 · SPORT", "2 · USE SETTINGS FROM": `11.5px/700`, `letter-spacing:.14em`, uppercase, `#8b9aab`.
- **Sport chips** — one per sport, `padding:9px 20px; border-radius:10px; font-size:14px; font-weight:700; letter-spacing:.06em`. Inactive: `color:#7b8a9b; background:#0d141c; border:1px solid #1a2532`. Active: sport accent color on sport tint background with sport border (see tokens).
- **League radio list** — `max-height:220px; overflow:auto; gap:7px`. Only leagues in the selected sport appear; changing sport resets the selection to that sport's first league. Each option: `display:flex; align-items:center; gap:11px; padding:11px 13px; border-radius:11px`. Unselected `border:1px solid #1a2532; background:#0a0f16`; selected `border-color:#17493f; background:#0b1f1c`. Radio: `15px` circle, `border:2px solid` (`#2ee6c5` selected / `#2b3a4b`), selected fill `#2ee6c5` with `box-shadow: inset 0 0 0 2.5px #0b1f1c` to create the inner ring. Name `14.5px/600` `#eef4f9`; meta `12.5px` `#7b8a9b` = `"<teams> teams · <rounds> rounds · <season>"`.
- **Footer** — "Cancel" (`padding:10px 18px; border-radius:999px; border:1px solid #2b3a4b; color:#9fb0c0; 14px/600`, hover `#e7edf3`), spacer, primary CTA labeled **"Start <SPORT> mock"** (`padding:10px 22px; border-radius:999px; background:#2ee6c5; color:#06231f; 14px/700`, hover `#5ff2d9`). The CTA label reflecting the chosen sport is intentional — it's the last confirmation that this is not a football mock by accident.

## Interactions & Behavior

- **Sport filter (sidebar):** selecting a sport filters the leagues grid and the mock list, updates the header title, the "Your leagues" and "Mock drafts" count captions, and hides the Sleeper callout for non-NFL sports. It also becomes the default sport when the modal opens from the global button. No page navigation — client-side filter. Persist the selection (URL query param or local storage) so a refresh doesn't snap back.
- **"Start a mock draft" / "New mock" / sidebar "Mock drafts":** open the modal with `modalSport` = current filter sport, or the user's most-used sport when the filter is "All sports" (the prototype falls back to NBA).
- **League card "Mock it":** opens the modal with that league's sport *and* that league pre-selected in step 2. This is the fastest correct path and should be the primary way users start mocks.
- **Modal sport chip click:** sets the sport and resets the league selection to the first league of that sport (never leave a cross-sport league selected).
- **Modal submit:** create a mock draft using the selected league's teams/rounds/scoring/roster settings and the selected sport's player pool, then navigate to the mock draft room. On failure, keep the modal open and show an inline error under the CTA.
- **Modal dismiss:** overlay click, Cancel, and `Esc`. Trap focus inside the dialog; return focus to the trigger on close. (The prototype does not implement `Esc`/focus trap — add both.)
- **Mock row CTA:** "Resume" → mock draft room at the current pick; "View board" → completed board view.
- **Hover states:** listed inline above. Transitions of `120ms ease` on background/border/color are appropriate; the prototype has none.
- **Responsive:** the leagues grid auto-fills at `minmax(290px, 1fr)`; mock rows wrap. Below roughly `860px` the sidebar should collapse to a horizontal scrolling sport chip bar above the content, and the sidebar menu should move into the app's existing mobile nav.
- **Loading:** skeleton the league cards (3) and mock rows (4) with `#0a1017` blocks at the same dimensions. **Empty:** the dashed empty state above. **Error:** replace the affected section body with a dashed container carrying the message and a Retry pill styled like "New mock".

## State Management

| State | Type | Notes |
| --- | --- | --- |
| `sportFilter` | `"All" \| "NBA" \| "NFL"` | Drives leagues, mocks, header, Sleeper callout, modal default. Persist. |
| `modalOpen` | boolean | — |
| `modalSport` | sport | Seeded from `sportFilter` or the card that opened the modal. |
| `modalLeagueId` | string | Must always reference a league whose sport is `modalSport`; reset on sport change. |
| `leagues` | `League[]` | `{ id, name, sport, initial, avatarColor, managers, rounds, season, status, live, actions[] }` |
| `mocks` | `Mock[]` | `{ id, sport, leagueId, leagueName, teams, rounds, userPickSlot, throughPick, picksMade, updatedAt, done }` |

Data fetching: leagues and mocks load per user. **`picksMade` and `teams × rounds` must come from the API** — progress percentage is computed client-side from them. Creating a mock is a POST that returns the new mock's id for navigation; optimistically insert the row at the top of the list with 0 progress.

## Design Tokens

**Colors**
| Token | Hex | Use |
| --- | --- | --- |
| bg/base | `#06090d` | Page |
| bg/sidebar | `#080c12` | Sidebar |
| bg/surface | `#0a1017` | Cards, mock rows |
| bg/surface-alt | `#0b1119` | Modal |
| bg/raised | `#101821` | Active nav row, primary chip |
| bg/sunken | `#0d141c` | Inactive modal chip, nav hover |
| bg/dashed | `#080d13` | Sleeper callout |
| border/hairline | `#141c26` | Card + divider borders |
| border/rule | `#121a23` | Section rules |
| border/subtle | `#1a2532` | Chip borders |
| border/dashed | `#1d2833` | Dashed containers, modal border |
| border/strong | `#2b3a4b` | Secondary buttons, inactive dots |
| border/hover | `#233040` | Mock row hover |
| accent/teal | `#2ee6c5` | Primary action, live status |
| accent/teal-hover | `#5ff2d9` | Primary hover |
| accent/teal-ink | `#06231f` | Text on teal |
| accent/teal-tint | `#0d2f2a` | Ghost-teal hover fill |
| accent/teal-border | `#17493f` | Teal outlines, live card border |
| accent/teal-selected | `#0b1f1c` | Selected radio row |
| sport/NBA | `#f0803c` / bg `#2a1509` / border `#4a2a12` | NBA badge + dot + progress |
| sport/NFL | `#7ec96b` / bg `#12240e` / border `#25401d` | NFL badge + dot + progress |
| sport/MLB (reserved) | `#6aa8f5` / bg `#0d1a2c` / border `#1c3350` | Future sport |
| text/primary | `#f1f6fa` | Card titles |
| text/high | `#eef4f9` | Active nav, radio labels |
| text/body | `#e7edf3` | Base |
| text/soft | `#c6d2de` | Username, secondary CTA |
| text/muted | `#9fb0c0` | Cancel |
| text/meta | `#8b9aab` | Section labels, secondary chips |
| text/meta-dim | `#7b8a9b` | Card meta, inactive nav |
| text/faint | `#5a6878` | Timestamps, statuses |
| text/fainter | `#4d5a69` | Counts, micro labels |
| text/ghost | `#2b3a4b` | Season year |
| avatars | `#1c4f8a`, `#7a2f2f`, `#6b3fa0`, `#1f5e52`, `#8a6a1c`, `#7a4a1e` | Per-league; deterministic from league id |

**Spacing** — 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 26, 70 px. Sections `gap:26`, card grid `gap:12`, intra-card `gap:7–14`.

**Typography** — Barlow (400/500/600/700) for UI; Barlow Condensed (600/700) for the wordmark, page title, modal heading, and season year. Sizes: 10.5, 11, 11.5, 12, 12.5, 13, 13.5, 14, 14.5, 15, 16, 21, 23, 28, 34 px. Uppercase labels: `letter-spacing:.16em` (section), `.14em` (modal step), `.08em` (badges), `.06em` (modal sport chips), `.04em` (wordmark). Line-heights: 1.05 wordmark, 1.1 page title, 1.15 modal heading; default elsewhere.

**Radii** — 6 (small badge), 8 (chips, small buttons), 9 (nav rows), 10 (avatars, modal sport chips), 11 (radio rows), 12 (mock rows, callouts), 14 (league cards), 16 (modal), 999 (pills, dots, progress).

**Shadows** — none, except the selected radio's inner ring: `inset 0 0 0 2.5px #0b1f1c`. Depth is carried by background steps and hairline borders.

**Progress bar** — track `5px` / `#141c26`; fill `border-radius:999px`, `max(pct, 3)%`, sport accent when in progress, `#2b3a4b` when finished.

## Assets
No images, icons, or illustrations. All visual elements are CSS shapes and type. Fonts are Barlow and Barlow Condensed from Google Fonts — substitute the codebase's existing font pipeline if one exists. Avatars are colored blocks with a letter; if the product has real league avatars, use them and keep the color block as fallback. `before-current-home.png` is a screenshot of the existing screen for reference only — not an asset to ship.

## Files
- `screens/home.png` — home, All sports filter, top of page.
- `screens/mock-drafts-list.png` — Sleeper callout + the redesigned Mock Drafts list (all four row states).
- `screens/modal-start-mock.png` — the two-step Start a mock draft modal, NBA selected.
- `Ball Knowers Home.dc.html` — the full design prototype (sidebar, leagues grid, mock list, modal, all interactive states). Open in a browser to click through.
- `before-current-home.png` — the current production home screen this redesign replaces.
