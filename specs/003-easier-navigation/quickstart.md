# Quickstart: verifying navigation that doesn't strand you

**Feature**: `003-easier-navigation` | **Date**: 2026-09-17

Each phase is independently verifiable. The checks below are the ones that would have caught the
defects this feature fixes — several of them fail today, on purpose, so you can confirm the
before-state first.

## Prerequisites

- Node 20+ and the `web/` dependencies installed
- Postgres running **only for Phase 2** (Docker Desktop)
- A signed-in session with at least two leagues, one NFL and one NBA, and one multi-season league

```bash
cd web && npm install
```

## Baseline: reproduce the defects first

Run this against the current `main` before any phase lands. Three of these six should report `false`.

```bash
npm --prefix web run dev
```

Then in the browser console on each route:

```js
!!document.querySelector('nav.app-rail .app-rail-league')
```

| Route | Expected before | Expected after Phase 1–2 |
|---|---|---|
| `/leagues/:id/history` | `true` | `true` |
| `/leagues/:id/power` | `true` | `true` |
| `/drafts/:id/board` | `true` | `true` |
| `/leagues/:id/analysis` | **`false`** | `true` |
| `/managers/:id/history` | **`false`** | `true` when reached from a standings row |
| `/mock/:id` | **`false`** | `true` for mocks created after V16 |

## Phase 1 — league context on every league route

```bash
npm --prefix web test -- destinations
```

**Expect**: the contract invariants from [contracts/destinations.md](./contracts/destinations.md) —
every `match` has an `href` and vice versa, `destinationFromPath` is total over `App.tsx`'s
league-scoped routes, no destination has an empty or defaulted `sports`.

**The test that matters**: add a fake league-scoped route to `App.tsx` without a row in
`destinations.ts` and confirm the suite **fails**. If it passes, the consolidation didn't take and
the next page added will strand users the same way Analysis does today.

Then in the browser: from `/leagues/:id/analysis`, confirm the rail shows Draft board, History,
Power rankings, Analysis and Mock it, with Analysis marked current.

## Phase 2 — mocks remember their league

```bash
cd backend && ./gradlew test
```

**Expect**: `BUILD SUCCESSFUL` — and then check the skip count.

```bash
cd backend && ./gradlew test --rerun-tasks
```

Then read the skip count out of the XML rather than the console, which does not print one:

```bash
cd backend && python3 -c "import glob,re; print(sum(int(re.search(r'skipped=\"(\d+)\"',open(f,encoding='utf-8').read()).group(1)) for f in glob.glob('build/test-results/test/*.xml')), 'skipped')"
```

The suite reports success with roughly 52 tests silently skipped when Postgres is down, so a green
build alone proves nothing about the integration tests. If the skip count is ~52, start Docker
Desktop and re-run.

**Round trip check**: create a mock from a league, then read it back.

```bash
curl -s localhost:8080/api/mocks/<id> | jq '{sourceLeagueName, sourceSleeperLeagueId}'
```

Expect both populated. Create one with no league and expect both `null`. Read an existing pre-V16
mock and expect `sourceLeagueName` set with `sourceSleeperLeagueId` `null` — **not** backfilled.

**Split-deploy check**: with the backend on the old build and the frontend on the new one, open
`/mock/:id`. Expect no League section and **no console error**. A white page here is the 2026-09-14
regression returning.

## Phase 3 — the jump-to palette

```bash
npm --prefix web test -- searchIndex JumpTo
```

**Expect**: index-building covers the both-sports manager merge (a manager in both NFL and NBA
appears **once**, carrying both sport pills), season distinctness, and that no NBA league emits an
Analysis destination.

In the browser:

1. From any route, press `Ctrl/Cmd+K` → overlay opens, focus in the input.
2. Type three characters of a league name → its pages appear, each labelled with the league.
3. Type a manager's name → their profile appears, including managers outside the current league.
4. Arrow to a result, press Enter → you land on it.
5. Press `Ctrl/Cmd+K`, then Escape → overlay closes, focus returns where it was, URL unchanged.

**No-fetch check** (NFR-001), with the Network tab open on a page that has already loaded:

```js
performance.getEntriesByType('resource').filter(r => r.name.includes('/api/')).length
```

Open the palette and re-run. The count must not increase.

