# Research: Player spotlight on the home page (spec 015)

Read against `origin/main` at 29073a1 on 2026-10-02. No backend was running on this machine (nothing on
8080), so **nothing in this file is measured at runtime**. Each place a number would matter says so.

## R1 — Backend: reuse the existing endpoint unchanged

`GET /api/leagues/{id}/player-spotlight` (`PlayerSpotlightController.playerSpotlight`) already does
the whole job for one league. It is scoped by `LeagueMembership.visibleLeague` (404 for a league the
viewer is not in), reads only stored data (014 FR-016), and reports each section's reason itself.

**Decision**: no new endpoint, no change to this one, no change to `web/src/api.ts`. The home page
calls `getPlayerSpotlight(id)` for the open tab's league, which is exactly what `LeagueHome` calls.
That is what makes FR-004 (one source) hold by construction rather than by a parity test.

**Alternatives considered**:
- *A batch endpoint (`/api/me/player-spotlights`) returning every league at once*: one round trip,
  but it computes N spotlights when the viewer looks at one. It is also a second code path that must
  stay equal to the first. Rejected by FR-007 ("only the open tab is fetched up front") and FR-004.
- *Folding football's top-of-week into the spotlight payload* (see R2): a 014 contract change for no
  reader-visible gain. Rejected.

## R2 — Football's Top players come from the Weekly Report, not the spotlight

`PlayerSpotlight.tsx` renders football's top list from a **second** block, `weekly`
(`getWeeklyReport(id, 0)`), per 014 research R9: the list *is* the Weekly Report's `topPerformers`,
starters only. The spotlight payload has no `topOfNight` for football, and no top-of-week either.

**Decision**: a home tab fetches the Weekly Report for its league **only when the spotlight says it
needs it**: `applies` is true, `topOfNight` is absent, and `period` is non-null. It fetches after the
spotlight resolves, not in parallel.
- It follows the sport's rules from the payload, never from a sport name (spec FR-014 / 014 FR-013;
  `PlayerSpotlight.tsx` is pinned by a source-scan test). `DraftSummary.sport` is known on the home
  page, and branching on it would be the quick path. It is the exact thing that rule forbids.
- It costs NBA tabs nothing: no Weekly Report call for a sport that never reads it.
- **Cost, assumed and not measured**: NFL tabs pay one extra round trip before the Top column
  fills. The other two columns render as soon as the spotlight arrives, and the Top column already
  has a loading state for exactly this (`TopOfWeek`: "Loading top players"). The latency is measured
  in verification (quickstart V5).

**Alternatives considered**: fetching both in parallel for every tab (wasted call per NBA tab, and
it needs either a sport check or an unconditional call); a server-side merge (R1, rejected).

## R3 — The component: split the lists from the section chrome

`PlayerSpotlight` renders its own `<section>`, `<h2>Player spotlight</h2>` and fixed element ids
(`lh-spotlight-h`, `lh-spot-tab-*`, `lh-spot-panel-*`). The home page needs a league tab strip
between the heading and the lists, and a heading that names the league.

**Decision**: split `PlayerSpotlight.tsx` into two exports:
- `SpotlightLists`: the phone segmented control plus the three columns. It takes the spotlight, the
  weekly block, and an `idPrefix` so element ids stay unique. Each visited league tab stays mounted
  (R5), so several copies can be in the DOM at once.
- `PlayerSpotlight` (default export, unchanged signature): `LeagueHome`'s wrapper, the section and
  heading around `SpotlightLists` with prefix `lh`. `LeagueHome`'s markup and its tests do not change.

The home page builds its own section around `SpotlightLists`. Its CSS reuses the `lh-spot-*` rules
as they are, so the two pages cannot drift visually.

**Alternatives considered**: a `heading` render-prop on `PlayerSpotlight` (keeps one export, but
the section's ids still collide across mounted tabs); copying the component (two implementations of
one rule, the class `feedback_optional_params_that_encode_rules` and the multi-sport landmines memory
warn about). Rejected.

## R4 — `applies: false`, and every other "nothing to show"

`PlayerSpotlight` returns `null` when `applies` is false. The only such reason today is `PAST_SEASON`
(`PlayerSpotlightService`, line 183). On the league home, rendering nothing is right: the rest of
the page explains the season. On the home page, a league tab that opens to nothing breaks spec FR-010.

The lineage's `current` draft is its newest **ingested** season. That can be a past season: a league
whose 2026 season was never set up here still shows its 2025 card in "Your leagues".

**Decision**: the home tab handles `applies: false` itself, with one sentence: "No player spotlight
for {season}: it covers the current season only." Every other empty state (pre-season, no completed
night, no rookie played, trending unavailable) is already worded inside the lists, per section, and
is reused verbatim (FR-010).

**Alternative considered**: dropping past-season leagues from the tab strip. That means the strip
disagrees with "Your leagues", and FR-002 says it follows that list. Rejected.

## R5 — Tabs: which leagues, which opens, and no refetch on return

