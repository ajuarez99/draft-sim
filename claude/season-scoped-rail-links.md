# Season links keep you on the page you were on

**Status: planned 2026-09-29, nothing built.** Reported by Allan: in "Ball Knowers",
clicking **2025** and then **Superlatives** lands on the 2025 *draft board*, not the
2025 superlatives. Expected: pick a year, then every tab is that year.

File:line references were checked against `main` at `04c2043`. Nothing
below has been executed yet; the "already works" claims are **read, not run**, and the
first build step is to confirm them.

## What's there now

Two separate things cause it, and fixing only one of them doesn't fix the bug.

1. **The year links always go to the board.** `seasonHref`
   (`web/src/components/LeagueRailSection.tsx:133`) returns `/drafts/:id/board` (or the
   draft room) no matter which page you're on. It's used by the row of years
   (`:396-406`) and by the "Seasons" group in the Switch flyout (`:437-447`).
   The "Leagues" group right below it already does the right thing:
   `switchTarget(currentKey, …)` (`:149`) keeps your page, and falls back to History
   when the target doesn't offer it. The year links never got the same treatment.
2. **Every league-page destination builds its URL from the newest season.** In
   `web/src/destinations.ts`, every `/leagues/...` row's `href` uses
   `ctx.lineage.current.sleeperLeagueId` (`:128`, `:139`, `:158`, `:174`, `:188`,
   `:202`, `:215`, `:228`), and the header comment (`:88-94`) says why: History and
   Power Rankings walk the whole chain. So even once you're on a 2025 page, every rail
   tab sends you back to the current season. The board is the only row that uses
   `ctx.season`.

The parts that should already work (read, **not yet run**):

- **The backend answers for whichever season id it's given.** `LeagueSeasonResolver`
  (`backend/.../engine/LeagueSeasonResolver.java`) calls `chainBySleeperId`, which walks
  *backwards* from the given id through `previous_league_id`
  (`LeagueRepository.java:281-291`). So `/leagues/<2025 id>/superlatives` should answer
  about 2025. It only walks further back if 2025 has no scored weeks, and it says so
  when it does (`SeasonFallbackNote`). Superlatives, Weekly report, Expected wins,
  Roster management and playoff odds all go through it. `LeagueAnalysisService:209`
  uses `bySleeperId` directly: it gives the exact season, with no fallback.
- **The rail already knows which season a league URL means.** `useRailLeague`
  (`web/src/railLeague.ts`, the `resolve()` body) sets `season` to the lineage entry
  whose `sleeperLeagueId` matches the path. So `/leagues/<2025 id>/superlatives` should
  highlight 2025 in the year row, not the current season.

## Design

**A. Split destinations into "whole chain" and "one season".**

- **Whole chain (keep `lineage.current`):** `history`. It shows every season at once,
  so there's no year to keep.
- **One season (switch to `ctx.season.sleeperLeagueId`):** `analysis`,
  `rosterManagement`, `expectedWins`, `forecast`, `weeklyReport`, `superlatives`.
- **`power` needs a decision at build time.** It walks the chain but also has its own
  season selection (`PowerRankings.tsx:480-482` reads history and picks a season).
  Recommended: use `ctx.season`'s id and have the page start on that season. Check what
  the page does with a non-current id **before** choosing.
- Update the header comment at `destinations.ts:88-94` to say which rule each row
  follows. The current comment would be wrong after this change.

**B. Year links go through `switchTarget`.**

Replace `seasonHref(s)` at both call sites with
`switchTarget(currentKey, { lineage, season: s })`. That gives three cases:

- On the board, you stay on the board for the other year. This is what happens today.
- On Superlatives, you go to that year's Superlatives.
- On a page that year doesn't offer, you go to History. For example, a football-only
  page, or a `requiresStatus` mismatch. *(Amended 2026-09-30, see A3: from a draft page
  the fallback is that year's board, not History. The football-only case can't come up
  through year links, see A2.)*

After this `seasonHref` has no callers. Delete it, since it duplicates `draftRoute`.

**C. Pages that don't make sense for a finished season.**

The **Forecast** (playoff odds) for a completed season is a forecast of something that
already happened. Check what `SeasonForecastController` returns for a complete season:

- If it already refuses or explains itself, leave it.
- If it would show odds for a finished season as if they were live, don't offer it.
  Destinations currently gate only on *draft* status (`requiresStatus`). A league-status
  gate (the V21 `league.status` / `complete()` flag) doesn't exist on `DraftSummary`
  yet. The smallest honest version is for the page to say "this season is over, here's
  how it ended" instead of hiding the tab.

That call is made at build time from what the endpoint actually returns, and recorded
here as an amendment. *(Amended 2026-09-30, see A4: Analysis gets the same check.)*

## Not building

- **A year picker inside each page.** The rail's year row is the picker, and adding a
  second one to each page is how the two would end up disagreeing.