**Shortcut collision check**: in a draft room with the player search focused, confirm typing `k`
does not open the overlay and `Ctrl/Cmd+K` still does.

## Phase 4 — switcher and collapsed seasons

1. From `/leagues/A/history`, switch to league B → land on `/leagues/B/history`.
2. From `/leagues/A/analysis` (NFL), switch to an NBA league → land on that league's **History**, not
   a page it doesn't have.
3. In a draft room (rail collapsed by default), confirm the league's other seasons are reachable.

```js
document.querySelector('nav.app-rail').className          // includes 'collapsed'
!!document.querySelector('.rail-switcher-toggle')   // true -- the flyout is the route to seasons
document.querySelector('.rail-league-seasons')      // null while collapsed, by design
```

4. On `/managers`, confirm every row links to a profile:

```js
document.querySelectorAll('a[href*="/managers/"]').length  // 53 today, 0 before this phase
```

**Board width check** (NFR-004): in a 14-team draft room at 1440px, confirm the switcher added no
horizontal space — compare `.board-scroll` `scrollWidth` before and after.

## Phase 5 — phone return affordance

```js
// at 375x812
window.scrollTo(0, document.body.scrollHeight)
```

Then confirm a navigation control is visible without scrolling up, and that when closed it occupies
no permanent band — page content still starts where it did before this phase.

## Full suite before opening a PR

```bash
npm --prefix web test
```

```bash
cd backend && ./gradlew test
```

Confirm the backend skip count is near zero, not ~52.


---

## Verification results (2026-09-17)

Run against a local dev server on a real backend and real league data, signed in as `popsharky`.
Recorded here because this project's bar for "verified" is driving the real thing, not a green suite.

**The six-route table, after:**

| Route | League section | Marked current |
|---|---|---|
| `/leagues/:id/history` | `true` | History |
| `/leagues/:id/power` | `true` | Power rankings |
| **`/leagues/:id/analysis`** | **`true`** (was `false`) | Analysis |
| `/drafts/:id/board` | `true` | Draft board |
| `/managers/:id/history` **via a standings link** | **`true`** (was `false`) | nothing, correctly |
| `/managers/:id/history` **visited directly** | `false` | — (no referring league; correct) |
| `/mock/:id` | `false` here | — (see note) |

**Other measurements:**

- `/managers`: **53 rows, 53 profile links** (0 before).
- Palette: opens on Ctrl+K with focus in the input; 40 results; groups Pages / Seasons / Managers;
  multi-word `foot power` narrowed to the two football leagues' Power rankings; Enter navigated and
  the rail marked Power rankings current in the league it landed in.
- Sport gate live: the NBA "Ball Knowers" league offers Draft room / Follow live / History / Power
  rankings and **no Analysis**; "(Foot) Ball Knowers" (NFL) offers Analysis.
- Switcher from an NFL Analysis page: NFL targets kept `/analysis`, the NBA target fell back to
  `/history`.
- Collapsed draft room at 1440×900: rail `app-rail collapsed` at **56px**, inline season row absent,
  both seasons reachable through the flyout, `.board-scroll` `scrollWidth` **1281px unchanged**
  (NFR-004 intact). The phone control is `display: none` here.
- Phone at 375×812 scrolled 4868px down: the bar has scrolled away (as designed) and the fixed
  control is on screen — the return trip works with no permanent band.

**Note on `/mock/:id`.** The frontend under test was proxied to a backend running pre-V16 bytecode,
which does not serve `sourceSleeperLeagueId` — so the mock room correctly showed no league context
and, importantly, **did not throw**, which is the split-deploy guarantee. The stored round trip is
covered by `MockDraftRepositoryIT` against real Postgres (11 tests, **0 skipped**). The mock room
showing its league against a V16 backend is the one claim here that is tested but not
browser-verified.

**A bug this pass caught that the unit tests could not.** The switcher flyout was
absolutely positioned inside the rail, which is `overflow-y: auto` and 56px wide when collapsed.
Measured: the menu ran to x=263 and was clipped at x=56 — the seasons were present in the DOM (so a
DOM-presence test passed) and a sliver on screen. Fixed by positioning the menu against the viewport
from the rail's own right edge; re-measured at `menuLeft: 62` against `railRight: 56`, fully on
screen.
