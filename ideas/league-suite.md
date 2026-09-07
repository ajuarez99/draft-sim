# A full league suite, built on top of draft-sim

Design note, 2026-09-07. Nothing here is built. Recorded so the reasoning does not
have to be redone.

## The pitch

draft-sim currently answers one question: "what would this draft look like." The
bigger idea is to grow it into the thing a league actually opens all season --
history and polls first, with room to keep adding once those exist:

- **League history.** Standings and records across seasons, not just the current
  one. Past drafts already ingested (`draft` table) are the obvious seed --
  final rosters, who reached, who got value, per the same `valueDelta` /
  `positionalPrior` math the simulator already uses. A "how did last year's mock
  compare to what actually happened" view falls out of data already in the
  database with no new ingest.
- **Polls.** Weekly power rankings, prediction polls, "grade this draft" votes.
  This is the one genuinely new category -- it needs manager-facing input, not
  just Sleeper ingest, which means auth-for-humans (see Open questions) rather
  than the single-user assumption the app runs on today.
- **Whatever else a league wants**, once the first two exist and there's a
  container to hang it on: side bets, trade history, a manager leaderboard for
  "biggest reach of the year." Deliberately not speccing these now -- see
  ideas/README's "parked, not yet written up" list for the pattern of writing
  these up only when one is actually about to get built.

## Why this is bigger than it looks

Everything shipped so far is single-user: Allan runs ingest, Allan looks at the
board, Allan runs mocks. A league suite that other managers open means:

- **Multi-user auth**, not the current binary `API_TOKEN` on/off switch. Each
  manager needs their own identity to vote in a poll or see "your" history.
- **Write paths with real users behind them.** Everything today is read-mostly
  (ingest writes, humans only read) except mock-draft sessions, which are
  already scoped to one browser session with no login. Polls are the first
  feature where arbitrary league members submit data that has to be
  attributed and can't be re-run like a sim.
- **A reason for 13 other people to open the app at all.** Right now the only
  user is Allan. Nothing about the data model says this doesn't work for a
  whole league, but nothing about the product does either -- there's no invite
  flow, no per-manager view, no notion of "your" anything.

None of this is a reason not to do it. It's the reason it's an idea and not a
roadmap item: it's a different kind of app (multi-user, always-on, social)
layered on top of a simulator (single-user, on-demand, analytical), and that
seam should be crossed deliberately, not backed into feature by feature.

## What already exists that this would build on

- `draft`, `board`, and manager-profile tables already hold the history a
  "league history" view would read. No new ingest for the first version --
  just new read paths and UI over data that's already there.
- The manager-tendencies work ([[project_manager_tendencies_and_mock_seating]]
  memory) already put real managers, not just bots, into the product. History
  and polls are both "more surface area for the real managers," same direction.
- `SportRules` seam exists for basketball ([[project_basketball_is_the_real_target]]
  memory notes football is the dev vehicle, not the end goal) -- a league
  suite is sport-agnostic in a way the simulator mostly is too, worth keeping
  in mind if this gets built before basketball support does.

## Open questions

1. **Auth model.** Sleeper OAuth (if it exists) vs. a bespoke login vs.
   something lighter (a per-manager magic link, no real accounts). Unresearched.
2. **Where polls live relative to Sleeper.** Sleeper already has some social
   features (league chat). Is this additive or competing with something
   managers already use and ignore?
3. **Scope of "history."** Multi-season standings need Sleeper league-history
   ingest beyond what `league-chain crawl` currently pulls for draft purposes --
   unverified whether that's a small extension or a new ingest path.

## Recommendation

Don't start with infrastructure. If this gets built, start with the read-only
slice that needs no new auth: a league history view over drafts already
ingested. That validates whether anyone other than Allan would actually open
this before multi-user auth and polls get built for an audience that turns out
not to want them.
