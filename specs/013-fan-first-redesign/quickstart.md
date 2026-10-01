# Quickstart: validating the fan-first redesign

This is the live-verification guide for each phase. A green suite is necessary, not
sufficient (AGENTS.md). Every phase ends with the browser checks in §4 at all three
sizes and in both sports.

## 1. Prerequisites

- Postgres reachable per `application.yml` (local 5433 cluster). **Check the backend
  suite's skipped count.** "BUILD SUCCESSFUL" with ITs skipped means Postgres was down,
  not that they passed.
- Data: re-ingest before trusting any page:
  `POST /api/ingest/all/1346366555759341568` (NFL, "(Foot) Ball Knowers") and
  `POST /api/ingest/all/1339351318115946496` (NBA, "Ball Knowers").
- Servers: `preview_start` with `draft-sim-api` and `draft-sim-web` (`.claude/launch.json`).
  If working in a worktree, confirm the bootRun classpath is the worktree's, not
  main's (memory: worktree preview serves main).
- After any Java change, restart `draft-sim-api` (no hot reload). After restarting
  the web server, hard-refresh open tabs (Ctrl+Shift+R).
- Two identities (ask Allan before signing in as anyone but popsharky):
  - **commissioner:** popsharky (`is_owner: true` on "(Foot) Ball Knowers", measured 2026-09-30)
  - **fan:** a non-commissioner member of the same league

## 2. Before touching copy: caveat inventory

For each page in the spec's route inventory, before its copy changes, record every
caveat sentence and badge into `specs/013-fan-first-redesign/caveats-before.md`
(page → text). After the change, produce `caveats-after.md` with where each one now
lives (beside the number / under "How this works"). **Pass:** every *before* row has
an *after* location. No rows are dropped (SC-002).

## 3. Automated checks

```bash
cd web && npx tsc -b && npm test && npm run build
```
```bash
cd backend && ./gradlew test
```
The tests that must exist and pass:
| Test | Asserts |
|---|---|
| Letter grades ordering | higher rank never gets a lower grade; ties share a grade; `gradesEarly` true at 3 weeks, false at 4 |
| All-play / median invariants | per-team all-play total = weeks × (n − 1); league Σ wins = Σ losses |
| Early threshold single source | superlatives and grades read the same constant (one declaration) |
| History `canCommission` | true for the Sleeper commissioner and the configured owner, false for a member, absent → Compute hidden |
| `PlayerFace` fallback | photo error → logo; logo error → initials; DEF starts at logo; null team → initials |
| Destinations | every league route still has a row; every row has a group and a label; old names still match in jump-to |
| Archetype honesty | `picksScored = 0` never yields a reach-based label |
| Tiers | a gap > `adpGap` starts a tier; ADP 999 lands in "Unranked" |

## 4. Browser checks (each phase, 3 sizes × 2 sports)

Sizes: `resize_window` 1440×900, 768×1024, 375×812. Reset to desktop afterwards.

**4a. Nested surfaces (SC-001).** Run on each page:
```js
// Count bordered/filled ancestors for every leaf; report the worst.
(() => { const box = el => { const s = getComputedStyle(el);
  return parseFloat(s.borderTopWidth) > 0 || (s.backgroundColor !== 'rgba(0, 0, 0, 0)' && el !== document.body); };
  let worst = 0, where = null;
  for (const el of document.querySelectorAll('main *')) { if (el.children.length) continue;
    let n = 0; for (let p = el; p && p.tagName !== 'MAIN'; p = p.parentElement) if (box(p)) n++;
    if (n > worst) { worst = n; where = el.textContent.slice(0, 40); } }
  return { worst, where }; })()
```
**Pass:** `worst ≤ 2`. Chips, badges and buttons are leaves themselves and are
excluded by design. The script counts containers above a leaf, so a chip inside a
row inside the panel scores 2.

**4b. Horizontal scroll.** `document.documentElement.scrollWidth <= innerWidth` on
every page at every size.

**4c. Commissioner (SC-003).** As the fan, visit every "(Foot) Ball Knowers" page:
no Compute/Recompute anywhere. As popsharky: the controls appear on History,
Power and Forecast. Clicking one without a saved key prompts for the key as before.

**4d. League home (SC-005).** As popsharky, at each size, without scrolling:
record, rank and this week's opponent are visible. As a signed-in non-member: no
"you" block and no error.

**4e. Draft room (SC-006, SC-007).**
- Simulator (`/drafts/1339351318128517120`): a loading message within 1s of load,
  and never a blank panel. After it runs: tiers plus availability bars, visible
  without clicking (desktop/tablet), one tap on phone.
- Mock (`/mock/new` → start): tiers and photos visible when on the clock. No
  availability bar, and the reason line is present (research R5).
- Live room: tiers plus availability once the seat is known.

**4f. Faces (SC-008).** On `/drafts/1346366555776126976/board` (NFL) and an NBA mock
board: count `img` elements with `naturalWidth === 0` (must be 0) and cells with
neither an image nor initials (must be 0).

**4g. Contrast (SC-011).** Check the computed colors of the volt button, the "You"
chip (`--crimson-fill`), muted text and `--down` values. Each is ≥ 4.5:1.

**4h. Addresses (SC-012).** Open every route in the spec's inventory by its old
address. Each lands on the same page.

## 5. Proof

For each phase, attach screenshots of the home, power, weekly report and draft room
at 1440 and 375, for both sports. Record measured numbers (nested depth, contrast
ratios, image-failure counts) in the phase's commit message or build notes, labelled
measured.
