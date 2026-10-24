## Spec 046: ADP compare + live auction board

A league page at `/leagues/:id/adp`. It compares Sleeper dynasty ADP, Sleeper redraft ADP, and Yahoo ADP and average auction price. It started as a prototype used live in Legacy Ball Knowers' $200 dynasty auction on 2026-10-21.

- **Gap tints.** Green means Yahoo takes the player earlier, red means Sleeper dynasty does. Only Yahoo values drafted in at least 50% of Yahoo leagues count. Every gap is also written out in words.
- **Trust-Yahoo blend.** Two sliders, labelled hand-set, produce a "Blend $" price. It's Yahoo's price curve scaled to add up to the league's total budget. The page says plainly that it does **not** model how fewer teams and deeper rosters push money to the top.
- **Live auction mode** (auction drafts only):
  - per-slot budget cards: money left, spots left, max bid
  - a sales feed
  - sold prices on each player row

  It's refreshed through the existing live-stream SSE; there's no second poll.
- **Auction picks are kept out of every snake-order model.** It's one rule, `DraftRepository.modelsSnakeOrder`, applied to:
  - profile fitting
  - the `adp_at_time` backfill
  - the observed board
  - grades (`AUCTION_DRAFT`)
  - a 409 on the simulator, fork-to-mock and manual pick

  The real completed board (`GET /drafts/{id}/board`) is unchanged. Auction leagues are routed to the ADP page from every entry point.

### Why the model exclusion matters (measured)

With the **old** code, ingesting the real auction changed **all 47** NBA manager profiles and fitted two auction-only managers from nomination order. With this change, **44/44 real profiles are unchanged** and the two auction-only managers are NEUTRAL. Details: `specs/046-adp-compare-auction-board/baseline/README.md`.

### Data

- **V39:**
  - `adp_snapshot.avg_cost` and `percent_drafted`
  - `draft.budget`
  - `draft_pick.amount`, `player_name` and `sleeper_player_id`
  - a new `adp_unmatched` table
- **Sleeper capture:** dynasty and redraft ADP are read from the raw season-projection payload. A capture is refused unless it's for the page's season.
- **Yahoo:** `pub-api-ro` is called server-side, since it has no CORS.
- **Daily refresh:** two new best-effort steps, `ADP_CAPTURE` and `YAHOO_ADP`.

### Process

- **Spec, plan and plan review:** 17 findings.
- **Tasks and analyze.**
- **Build:** Sonnet.
- **Code review:** 13 findings, all fixed.
- **Live verification:** found and fixed 4 more.
  1. The season rule followed a stray season-2099 IT league.
  2. The `color-mix in oklch` tints rendered blue and purple instead of green and red.
  3. The legend wrapped wrong.
  4. The phone budget grid ran one card per row.

All the docs are in `specs/046-adp-compare-auction-board/`.

### Verified

- **Backend:** 2,365 tests, 6 skipped (the known spec-041 ones), 0 failed.
- **Web:** 2,496 passed, `tsc` clean, build OK.
- **Live,** on a scratch DB, against this branch's own servers:
  - The real auction ingests as 230 sales, Σ $1,982, budget $200, and $18 left across teams, which matches Sleeper.
  - 817 dynasty, 903 redraft and 185 Yahoo ADPs are captured, with 185/185 Yahoo players matched.
  - The refusals return 409. The routing redirects work.
  - The page was checked at desktop and 375px.
- **Sale-to-screen latency:** median 1.55s, max 2.15s over 12 sales, through a stub Sleeper.

**Not covered:** a real in-progress Sleeper auction, production, and an ESPN NBA league's page (covered by IT only).

### Post-deploy (required)

Run `POST /api/ingest/all/1415422393706455040` with the admin token **after V39 and this code are live**, and never during a live draft (see HANDOFF.md). Until then the completed auction has no stored prices.

Also unchecked: whether production ever ingested Legacy Ball Knowers before this. If it did, production NBA profiles are polluted until this ships and the re-ingest runs.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