- **Source**: `leagueLineages(drafts)` filtered by the rail's sport filter, the exact
  `visibleLineages` that "Your leagues" renders (`DraftPicker.tsx:230`). One tab per lineage, keyed
  by `current.sleeperLeagueId`, labelled `current.leagueName` plus a sport pill (the same pill "Your
  leagues" rows use).
- **Opens on**: the first visible lineage. If the sport filter removes the selected league, selection
  falls back to the first visible one.
- **No refetch on return (FR-008)**: each league tab, once visited, stays mounted and is hidden with
  `hidden` when not selected. Its blocks keep their state, so returning shows the loaded data with no
  request and no skeleton. Unvisited tabs mount nothing, which is the "only the open tab" half of FR-007.
- **ARIA**: the league strip is a `role="tablist"` labelled "Leagues". The phone-only list control
  inside each panel is a second tablist labelled "Player spotlight lists", as on the league home.
  Arrow/Home/End keys follow the existing segmented control's handler.
- **The inner Top/Trending/Rookies choice** stays shared through the existing `lh-spot-tab`
  localStorage key, so phones open both pages on the same list.

## R6 — Placement and the first-paint guarantee

- **Where**: after "From Sleeper" (both its error and its list forms), before "Mock drafts"
  (`DraftPicker.tsx` around line 500). It renders only when `drafts` has loaded and `visibleLineages`
  is non-empty (spec edge case: no ingested leagues, no section).
- **FR-007 / SC-005**: the section's fetch starts only after `drafts` resolves (it needs a league id),
  and it is a separate block. "Your leagues" and "Mock drafts" render from their own existing fetches
  and never wait for it. **Not measured**: whether the extra request delays anything on a slow
  connection. The browser's per-host connection limit is far above the page's request count; this is
  checked in V6, not assumed.

## R7 — Refresh: the home page does not start one

The automatic refresh (spec 009) starts from `LeagueRailSection` when a league page is open, and
bumps `useLeagueDataVersion(id)` when it finishes. The root page opens no league, so no refresh runs
from it, and trending's hourly-on-visit refresh (014 R5) does not fire from the home page either.

**Decision**: don't add a refresh trigger to the home page. Starting a full league ingest on every
home-page load, for every league, is exactly the herd 009's concurrency audit removed. The tab still
lists `useLeagueDataVersion(id)` among its blocks' dependencies, so a refresh finishing elsewhere in
the same session updates it.

**Accepted consequence, stated rather than hidden**: a viewer who only uses the home page sees data as
fresh as the daily run, or as their last league visit. Trending already shows its own age once it
exceeds the window (014 FR-011), so a stale list says so instead of passing as current.

## R8 — `useBlock` is private to `LeagueHome`

`useBlock` and its `Block<T>` type live in `pages/LeagueHome.tsx`. `useBlock` is not exported, and
`PlayerSpotlight.tsx` already imports the `Block` type from a page module.

**Decision**: move both to `web/src/useBlock.ts`, and import them from there in `LeagueHome`,
`PlayerSpotlight` and the new home section. This is a pure move with no behaviour change, done as its
own commit so the diff reads as one.

## R9 — What this plan does not touch

No migration, no Java, no `api.ts` type, no `weights.yml`. The AGENTS.md hard rules on append-only
migrations, `api.ts` mirroring, `Map.of` nulls and SQL execution are therefore not in play. If the
build finds that a backend change is needed after all, this file gets an "amended after build" note
rather than a quiet rewrite.

## Amended after review (2026-10-02, T023)

The bug-hunting review found one defect in the build, now fixed with a test:

- **A refresh caused a wasted weekly-report request.** `LeaguePanel`'s weekly block listed `version`
  in its dependencies. On the render where a league's data refresh finished (version bumped), the
  spotlight block was still in its old `ok` state, so `needsWeekly` was true and the new `version`
  started a weekly request. One render later the spotlight went to `loading`, `needsWeekly` went
  false, and that request was cancelled. Then the real one ran. That made 3 weekly calls for one
  load plus one refresh, instead of 2. **Fix**: drop `version` from the weekly block's
  dependencies, since the spotlight's own trip through `loading` already refetches it. **Test**:
  "a finished refresh refetches the weekly report once" in `HomeSpotlight.test.tsx`. It was run
  against the old dependency list and **failed** (3 calls), then passed with the fix (2 calls).
  The home page starts no refresh itself (R7), so this only fired after a refresh finished
  elsewhere in the same session.

Checked and **not** defects: `setVisited` during render (same-component state update, converges in
one extra render, and is never called again once the id is in the set); hidden panels don't refetch
on parent rerenders (`useBlock` keys on `[leagueId, version]` only); a visited id that left
`leagues` renders no panel (the filter runs over `leagues`, not `visited`); `LeagueHome`'s output is
unchanged (every pre-existing `PlayerSpotlight` and `LeagueHome` test passes unmodified); no sport
literal in `HomeSpotlight.tsx` (source scan: 5 run, 0 skipped).
