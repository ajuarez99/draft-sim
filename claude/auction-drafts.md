# Auction drafts

Status: **reconciliation doc. Recommendation: don't build.** 2026-10-05.
Gap #12 in [competitor-gap-research.md](competitor-gap-research.md).

Competitors: Hashtag Basketball "Auction Values", FanScout "ADP & Auction
Values", DraftSharks War Room (auction mode), and Sleeper itself supports
auction drafts.

## Why not now: no league runs one (measured)

Local DB, 2026-10-05: `draft.draft_type` is `snake` for every ingested draft,
both sports, all seasons. The whole engine, including `DraftSimulator`,
`PickDecider`, `PickScorer`, the manager profiles (reach bias = picks vs ADP)
and `MockDraftEngine`, models *the order players come off the board*. An
auction has no order to model. It has nominations and bids.

## What an auction version would actually take

This is not a feature on top of the simulator. It's a second simulator.

1. **Values:** a dollar value per player. Convention is value over replacement
   scaled to the league budget. That needs a per-player season value
   ([projection-tools.md](projection-tools.md)) plus a replacement level per
   position, from `roster_positions`.
2. **Manager models:** nomination strategy and bidding aggressiveness per
   manager. Fitting those needs auction history, and there is none (zero
   auction drafts ingested). With thin data being this project's central
   problem, a fitted auction profile from zero drafts would be pure prior.
   That's worse than the snake case, not equal to it.
3. **Engine:** a nomination/bid loop with budget constraints instead of a snake
   order. The SSE/live-tracking pieces could be reused. `PickDecider` couldn't.
4. **Ingest:** Sleeper auction picks carry `metadata.amount`. `draft_pick` would
   need a price column.

## Decision

Don't build unless a real league switches to auction. If one does, start with
item 1 (auction values from projections). It's useful alone, and it doesn't
pretend to model anyone.

## Acceptance criteria (if ever built)

1. Values sum to the league's total budget minus minimum bids (assert).
2. A player's value rises with scarcity: removing all but one top-tier C in a
   test pool must raise that C's value (preference-ordering test).
