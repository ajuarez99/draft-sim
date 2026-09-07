# ideas

Things that are **not planned work**. Speculative directions, recorded so the
reasoning does not have to be redone and so an appealing-but-wrong version does not
get built by accident later.

Nothing in this folder is committed to, scheduled, or half-built. Most of it is
blocked on data the league does not yet have.

## What goes where

    HANDOFF.md      current state and what to do next
    DEPLOY.md       how to deploy, when that happens
    ideas/          this folder: maybe someday, and why
    README.md       how to run it

If something moves from "maybe" to "next", it leaves this folder and goes into
`HANDOFF.md`.

## Contents

- **`ad-hoc-league-sizing.md`** — supporting a real league whose team count isn't
  one of the four already built (8/10/12/14). Demoted from the roadmap: none of
  Allan's actual leagues need it.

## Left this folder (promoted to plans, 2026-09-07)

Both went to `claude/` once the assumption each was blocked on got **measured**
instead of argued about. Neither is built; they are plans now, not maybes.

- **`claude/league-suite.md`** — league history + polls. Measured: Sleeper has no
  OAuth at all (their docs: the API is read-only and performs no authentication),
  so multi-user auth is fully bespoke; and every league-history endpoint needed
  for the read-only slice already works unauthenticated, making that half a small
  extension rather than a new ingest path.
- **`claude/player-affinity.md`** — player similarity, note extraction, archetypes.
  Measured: public Sleeper drafts *can* be enumerated at volume without auth
  (~6.5× league growth per crawl level, 214 calls for two levels), so the
  archetype half is unblocked — but a seed league's own managers gain a median of
  **one** extra draft, which undercuts the borrow-individual-history idea in
  `claude/borrowed-drafts.md`. The feasibility scripts live in `claude/scripts/`.

## Also parked, not yet written up

Carried over from the design doc's deferred list — each needs several more seasons
of history before it is anything but noise:

- Draft archetype classification (zero-RB, hero-RB, and friends) — now written up
  as step 4 of `claude/player-affinity.md`, and no longer blocked on the corpus
  question, though still the largest piece in that doc
- Handcuff and stacking detection
- Tier discipline — does a manager reach across a tier break or wait
- Basketball as a second `SportRules` implementation (the seam exists; nothing else does)
- Auction drafts

Backtesting is a different category: it was **decided against**, not deferred for
lack of interest. See the design doc. Do not relitigate it.