- **A `?season=` query param.** The season's own Sleeper league id already identifies
  it, and the backend and rail both key off it. A second encoding of "which year" is
  the "two implementations of one rule" class from the multi-sport landmines.
- **Changing History.** It's the one page that really is chain-wide.

## Risks worth checking, not assuming

- **Resolver fallback on a new season.** Clicking 2026 before week 1 is scored makes
  Superlatives walk back to 2025 and say so. That's correct and already built. Confirm
  the rail still highlights **2026** in that case, since the URL says 2026, and the
  fallback note carries the rest.
- **Data-version bumping.** `useLeagueDataVersion(sleeperLeagueId)` is keyed by the URL
  id. The refresh hook bumps every season id in the lineage
  (`LeagueRailSection.tsx:71`), so an old-season page should still refetch after a
  refresh. Verify it rather than assuming.
- **Commissioner conduct list** on Superlatives is addressed by the *resolved* league
  row, not the URL (`Superlatives.tsx:557-570`). A 2025 URL should now edit 2025's
  list. Confirm that's the intent. It probably is, because 2025's list is 2025's.
- **Jump-to palette** (`searchIndex.ts`) also reads `destinationsFor`. It will start
  producing season-specific URLs for whichever season `ctx` carries. Check it passes
  `lineage.current` as the season, so the palette keeps meaning "this league, now".

## Acceptance criteria

1. **Unit** (`destinations.test.ts`, `LeagueRailSection.test.tsx`):
   - For a two-season lineage, while on `/leagues/<current>/superlatives`, the 2025 year
     link's `href` is `/leagues/<2025 id>/superlatives`.
   - The same link while on the 2026 board is the 2025 board.
   - While on History it's History. The History href is identical for both years.
   - ~~While on Analysis, the year link for an NBA season goes to that season's History,
     because Analysis is football-only.~~ *Replaced by A2: this can't happen, see below.*
   - While on Follow live for a pre-draft season, the year link for a completed season
     is that season's board (A3).
   - While on Weekly report with `?week=5`, the year link carries no query string (A5).
   - On a 2025 page, every one-season rail row's href carries the 2025 id.
2. **Live, in the browser, on "(Foot) Ball Knowers" and "Ball Knowers" (NBA):**
   - Click 2025, then Superlatives: the page header says 2025, and the cards are 2025's.
     *(The header had no year when this was written. A1 adds one.)*
   - Then Weekly report: it's still 2025.
   - Then click 2026: you're on 2026's Weekly report.
   - The year row highlights the year shown in each case.
   - Screenshot the 2025 Superlatives.
3. **Forecast on a finished season**: record what it actually does, and the decision
   from Design C, as an amendment to this doc. Do the same for Analysis (A4).

## Amended after analysis (2026-09-30)

A `/speckit-analyze` pass over this doc and `specs/011-season-scoped-rail-links/tasks.md`
checked the code on `587fbc3` and found these. The text above is kept, and each change
is marked where it applies.

- **A1. The one-season pages don't state their year.** Acceptance 2 said "the page
  header says 2025", but the Superlatives `PageHeader` is fixed text: eyebrow "League",
  title "Superlatives" (`Superlatives.tsx:101-105`). No page could meet that check. The
  reported bug was not being able to tell which year you're looking at, and a page that
  never names its year still has that problem once the links are fixed. **Decision:**
  each one-season page's header names the season the response resolved to, taken from
  the payload, not the URL, so it can't disagree with `SeasonFallbackNote`. This isn't
  the in-page year picker ruled out under "Not building": it's a label, not a control.
- **A2. "NBA on Analysis → History" can't happen through year links.** A lineage is one
  sport, and the rail works out the lineage from the URL. On an NFL Analysis page every
  year link is NFL. The cross-sport fallback belongs to the Leagues flyout, which already
  uses `switchTarget`. It's tested there directly, labelled as flyout coverage.
- **A3. Follow live → another year lands on that year's board, not History.** Follow
  live requires `pre_draft`/`drafting`, so a completed year doesn't offer it. Today's
  `seasonHref` sends that click to the other year's board. Design B as written would
  have changed it to History. **Decision:** `switchTarget` falls back to `board` when the
  page you're on is a draft page (`idKind === 'draft'`), and to History otherwise. The
  rule is read from the destination table, not restated.
- **A4. Analysis has the same finished-season question as Forecast.** Two of its three
  blocks are rest-of-season projections, and 2025 Analysis becomes reachable from the
  rail once this ships. It's investigated and decided the same way as Design C.
- **A5. `?week=` doesn't carry across years.** Weekly report keeps the week in the query
  string (`WeeklyReport.tsx:31-34`). `switchTarget` builds a URL without it, so a year
  link from week 5 lands on the other year's latest week. That's correct, because week 5
  of another season is a different week. A test pins it.
- **Minor.** The header comment is at `destinations.ts:92-97`, not `:88-94`. There is a
  third copy of `draftRoute` at `pages/DraftPicker.tsx:56`, which is out of scope here.
