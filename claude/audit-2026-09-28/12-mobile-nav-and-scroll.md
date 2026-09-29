# 12 — On a phone, most league pages are hidden, the icons are too small to tap, and the header never scrolls away

**Severity: Medium. Verified locally** at 375x812 on Power rankings. The root cause in
finding 3 is **inferred from the CSS and not yet run.** No dev server was up when this was
written.

## What's there now

1. **Seven of ten league pages are off-screen, and nothing hints at them.**
   `.app-rail-pages` (rendered at `LeagueRailSection.tsx:442`) becomes one swipeable lane at
   ≤860px (`styles.css:1973-1982`). It has `overflow-x: auto` and a hidden scrollbar
   (`:1979-1981`), with no fade and no arrow. Measured: `scrollWidth` 1249 in a 347px box.
   Analysis, Roster management, Expected wins, Season forecast, Weekly report, Superlatives
   and Mock it are all out of view. The comment at `:1960` says the links are "~1130px", so
   the lane has grown since that comment was written.
2. **The global menu shows bare glyphs that are too small to tap.** At ≤700px the menu hides
   its labels (`styles.css:2012-2013`) and so does Sign out (`:2016-2018`). Home, Managers,
   Mock drafts and Sign out show only ◆ ● ▶ ⏻ (`Rail.tsx:125-167`), at 24-27px wide against a
   ~44px minimum. The aria-labels are present. Sign out (`Rail.tsx:162`) signs out on one tap,
   with no confirmation, right next to the nav.
3. **The page doesn't scroll. An inner pane does.** Measured: document `scrollHeight` is 812,
   and `.content.scrolls` is a 597px scroller under a ~215px header. The header takes ~26% of
   the screen on every page. `styles.css:2028-2039` intends the opposite ("the chrome scrolls
   away with the page"). **Likely cause (inferred):** `.content.scrolls { overflow-x: hidden }`
   (`:2433`). CSS turns a `visible` `overflow-y` paired with a `hidden` `overflow-x` into
   `auto`. That makes `.content` a scroll container, and with `.content`'s `min-height: 0`
   (`:978`) and `.app-main`'s `flex: 1 1 0` (`:1823`) the chain shrinks to fit the viewport
   instead of growing it. Only pages with `.scrolls` are affected (PowerRankings.tsx:524/725,
   ErrorBoundary.tsx:44).

## Fix (recommended option first)

1. **Scroll, first:** change `:2433` to `overflow-x: clip`. `clip` doesn't create a scroll
   container. Confirm in DevTools that the computed `overflow-y` of `.content.scrolls` is
   `visible` and that the document grows. If it doesn't, find the real cause before
   proceeding. Don't stack another override on top.
2. **League pages: keep the lane, add a cue** (recommended over a sheet, because it keeps
   one-tap access and the existing tests):
   - Put a mask-image fade on whichever edge still has content, toggled by a small scroll
     listener using `data-overflow-start` / `data-overflow-end`.
   - On route change, call `scrollIntoView({inline: 'nearest'})` on the `.app-rail-row.on`
     item, the same way `JumpTo.tsx:104` does.
   - Add a trailing "All pages ▾" chip that opens the existing Jump to list. This is the
     fallback that guarantees ≤2 taps.
   - *Alternative:* collapse the lane into one "Pages ▾" sheet button. This saves the full
     36px row but makes every page two taps. Pick it if step 3 still leaves the header over
     ~120px.
3. **Global menu at ≥44px:**
   - Give each `.app-rail-row` `min-width` and `min-height` of 44px, and bring back short
     labels under the glyphs (10-11px type), styled as tinted pills rather than flat
     identical boxes.
   - Move Sign out into the account avatar's popover so it's no longer a one-tap control
     next to nav.
   - *Alternative:* a bottom tab bar (Home / Managers / Mock / League). It's a bigger design
     change, so only if Allan wants it.
4. **Compact sticky header (optional after step 1):** once the document scrolls, pin only
   the league identity plus the lane (~80px), and let the wordmark and menu scroll away.
5. Leave everything above 860px untouched. Every rule goes inside the existing
   `@media (max-width: 860px)` or `700px` blocks.

## Not in scope

- Pages that don't use `.scrolls` and rely on internal band scrolling (the draft boards).
- Renaming or reordering the league pages.

## Acceptance criteria

- [ ] At 375x812, every league page is reachable in ≤2 taps from Power rankings, and an edge
      fade or chip shows that the lane continues. Screenshot both the start and end of the
      lane.
- [ ] Every rail control measures ≥44x44 via `getBoundingClientRect`. Sign out is not a
      single tap from the nav row.
- [ ] Document `scrollHeight` > 812 on Power rankings, and after scrolling the header takes
      less than the 215px it takes now. Record the number.
- [ ] `document.documentElement.scrollWidth === 375`, so there's no sideways page scroll.
- [ ] Desktop (1440px) screenshots are pixel-equivalent before and after, both rail states.
- [ ] Existing rail tests (`LeagueSwitcher.test.tsx`, `testRailHelpers.tsx`) still pass.
      Hard-refresh (Ctrl+Shift+R) the tab after any dev-server restart before judging a fix.
