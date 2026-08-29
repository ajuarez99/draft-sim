# Borrowed drafts and variable league size

Design note, 2026-08-29. **Nothing built.** Recorded so tomorrow starts from the
reasoning rather than rebuilding it.

Two topics, one spine. Widening the per-manager sample with borrowed drafts and
letting the user mock an 8-to-16-team draft both fail the same way: anything keyed on
absolute round number or absolute pick number breaks the moment team count varies.
The normalization work is shared — do it once and both fall out.

## The idea

A leaguemate is in more leagues than yours. Sleeper will hand you every draft they
have been in, for any sport and season, without auth. Ingest those and a manager's
history goes from ~15 picks (one shared league) to potentially 50+, which is the
difference between shrinkage flattening everyone into the league average and an
estimate that is actually theirs.

    GET /v1/user/{username_or_id}                  -> user_id
    GET /v1/user/{user_id}/drafts/nfl/{season}     -> every draft, that season
    GET /v1/draft/{draft_id}                       -> settings, draft_order
    GET /v1/draft/{draft_id}/picks                 -> picks, picked_by, draft_slot

**Verified from Sleeper's docs, not yet called from this project.** The drafts-for-user
endpoint returns objects carrying `type`, `status`, `settings` (teams, rounds, slot
counts) and `metadata.scoring_type`, so filtering happens before any picks are pulled.

## Why the current season is the valuable half

2026 drafts from a manager's *other* leagues are drawn from the same ADP landscape,
the same injury news, and the same player opinions as the draft being simulated. The
contemporaneous board already exists — it is the one the tool built. Compare that to
2025 picks, which are already excluded from reach fitting for exactly the reason that
no contemporaneous board exists for them (`HANDOFF.md`, known warts).

So the ordering is: same-season borrowed drafts first, prior-season borrowed drafts
second and probably down-weighted, and 2025 shared-league picks stay where they are.

## Verified this session: one real manager's actual numbers

Allan asked, looking at the live UI, whether Bartner (fantasy(heart) slot 1)
really has no draft history — `draftsObserved: 0` looked suspicious for someone
he knows is in multiple leagues. Checked directly against Sleeper's API rather
than guessed: `GET /v1/user/670342422659690496/drafts/nfl/2026` and `/2025`.

