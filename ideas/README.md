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

- **`player-affinity.md`** — whether manager tendencies and players belong in a
  vector store. Player-to-player similarity from role features is worth building
  and the scorer already reserves a slot for it; a shared text-embedding space for
  notes and players is the appealing wrong answer. Corpus-scale work is blocked on
  an unverified assumption about enumerating public Sleeper drafts.
- **`ad-hoc-league-sizing.md`** — supporting a real league whose team count isn't
  one of the four already built (8/10/12/14). Demoted from the roadmap: none of
  Allan's actual leagues need it.

## Also parked, not yet written up

Carried over from the design doc's deferred list — each needs several more seasons
of history before it is anything but noise:

- Draft archetype classification (zero-RB, hero-RB, and friends)
- Handcuff and stacking detection
- Tier discipline — does a manager reach across a tier break or wait
- Basketball as a second `SportRules` implementation (the seam exists; nothing else does)
- Auction drafts

Backtesting is a different category: it was **decided against**, not deferred for
lack of interest. See the design doc. Do not relitigate it.
