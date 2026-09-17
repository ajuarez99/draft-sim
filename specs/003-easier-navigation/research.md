# Research: Navigation that doesn't strand you

**Feature**: `003-easier-navigation` | **Date**: 2026-09-17

Everything below was measured against the running production site and the checked-out tree, not
inferred. Where a decision rests on a measurement, the measurement is quoted.

---

## R1. Why three routes lose league context

**Question**: The rail shows league context on some league pages and not others. What decides it?

**Finding**: One regex. `web/src/railLeague.ts:27`:

```ts
const league = pathname.match(/^\/leagues\/([^/]+)\/(?:history|power)(?:\/verify)?\/?$/)
```

`analysis` is not in the alternation. `/mock/:id` and `/managers/:id/history` are not matched by
either branch of `leagueRefFromPath`. Verified live on 2026-09-17 by reading `nav.app-rail` on each
route while signed in as `popsharky`:

| Route | `.app-rail-league` present |
|---|---|
| `/leagues/1346366555759341568/history` | `true` |
| `/leagues/1346366555759341568/analysis` | `false` |
| `/drafts/1346366555776126976/board` | `true` |
| `/mock/4` | `false` |

The comment directly above the regex says "Six routes share these two shapes" — the code handles
four of them. The Analysis route was added to `App.tsx` and to `LeagueRailSection`'s link list, and
the matcher it also needed was missed. That is the whole defect.

**Decision**: Replace the hand-written alternation with a single declared table of league-scoped
routes, and derive both the matcher and the rail's link list from it.