**He has 55 drafts across the two seasons (17 + 38). Zero are usable by this
doc's own filters.** Every single one is `linear` (not snake) or carries
`dynasty_2qb` / `idp` in `metadata.scoring_type` — auction/linear format,
superflex, or individual-defensive-player leagues. This doc's filter table
already says to drop all of those, for the reasons already given there (a
2QB league rewrites rounds 1-4 entirely; linear isn't snake; IDP is a
different roster shape this project doesn't model). `draftsObserved: 0` for
Bartner is correct given the current 4 leagues, and would *still* be 0 even
with the borrowed-drafts idea fully built — not because the idea doesn't work,
but because this particular manager's other leagues happen to be the wrong
kind, this year.

**Worth knowing before selling this idea on Bartner specifically: it won't
help him.** It may still help others — this was one manager, checked because
he was the one Allan asked about, not a survey. The volume-check script in
"Step zero" below still needs to run across all 13 leaguemates before knowing
whether the idea is worth 40-60 picks per manager or 3.

(Also worth noting since it wasn't obvious going in: `scoring_type` in the
drafts-for-user response names dynasty/2qb/idp status directly, which makes
filtering cheaper than expected — no need to cross-reference `settings.type`
against every draft to know a league is out of scope.)

## Step zero, before anything is designed around this

A ~30 line script. Loop the 13 leaguemate user IDs through
`/user/{id}/drafts/nfl/2026` and `/2025`, apply the filters below, and count what
survives per manager.

The whole idea is worth building if that number is 40 or 60. It is worth nothing if
this league is casuals with one league each and the number is 3. Nobody has checked.
Run it first; it costs an hour and it decides everything downstream.

Print per manager: total drafts, survivors, picks contributed, and the reason each
rejected draft was rejected — the rejection histogram is itself useful.

## Filters

A draft from a different format is not the same process and pooling it is worse than
having no data, because it looks like data.

| Condition | Action | Why |
|---|---|---|
| `type != "snake"` | drop | auction and linear are different games |
| `settings.type` in (1, 2) | drop | keeper / dynasty, out of scope, distorts the pool |
| `status != "complete"` | drop | partial boards bias late rounds |
| Superflex / 2QB in `roster_positions` | drop | rewrites rounds 1–4 entirely |
| Best ball | drop, or tag and down-weight | no waivers changes how you draft depth |
| `teams` differs from 14 | keep, normalize | see below |
| PPR vs half vs standard | keep, tag | moves TE and receiving-back value |
| Own league vs borrowed | keep, tag | see weighting note |

`settings.type` values are worth confirming against a live response rather than
trusting this table; the docs are thin on it.

## What to record per pick

Position alone is the least useful field here — it is the only one that survives
without context, and it is also the one the model already smooths into near-nothing
(`alpha = 8` on ~360 picks). The fields that make a borrowed pick usable:

**Identity and placement**

    draft_id, pick_no, round, draft_slot, picked_by (user_id), player_id

**Board context at the moment of the pick** — this is the whole point

    board_position       player's rank on the contemporaneous board for that draft
    board_rank_pct       board_position / pool size, so it survives team-count changes
    pick_pct             pick_no / total_picks, likewise

Reach must be computed in board-rank space, not against raw `pick_no`. Pick 30 in a
10-team league is round 3; in a 14-team league it is round 3 as well but a very
different board. `boardPosition - pickNumber` (the code's sign convention, positive =
reach) is only comparable across formats once both terms are normalized.

**Derivable from the pick list, but store it if the recompute is annoying**

    roster_state_at_pick   what slots were already filled
    last_5_positions       run context, feeds runSensitivity
    top_n_available        needed to ask "did they take BPA or deviate"

`top_n_available` is what turns a pick into evidence. "Took a WR in round 4" says
almost nothing. "Took WR7 with RB4 sitting there four spots above him on the board"
is the observation the scorer is trying to reproduce.

**Format tags, carried onto every pick**

    teams, scoring_type, is_best_ball, is_own_league, season

## Two confounds that will bite

**Round index does not transfer.** Round 3 is picks 21–30 in a 10-team and 29–42 in a
14-team. Positional priors keyed on round number silently pool different parts of the
board. Key them on `pick_pct` bucket, or on board-rank bucket, and derive the round
display separately.

**Slot confounds positional tilt, and the confound decays with round.** Pick 4 and
pick 12 are looking at genuinely different boards in round 1 — the elite tier is being
consumed in front of you and the value gradient is steep, so "took RB in round 1" from
slot 4 and from slot 12 are not the same observation. By round 8 the tiers are wide
flat bands and the two menus are close to interchangeable, so the same pick is much
closer to revealed preference.

Two knock-on effects:

- The picks that feel most diagnostic of a manager's strategy (rounds 1–3) are the
  ones most contaminated by where they sat. The picks that feel like filler are the
  cleanest signal about what they actually like.
- The turn is a separate slot effect that does *not* decay the same way. Slot 12 of 12
  picks back-to-back every round and can pair positions; slot 1 never can and eats the
  longest gaps. That is a structural strategy difference, not just a different menu,
  and it persists deep into the draft.

**The fix is to condition on the menu, not on the slot.** `top_n_available` is already
in the record-per-pick list above, and once tilt is fit as "given this board, what did
they take" rather than "what positions appear in their round 3", most of the slot
effect dissolves on its own — the steep-board rounds are exactly where the menu term
does the most work. Conditioning on slot directly is the worse version: it needs a
parameter per slot per round on data that cannot support it.

**This is not only a borrowed-drafts problem.** The current league-average positional
prior is fit on ~360 picks from fourteen managers sitting in fourteen different slots.
Slot effects are already smeared into what the model presents as "how drafters behave
in round 3", and that average is the shrinkage target, so it propagates into every
seat. Worth checking on the existing data before any borrowed draft arrives: bucket the
current picks by slot and see how much round-1-through-3 positional distribution varies
across buckets. If it varies a lot, the baseline itself needs the menu conditioning,
independent of this whole idea.

## Weighting

A borrowed draft is weaker evidence than a shared-league one. Someone drafting in the
league with their friends and money in it is plausibly not the same drafter as in a
random public 12-teamer. Prior-season borrowed drafts are weaker still.

    w_own_current   1.0
    w_borrowed      ?      current season
    w_borrowed_prior ?     prior season

These are arbitrary. Per the project convention they go in `weights.yml` declared as
arbitrary and tunable, not baked into `ProfileService`. The shrinkage denominator
becomes a weighted count rather than a draft count, and `drafts_observed` should
probably become two numbers — own and borrowed — so the confidence panel is not
claiming 60 picks of evidence when 45 of them are borrowed.

## Schema

- `draft.league_id` is an FK, so borrowed leagues need `league` rows. Fine — you want
  `settings_json` anyway, it is what the filters read.
- Flag on `league` or `draft` for own vs borrowed. Cheaper on `league`.
- `manager_profile.drafts_observed` splits into own / borrowed counts.
- **This is a V2 migration if `bootRun` has succeeded by then.** Flyway will have
  recorded V1's checksum. Do not fold it into `V1__init.sql` the way the tendencies
  columns were.

## Traps

- `picked_by` can be an empty string on autopicked or commissioner-entered picks.
  Those are not the manager's behaviour. Drop them, and count how many you dropped —
  a manager whose picks are 60% autopick is not giving you a profile.
- Picks carry `is_keeper`. Should be null everywhere after the keeper filter; assert
  it rather than assume it.
- `draft_order` maps user_id -> slot and is null on some drafts. Picks also carry
  `draft_slot`, which is the more reliable path.
- Rate limit is ~1000 calls/min. Thirteen managers x two seasons x (1 draft list +
  2 calls per surviving draft) is nowhere near it, but a snowball crawl outward
  through other users would be.
- The borrowed drafts have no `adp_snapshot` history of their own. Same-season ones
  ride on the 2026 board. Prior-season ones have the same missing-board problem as
  the 2025 shared draft, which is the real reason to down-weight them.

## Order

1. Volume-check script. Everything borrowed is conditional on it.
2. **Normalization pass — do this first regardless of the volume check.** Move
   positional priors, K/DEF gating, reach bias and the run window off absolute round
   and pick numbers onto `pick_pct` / board-rank space. This is the shared prerequisite
   for both halves of the doc and it is a fix to existing behaviour on its own.
3. Sim-against-a-config API change, explicit seat assignment, 8–16 validation, odd-size
   snake tests. Ship variable sizing here; it does not depend on any borrowed data.
4. Ingest path for borrowed drafts, filters, format tags, own/borrowed flag.
5. Refit reach bias on the widened sample.
6. Weighted shrinkage; split `drafts_observed`; surface both in `/api/managers`.
7. Menu-conditioned positional tilt — only if step 5 looks sane.

## Variable league size — 8 to 16 team mocks

The user should be able to run a mock at any team count from 8 to 16, independent of
any league they are actually in. Two reasons it belongs next to the borrowed-drafts
work: it needs the same normalization, and it is the difference between a tool that
simulates *your* draft and a tool you can point at any draft you are about to enter.

### What actually has to change

**Simulate against a config, not a league row.** `POST /api/sims` currently takes a
`leagueId` and reads settings off it. It needs to accept an explicit shape — teams,
rounds, roster slots, scoring — with a stored league as a convenience preset that
populates those fields. Otherwise you can only mock leagues already in the database.
Validate `mySlot` in 1..teams and `teams * rounds` against board depth.

**Seat assignment becomes a first-class input.** At 16 teams you have more seats than
modeled managers; at 8 you have fewer. The request needs a slot-to-seat map where each
seat is either a known `managerId` or a league-average bot. This is already half-built
— fantasy(heart) is fourteen otherwise-identical league-average bots — but it is
implicit rather than something the caller controls.

**Board depth stops being generous.** 16 teams x 15 rounds is 240 picks. The board
needs at least that many entries that are ordered sensibly, not just the top 100 that
matter in a 14-teamer. This makes the real-ADP import matter more than it already did,
since `search_rank` degrades badly in the deep end.

**Anything keyed on round index has to move to `pick_pct`.** Same argument as the
borrowed-drafts section, and it bites harder here because it is not a data-pooling
question but a correctness one:

- Positional priors. Round 3 is picks 17–24 at 8 teams and 33–48 at 16. Applying
  14-team round-3 behaviour to an 8-team round 3 attributes late-second-round
  behaviour to what is really early-third-round board position.
- K/DEF gating. The engine gates these by round. At 8 teams a fixed round threshold
  gates them far later in board terms than intended; at 16, far earlier. Gate on
  rounds-remaining or `pick_pct`.
- Reach bias. "Reaches 8 picks early" is not a stable quantity across sizes — half a
  round is 4 picks at 8 teams and 8 at 16. Reach is more naturally expressed in
  rounds-equivalent or board-rank percentage and converted to picks at sim time. This
  is a fix to the existing fitted parameter, not only a borrowed-drafts concern.
- Run pressure window. A fixed "last 5 picks" covers 62% of a round at 8 teams and 31%
  at 16. Scale the window with team count — some fraction of a round, not a constant.

**Snake mechanics.** Team count is already a parameter of the snake order and the
existing round-trip assertions should cover odd counts for free, but add 9, 11, 13, 15
to the test matrix explicitly — off-by-one bugs in snake code love odd sizes. Third
round reversal is a Sleeper option and is *not* in scope; if the seam is cheap, leave
a hook, otherwise ignore it.

### Second-order effects worth knowing before the UI implies otherwise

Team count changes the draft, not just its length. At 8 teams every roster is strong,
waiver replacement is deep, and reaching is cheap because the player you want will
often still be there. At 16 the starting-lineup scarcity term dominates and runs are
sharper because more seats react between your turns. The engine will produce this
behaviour only to the extent that `rosterNeed` and `runPressure` are genuinely
responsive to team count rather than tuned around 14. Worth an explicit sanity run at
8, 12, and 16 with the same board, comparing round-1 modal probabilities and how far
QBs and TEs slide. If the three boards look the same shape, something is keyed on a
constant that should be a function of size.

The per-manager profiles were fit almost entirely on 12- and 14-team drafts. Applying
them at 8 or 16 is extrapolation. It is defensible extrapolation once everything is in
normalized space, but the confidence panel should not present a profile as equally
trustworthy at 16 teams as at 14. A note on the seat card is enough.



This is a narrower and much more tractable version of open question #2 in
`draft-simulator-plan.md` ("can public Sleeper drafts be enumerated at volume").
It does not need enumeration at all — the user IDs are already known. If it works, the
same ingest path is most of what a wider crawl would need, and the archetype-clustering
idea in the affinity note stops being blocked on an unanswered question.
