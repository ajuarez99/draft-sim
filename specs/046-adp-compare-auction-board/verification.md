# Spec 046 live verification, 2026-10-23 (evening CDT)

**Setup**
- Code: this worktree, fast-forwarded to `origin/main` `48371dd` (spec 045) with the 046 changes on top,
  uncommitted.
- Backend: `bootRun` on **8146** against the **scratch DB on 5436** (`draftsim-pg-046`, a copy of 5433), with
  admin token `local046`.
- Frontend: Vite on **5246**, proxied to 8146.
- Browser: the built-in browser pane, signed in as popsharky (the app's username-only local sign-in).
- The shared 5433 DB and main's servers were never touched.

Every line below is **measured**, unless it says "not covered".

## Suites

| When | Backend (tests / skipped / failed) | Web (vitest) |
|---|---|---|
| Baseline, `5512cd7` | 2,217 / 6 / 0 | n/a |
| After Phase 2 + US4 | 2,238 / 6 / 0 | n/a |
| After US1 | 2,289 / 6 / 0 | 2,417 passed |
| After US2 + US3 | 2,294 / 6 / 0 | 2,459 passed |
| After review fixes + merge of main | 2,362 / 6 / 0 | 2,496 passed, tsc clean, build OK |
| Final (after the verification fixes below) | **2,365 / 6 / 0** | **2,496 passed** (142 files), tsc clean, build OK |

The 6 skips are the known spec-041 `PlayerStatsLeaderboardReadIT` fallbacks throughout. Every new IT ran.

## Quickstart checks

| Check | Result |
|---|---|
| **V3** auction ingest | `POST /api/ingest/all/1415422393706455040`: 230 picks, Σ amount **1,982**, `budget` **200**, `draft_type` auction. 230/230 rows carry `player_name`, and 0 have a null amount. **PASS** |
| **V5 / SC-004** profiles | Against `baseline/profiles-before-ingest.json` (old code, before the auction was ingested): **44 of 44 real NBA managers identical, 0 changed**. The 3 "missing" ids are IT fixture managers (`IT X`, `IT Y`, `IT Week X`) that test runs recreated under new ids. The old code changed all 47 (see `baseline/README.md`). **PASS** |
| **US4 AS2** | The two auction-only managers (hinrichmanuver, s2jaKe) come back as **NEUTRAL, 0 drafts observed, 0 picks scored**. With the old code both were FITTED. **PASS** |
| **V1** sources | `sleeper_dynasty` **817**, `sleeper_redraft` **903**, `yahoo` **185** rows, all captured today. All 185 Yahoo rows carry `avg_cost` and `percent_drafted`. **PASS** |
| **V2** Yahoo matching | 185 fetched, **185 matched, 0 unmatched** (strict matcher, team tie-break). **PASS** |
| **C3** season rule | `POST /api/ingest/adp/sleeper?season=2025` was refused: "season 2025 is not the ADP compare page's season (2026)", and nothing was written. **PASS**, after the fix below. |
| **V6** refusals | `POST /api/sims`, `POST /api/mocks/from-draft/{id}` and `POST /api/drafts/{id}/picks` each return **409** `{"error":"auction_draft","message":"This is an auction draft. The simulator models snake pick order, which an auction doesn't have."}`. `GET /api/drafts/{id}/board` stays **200**. Grades return `available:false, reason:"AUCTION_DRAFT"`. **PASS** |
| **V6b** routing | `/drafts/<auction>` and `/drafts/<auction>/live` both redirect to `/leagues/1415422393706455040/adp`. On league home, the rail has one "Auction board" entry and "See what everyone paid", both pointing at the ADP page. There's no "Mock it" anywhere. The home page offers no snake-room or mock link for the auction league. **PASS** |
| **V7** page (desktop) | See the details below this table. **PASS** |
| **V7** page (375px) | Document width 375, so the page itself never scrolls sideways. The 1,089px table scrolls inside its own frame. Budget cards sit two per row, after the fix below. **PASS** |
| **V8 / SC-003** latency | See the details below this table. **PASS** |
| **V9** snake league | "Ball Knowers" NBA 2026: the ADP page renders 985 players with **no budget cards and no sales feed**. The Blend $ note is present. The draft links still go to `/drafts/<id>/board`, and an "ADP & prices" rail entry was added. **PASS** |
| **V10** tint counts at gap ≥ 20, all players shown | **40 green, 18 red**. The plan review predicted 40 green under the ≥50%-drafted rule alone. |

**V7 desktop details:**
- The three source cards show "fetched on" and their counts: 817, 903 and 185.
- The budget cards add up to **$18 left** across the league (ImReallyHarry $17, s2jaKe $1), which matches Sleeper. popsharky is outlined and marked "You".
- The sales feed renders.
- The blend sliders are labelled "hand-set".
- The FR-013 notes render: redraft, categories assumed, derived, hand-set, and does not model top-heaviness.
- Kevin Durant is **green**: "Yahoo 53.2 picks earlier".
- AJ Dybantsa is **red**: "Yahoo 65.1 picks later".
- LeBron James is **green**: "Yahoo 118.4 picks earlier", 100% drafted. The old deepest-ADP cutoff would have hidden him.
- Players under 50% drafted show "drafted in N% of Yahoo leagues" and "not compared", untinted.
- Wembanyama shows "Sold $112" against Blend $ **$77**. That's the top-end miss the page discloses.

**V8 / SC-003 details:**
- The harness is `harness/stub_sleeper.py`. It serves the draft as `drafting`, adds one fixture pick every 6s, and proxies everything else to the real Sleeper. The backend's `SLEEPER_BASE_URL` points at it.
- The page's MutationObserver timestamped each new sale. Across **12 sales**: median **1.55s**, max **2.15s**, min 0.21s, from the stub adding a pick to the sale showing on the page. That's within one 2s drafting poll plus the refetch.
- The steady drift is the stub's 6s period against the 2s poll phase. Raw data: `harness/stub-adds.log`, `harness/latency.json`.

## Bugs found by live verification and fixed here

1. **The "current season" rule followed a stray league (`AdpSeason`).**
   - What happened: an IT fixture league at season 2099 ("IT 014 NBA", left in the scratch DB by a test run) became the "current" NBA season, so **every real 2026 Sleeper capture was refused**.
   - Fix: seasons later than next calendar year are ignored. Renewals into next year still count.
   - Test: `AdpSeasonTest`. Its first case fails on the old rule, which picks 2099.
   - Re-verified live: the 2026 capture stores 817/903, and 2025 is refused.
2. **"Green" and "red" weren't green and red.**
   - What happened: every tint was `color-mix(in oklch, <hue>, var(--panel))`. OKLCH interpolates hue toward the panel's blue, so the "green" key measured hue 197 (teal), the green gap pill hue 236 (blue) and the "red" key hue 307 (purple).
   - Fix: the ADP block now mixes `in oklab`. Red is a true red-orange (`--adp-red`, `oklch(66% 0.19 38)`), kept off crimson ("you") and off `--down`'s pink.
   - Re-measured: the keys are now OKLab a = −0.08 (green) and a = +0.08, b = +0.05 (red-orange).
3. **The legend's red swatch dangled at the end of the green line at desktop width.** Each swatch is now grouped with its own sentence.
4. **On a phone, budget cards ran one per row,** putting ten tall cards above the table. Now two per row, slightly tighter.

## Known, not fixed (recorded)

- **The "fetched on" date is UTC.** Captures and the daily gate both use the UTC date, consistently, and production runs in UTC. On this machine in the evening (CDT) it reads tomorrow's date ("Oct 24"). Cosmetic.
- **The page fetched `adp-compare` 4 times on first load** in dev. React StrictMode doubles effects in dev; production wasn't measured.
- **The two `403`s in the console** are the existing league auto-refresh POST (spec 009) refusing this sign-in. They aren't from 046.
- **Site-wide**, 229 `color-mix(in oklch, …, var(--panel))` uses shift hue toward blue the same way. Only the ADP block is fixed here. This is a candidate follow-up, recorded in `claude/lessons.md`.
- **Test-suite residue:** the ITs leave fixture rows (the season-2099 league, `IT X` managers) in whatever DB `DB_URL` points at. That's why 046 used a scratch DB.

## Not covered

- **A real in-progress Sleeper auction.** The live path was exercised only through the stub harness.
- **Production.** Nothing is deployed. After deploy, the **post-deploy re-ingest of Legacy Ball Knowers** in HANDOFF.md is required, or the finished auction shows "prices haven't been ingested yet".
- **An ESPN NBA league's ADP page.** There's no ESPN league in the scratch DB; it's covered by `AdpCompareMvcIT` only.
- **Whether production ever ingested Legacy Ball Knowers before this.** If it did, production NBA profiles are polluted until this ships and the league is re-ingested (`baseline/README.md`).