**Rationale**: The failure mode here is not "someone wrote a bad regex" — it is that the set of
league-scoped routes is written down in three places (`App.tsx`'s `<Route>` list, the regex, and
`LeagueRailSection`'s hrefs) and nothing forces them to agree. FR-001 asks for one declaration
because that is the only version of this fix that cannot silently rot the next time a page is added.
This matches a pattern already burned into this project's memory: *two implementations of one rule*
is the multi-sport landmine class, and an optional parameter that encodes a rule has shipped the
same bug three times. A route table is the same lesson applied to navigation.

**Alternatives considered**:

- *Add `analysis` to the regex.* Two characters, fixes today's bug, and leaves the next route to
  fail the same way. Rejected: it is the third copy of the list, not a fix to having three copies.
- *Use `useParams` in the rail.* Impossible as the shell is built — `AppShell` mounts **outside**
  `<Routes>` deliberately, so React Router's `Keyed*` remounts can't take the rail with them
  (`AppShell.tsx:72` documents this as load-bearing). `useParams` there returns `{}`. The
  hand-written matcher exists for this reason and must stay a matcher.
- *Move the shell inside a route element.* Rejected by the same comment: it would kill rail
  persistence and change what the `Keyed*` remount keys actually key, which exists so an in-flight
  resim can't land on the next draft's board.

---

## R2. A mock draft cannot currently link back to its league

**Question**: US1 scenario 3 wants `/mock/:id` to show its seeding league. Does the client have
enough to do it?

**Finding**: No — and this is a backend gap, not a frontend one.

- `createMockSession` **sends** `sourceSleeperLeagueId` (`web/src/api.ts:595`).
- `MockDraftService` **receives** it, resolves the league, checks membership and sport, then keeps
  only `league.name()` (`MockDraftService.java:163`) and passes that to `createSession`.
- The column is `source_league_name text` (`V12__mock_source_league.sql:8`). The id is never stored.
- `MockSessionState` therefore exposes `sourceLeagueName: string | null` — a display label, and
  `api.ts:543` says so explicitly.

So the id exists in memory at creation time, three lines above where it is discarded.

**Decision**: Add `source_sleeper_league_id text` to `mock_draft_session` (migration `V16`), persist
the value the service already holds, and surface it on `MockSessionState`. Treat it as nullable
forever.

**Rationale**: It is a one-column migration and one extra constructor argument on a path that
already validates the league. Deriving the league from `sourceLeagueName` instead would mean
matching leagues by display name — names are neither unique across users nor stable, and the
membership check the id path performs would be lost.

**Consequences to plan for**:

- **Existing mocks cannot be backfilled.** Rows written before V16 have only a name. The two mocks
  on the account today (`/mock/3`, `/mock/4`) will show no league context, and that is correct —
  inventing one by name match is the wrong answer. The rail shows league context for mocks created
  after this ships; older ones keep the current behaviour. FR-003's "and no league context when it
  was not [seeded]" covers this honestly.
- **Split deploys.** Frontend and backend are separate Railway services that deploy independently,
  and `api.ts:579` records a real white-page outage on 2026-09-14 from exactly this. The new field
  must be optional on the client and absent-tolerant: no league context, never a crash.

**Alternatives considered**:

- *Match on `sourceLeagueName`.* Rejected above.
- *Leave mocks out of scope.* Tempting — it is the smallest of the three broken routes. Rejected
  because a mock seeded from a league is precisely the case where the user is comparing the mock to
  the real room, and that is a round trip through Home today.

---

## R3. What the search overlay should be built on

**Question**: Hand-rolled, or a library (`cmdk`, `kbar`)?

**Finding**: No palette or generic search component exists in `web/src` today (`grep` for
`CommandPalette|cmdk|useSearch|SearchBox` matches only `DraftView.tsx` and `LiveDraftView.tsx`,
which have their own *player* search — a different index, staying where it is per Out of Scope).

**Decision**: Hand-roll it. No new dependency.

**Rationale**: The index is small and entirely local. It is built from data the app already has in
memory: `getDrafts()` is cached at module scope in `railLeague.ts` and carries `leagueName`,
`sport`, `season`, `teams`, `sleeperLeagueId` and `sleeperDraftId` for every league and season; the
manager list comes from `getManagers(sport)`. For five leagues across a handful of seasons plus 53
managers, that is a few hundred rows — a `filter` over a flat array, not a search engine. A palette
library brings a bundle, a styling system to override and its own focus model, to solve matching we
do not have.

The parts worth getting right are keyboard and focus behaviour (NFR-003), and those are a
documented, testable handful: open on shortcut, arrow through results, Enter to navigate, Escape to
close and restore focus, and a focus trap while open.

**Alternatives considered**:

- *`cmdk`.* The obvious pick if the index were large or remote. Rejected on size-of-problem.
- *Reuse the draft room's player search.* Different index, different lifetime, different result
  shape. Rejected — sharing it would couple navigation to the draft engine's data.

**Shortcut choice**: `Ctrl/Cmd+K`, plus a visible control in the rail so it is discoverable without
knowing the shortcut (FR-004 requires both). `/` is rejected: draft rooms have text inputs and a
bare `/` would fight them.

---

## R4. Where the search index comes from

**Decision**: Build it client-side from two existing endpoints. No new API.

| Source | Already called? | Gives |
|---|---|---|
| `getDrafts()` (`/api/drafts`) | Yes — cached at module scope in `railLeague.ts` | every league, every season, names, sports, both ids |
| `getManagers(sport)` (`/api/managers?sport=`) | Yes — by `/managers` | manager ids and display names per sport |

**Rationale**: NFR-001 says opening the palette must not hit the network on a warm cache, and the
league half is already warm for anyone who has loaded Home or any league page. The
`leagueLineages()` helper already groups seasons into lineages, which is exactly the grouping the
palette needs for "this league, that year".

**Open consequence**: `getManagers` is **per sport** — `api.ts:426` records that a single-sport call
listed only football managers, and that ten of the twelve Ball Knowers managers are the same Sleeper
user in both sports. The palette must either query both sports and merge on manager identity, or
scope manager results to the sport filter in play. Decided: **query both, merge, and label with the
sport pill**, because FR-004 says "managers" without qualification and a user searching a name does
not think in sports. This is the one place the palette does more than filter an existing array, and
it is why the index build is its own testable module rather than inline in the component.

---

## R5. Destination availability differs by sport

**Finding**: Analysis is football-only, and deliberately. `LeagueRailSection.tsx` gates it on
`d.sport === 'nfl'` with the reason recorded inline: two of that page's three blocks are
rest-of-season projections, and the only projection source wired up (Sleeper's `pts_ppr`) has no
basketball equivalent, so "a basketball league reaching it would get one working block and two
explaining themselves."

**Decision**: Model availability as a property of the destination table (R1), consumed by the rail,
the palette (FR-006) and the league switcher's fallback (FR-008) alike.

**Rationale**: This is the same trap as R1, one level up. The sport gate is currently expressed once,
in JSX, inside the rail. The palette and the switcher both need the same rule; writing it twice more
is how a basketball league ends up offered a football page. Making availability a field on the
declared destination means the rule is stated once and every consumer reads it.

---

## R6. The phone bar's cost is already known — and already decided

**Question**: Should navigation be pinned on mobile?

**Finding**: This was measured and settled before this feature. `styles.css` (the `max-width: 860px`
block) records it:

> On a phone the chrome scrolls away with the page instead of pinning a band above a shorter pane.
> [...] once league context landed that measured 252px of 812 on an iPhone-sized screen. A third of
> the viewport spent permanently on navigation is worse than navigation you scroll back up to reach.

Re-measured 2026-09-17 at 375×812 on `/leagues/:id/history`: the bar still renders as a wrapped
block of five rows above the page content, consistent with that note.

**Decision**: Do not pin the bar. Do not re-litigate the trade. Add a single small affordance that
solves only the *return trip* — the palette's entry point, fixed bottom-corner, at phone width only.

**Rationale**: The existing reasoning is sound and the measurement behind it is real; a plan that
quietly reverses it would re-spend a third of the viewport that someone already decided not to
spend. But "scroll back up to reach" is a real cost at the bottom of a 12-row standings table, and
the palette (P2) happens to be the cheapest possible answer to it — one button, no band, and it
opens something that reaches *everywhere*, not just the five rail rows. P4 therefore becomes a thin
layer over P2 rather than a navigation redesign, which is also why it is ranked last: it is nearly
free once P2 exists, and pointless before it.

**Alternatives considered**:

- *Sticky compact bar.* Costs a permanent band, which is the thing the measurement rejected.
- *Bottom tab bar.* Same objection, plus it implies a fixed top-level information architecture the
  app does not have (league context is contextual, not global).

---

## R7. Two smaller reachability gaps, confirmed

Both are one-line-ish and fold into the stories above rather than earning their own.

- **`/managers` links to no manager.** `grep` for `/managers/:id/history` hrefs finds them only in
  `LeagueHistory.tsx` (×2) and `LeagueAnalysis.tsx`. `ManagerTendencies.tsx` — the page *called*
  Managers, listing 53 profiles — links to none. Confirmed live: zero `a[href*="/managers/"]` on
  `/managers`. Folds into FR-007.
- **Seasons hide when the rail collapses.** `LeagueRailSection.tsx` gates the season links on
  `!collapsed && lineage.seasons.length > 1`, and `railDefaultCollapsed()` collapses draft rooms by
  default. Verified live on `/drafts/1346366555776126976/board`: `seasonsVisible: false` while the
  league has 2026 and 2025. The rail is collapsed in rooms for a measured reason (a 14-team board
  already overflows 1440px by 99px, and a column is 96px — NFR-004 keeps that intact), so the fix is
  to make seasons reachable *without* widening the rail, not to un-collapse it. Folds into FR-009.

---

## Resolved unknowns

| Unknown from Technical Context | Resolution |
|---|---|
| How to declare league-scoped routes once | R1 — a destination table, consumed by matcher, rail, palette, switcher |
| Whether a mock can link to its league | R2 — not today; needs `V16` + one service argument + one response field |
| Palette: library or hand-rolled | R3 — hand-rolled, no new dependency |
| Palette data source | R4 — existing `/api/drafts` and `/api/managers`, both sports merged |
| How to express sport-specific destinations | R5 — availability is a field on the destination table |
| Whether to pin mobile navigation | R6 — no; add only a return affordance, per an existing measurement |

No `NEEDS CLARIFICATION` items remain.
