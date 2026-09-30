# Site audit, 2026-09-28: security, correctness and design

A testing-and-design pass over the running site, looking for things that break, don't make
sense, or leave a hole. Each finding is cut into its own plan in this folder, sized for one
agent to pick up. The plans follow this repo's feature convention, scaled down: what's there
now (with file:line), the fix, what's deliberately not in scope, and acceptance criteria.

> **Amended 2026-09-29.** Spec 009 (automatic data refresh) has since merged, as commit
> `d90c2f9`. It adds new routes that touch plan 01 (see the amended note there), and it now
> owns data freshness (see "Not covered by this pass" below). Also, plans 01 and 02 were
> written during the 2026-09-28 audit session. Plans **03–13 were written on 2026-09-29 by
> separate agents**, not by the session that ran the audit. Check each plan's file:line refs
> against the tree before building from it.

## How this was tested

- **Local stack.** Docker Postgres on 5433, `bootRun` on 8080 and `vite dev` on 5173, signed in
  as `popsharky`. The pages were driven in the browser at 1400x900 and at 375x812.
- **Production** (`api.ballknowers.co`, `www.ballknowers.co`) was probed with **read-only**
  requests only: GET and CORS preflight OPTIONS. Nothing was written to production.
- **Every write probe ran against local only, and each was restored afterwards.** Tendencies
  were put back to `{}`. Draft 2's pick 1 was put back to player 2034 and checked row-for-row
  in SQL. The probe conduct entry (id 35) was deleted and confirmed gone.
- Branch at the start was `008-season-superlatives`. During the session a peer switched the
  tree to `009-auto-data-refresh` (untracked `specs/009-auto-data-refresh/`). **Nothing was
  committed.**

## Findings, ranked

"Verified" means the behaviour was actually executed and observed. "Read" means it was found
in the code and not executed.

| # | Plan | Severity | Status |
|---|---|---|---|
| 01 | [Callers with no identity see and write more than signed-in ones](01-anonymous-bypass.md) | **High** | Verified, local and prod (reads) |
| 02 | [Anyone can rewrite any manager's tendencies](02-tendency-writes.md) | **High** | Verified, local |
| 03 | [Manual pick endpoint rewrites completed drafts](03-manual-pick-guards.md) | **High** | Verified, local |
| 04 | [Commissioner gate falls to one public Sleeper lookup](04-identity-spoofing-decision.md) | Medium (needs a decision) | Verified, local |
| 05 | [No limit on simulation cost](05-sim-cost-limits.md) | Medium | Measured, local |
| 06 | [No security headers on the frontend](06-security-headers.md) | Low | Verified, prod |
| 07 | [Expected wins explains luck backwards](07-expected-wins-luck-copy.md) | Medium (wrong statement) | Verified, local |
| 08 | [Power rankings: "Your ballot" column and "pts" label](08-power-rankings-ballot-column-and-pts.md) | Medium | Verified, local |
| 09 | [History "Rank" on a live season is a stale power rank](09-history-rank-on-live-season.md) | Low–Medium | Verified, local |
| 10 | [Stale forecast; Weekly report opens on week 1](10-stale-forecast-and-weekly-default.md) | Low–Medium | Verified, local |
| 11 | [Reach bias is mostly a league-wide offset](11-reach-bias-baseline.md) | Medium (investigation) | Measured, local + prod |
| 12 | [Phone layout: hidden pages, inner scroller, tiny icons](12-mobile-nav-and-scroll.md) | Medium (design) | Verified, local |
| 13 | [Not-found pages and copy consistency](13-not-found-and-copy-consistency.md) | Low (design) | Verified, local |

## Suggested order

1. **01 → 02 → 03** share one root cause and should land close together: every write path
   treats a missing `X-Sleeper-User` as "trusted". Fix 01's policy first; 02 and 03 then
   become small.
2. **04** is a decision for Allan, not a build task. It shouldn't be started until he picks an
   option.
3. **07, 08, 09** are small, self-contained frontend fixes that can go in parallel.
4. **05, 06, 10, 12, 13**, in any order.
5. **11** is an investigation. Its outcome is a measured write-up, and "change nothing" is an
   allowed result.

## Checked and found fine (so nobody re-audits them)

- **CORS on production is exact.** `Origin: https://evil.example` gets a preflight 403, and
  `https://www.ballknowers.co` is allowed.
- **Sign-in input is escaped.** `<img src=x onerror=alert(1)>` renders as text. The Sleeper
  lookup uses URI templates (`SleeperClient.java:26`), so a username can't inject a path.
- **Mock sessions reject a stranger's identity** with a 404. They only fail open for an
  anonymous caller, which is covered in 01.
- **The unknown-route 404 page** (`/this/does/not/exist`) is designed and links home.
- **No console errors** across the pages swept at desktop width.
- **No horizontal page scroll at 375px** on Power rankings (`scrollWidth == innerWidth`).

## Not covered by this pass

- The live draft room during an actual `drafting` draft (none is live), and SSE `?user=`
  scoping.
- NBA league pages, beyond the home cards.
- A load test against production. Plan 05's numbers are local only, on 12 cores.
- ~~"Data only refreshes when someone ingests". The peer spec `specs/009-auto-data-refresh/`
  already appears to own this.~~ **Amended 2026-09-29:** spec 009 has merged (`d90c2f9`) and
  owns data freshness: refresh on visit, a daily job, and per-week per-game ingest. That is
  out of this audit's scope. Its access-control side is not, and plan 01's amended note
  covers it.

## Decisions (2026-09-29, Allan)

- **02:** notes only, and private per author.
- **04:** option D, the admin secret for commissioner actions, including `POST /power/compute`.
- **13:** team name first, username secondary.
- **Still open**, recorded in each plan:
  - **03:** allow manual picks on `pre_draft`? And does Sleeper have other live statuses?
  - **10:** recompute the forecast from 009's daily Action?
  - **11:** the investigation's outcome.
