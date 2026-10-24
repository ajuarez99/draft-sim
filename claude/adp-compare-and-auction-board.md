# ADP compare + live auction board

Status: **proposed feature, not built in the app.** 2026-10-23. A standalone
prototype was used live on 2026-10-21 for "Legacy Ball Knowers" (Sleeper NBA
2026, 10-team, $200 auction, dynasty). Allan, afterwards: "this was pretty
useful for drafts in general". Next step is `/speckit-specify` from this doc.

Supersedes the "don't build" decision in [auction-drafts.md](auction-drafts.md).
That doc's trigger ("unless a real league switches to auction") has now
happened. See its amendment note.

## What exists now

**In the app: nothing.** Checked on `origin/main` `5512cd7`:

- No auction prices stored. `draft_pick` has no amount column. Highest
  migration is V38.
- `SleeperProjectionClient` reads projections but not the `adp_dynasty` /
  `adp_std` fields that come back in the same payload.
- No Yahoo source of any kind. ESPN maps `AUCTION` to `"auction"`
  (`EspnTranslator.java:484`), but nothing downstream uses that.

**Outside the app: a working prototype.** It's
[prototypes/auction-board.html](prototypes/auction-board.html): one static file
with the player data baked in (pulled 2026-10-21), polling Sleeper from the
browser.

- **Comparison table.** Sleeper dynasty ADP, Sleeper redraft ADP, Yahoo ADP and
  Yahoo average auction price for about 380 players. Search, position filter,
  rookies-only toggle, and several sorts (dynasty premium, vet discount).
- **Gap highlight.** A row is tinted green when Yahoo drafts the player at
  least N picks earlier than Sleeper dynasty, and red the other way. N is a
  slider.
- **Blend.** Two sliders pull each player's ADP toward Yahoo's as the gap
  grows. "Blend $" is Yahoo's price at the player's re-ranked blend position.
  Both sliders are hand-set, not fitted.
- **Live auction.** Polls `/v1/draft/{id}/picks` every 3s and shows:
  - each team's budget cards: $ left, spots left, max bid = $ left − (spots
    left − 1)
  - a latest-sales feed, with price vs Blend $
  - sold players dropped from the list
  - a manual "mark sold" with undo, as a fallback if polling stalls

## Data sources (verified by calling them, 2026-10-21)

| Source | Endpoint | What it gives | Notes |
|---|---|---|---|
| Sleeper projections | `api.sleeper.app/projections/nba/2026?season_type=regular` | `stats.adp_dynasty`, `stats.adp_std` (redraft) per player; 1,857 rows, 835 with a real dynasty ADP | 999 = undrafted. Same payload the app already fetches. |
| Yahoo public API | `pub-api-ro.fantasysports.yahoo.com/fantasy/v2/league/nba.l.public;out=settings/players;start=N;count=25;sort=DA_AP;out=draft_analysis?format=json_f` | `draft_analysis.average_pick`, `average_cost` ($200 budget), `percent_drafted` | No auth needed. **No CORS**, so it must be fetched server-side. Only 186 players have a numeric ADP (rest `-`). **Redraft only.** Yahoo publishes no dynasty ADP. Adding `auctionvalues` to `out=` returns 400. |
| Sleeper draft picks | `api.sleeper.app/v1/draft/{id}/picks` | `metadata.amount` (string, $) on auction picks, plus `picked_by`, `roster_id`, `pick_no` | `Access-Control-Allow-Origin: *`. Confirmed on the completed 230-pick auction (2026-10-23). |
| Sleeper draft | `api.sleeper.app/v1/draft/{id}` | `type: "auction"`, `settings.budget`, `settings.rounds` (= roster spots incl. taxi), `settings.teams` | `rounds` was 23 = 19 roster + 4 taxi. |

Name matching Yahoo to Sleeper (strip accents, punctuation, and Jr/Sr/II–V
suffixes) matched **186 of 186**. `PlayerMatcher` should do at least as well.

## Measured against the real auction (one draft, n=1)

The completed draft: 230 sales, $1,982 of $2,000 spent, 120 sales at $1.
Comparing each price guide (as it was on draft night) with what players
actually sold for:

| Guide | Rank correlation vs real price | Sum of guide over sold players |
|---|---|---|
| Yahoo $ | 0.911 | $2,081 |
| Dynasty $ (blend at 0%) | 0.935 | $2,083 |
| Blend $ (75% / 30 picks) | 0.950 | $2,083 |

- **This is one draft.** It does not show the blend is better in general, and
  nothing was tuned to it after the fact.
- **The top end ran hot.** Wembanyama sold for $112 against a guide of $81,
  Jokić for $100 against $67–77, and KAT for $51 against $35. Yahoo's curve
  comes from mostly 12-team, ~13-spot leagues. This league had 10 teams and 23
  spots, so the same $2,000 was concentrated in fewer contested players. A
  real feature should rescale the curve to the league's teams × budget ×
  roster spots, not reuse Yahoo's raw prices.
- The median absolute error (~$0.50) means nothing. It's dominated by the 120
  $1 sales.

## Proposed design (for the spec to decide)

1. **Ingest.**
   - Persist `adp_dynasty` / `adp_std` from the projection fetch the app
     already makes.
   - Add a server-side Yahoo ADP fetch (daily is plenty) into `adp_snapshot`,
     with `source='yahoo'`, `source_scoring` and `derived` flags per
     [adp-sources.md](adp-sources.md).
   - Add `amount` to `draft_pick` (next migration after whatever main holds
     then; V39 as of today).
2. **Page.** An "ADP compare" view, league-scoped (works for snake drafts
   too): the comparison table, gap highlight and blend sliders. This is the
   part Allan called useful "for drafts in general".
3. **Live auction mode.** Only when `draft.type = 'auction'`: budget cards,
   max bid, sales feed and sold-price column. Reuse the existing live poller
   rather than a second browser-side poll.
4. **Honesty labels.**
   - Yahoo is redraft.
   - Blend $ is derived, not market data.
   - Blend knobs are hand-set.
   - Prices are rescaled from a different league shape.

## Explicitly not building

- **Auction simulation.** No nomination/bid modeling, and no manager auction
  profiles. Zero auction history beyond this one draft; the reasons in
  [auction-drafts.md](auction-drafts.md) still hold.
- **Showing the player currently up for bid.** Sleeper's public API doesn't
  expose the current nomination. Updates arrive when a player sells.
- **Fitting the blend weights.** One draft is not a training set.

## Acceptance criteria (sketch)

1. Every Yahoo-ranked player matches a Sleeper player, or is listed as
   unmatched. Never silently dropped.
2. Max bid = budget − spent − (spots left − 1), asserted against a recorded
   pick list (the 2026-10-21 draft is a ready fixture).
3. Preference-ordering test for the blend: at 0% trust, order equals Sleeper
   dynasty; raising trust moves a Yahoo-favoured player up, never down.
4. Rescaled prices sum to the league's teams × budget, minus minimum bids, over
   the drafted pool.
5. Live: the auction mode drives a real Sleeper auction draft (or a recorded
   replay) in the browser, with budgets matching Sleeper's own draft room.
