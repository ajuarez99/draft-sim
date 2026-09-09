# Merge review — `ball-knowers-multi-sport` → `main`

Written 2026-09-09, after the branch's last phase (6b) landed. Everything below
was **executed against the running app and the real database**, not read off the
diff. Where a finding has a repro, the repro is included.

**Verdict: do not merge as-is. Two guards plus one small fix — under a day — and
it is ready.** The architecture is right and does not need a refactor. What it
needs is for its own stated boundaries to be enforced in code instead of
described in comments.

---

## Status — updated 2026-09-09, same day

| item | state |
|---|---|
| B1 — NBA fork builds a football room | **open** (the sim/mock room; deliberately left while it is being tested) |
| B2 — live poller writes null picks | **fixed**, `9c655da` |
| S1 — manager history is football-only | **fixed**, `9c655da` |
| S2 — football-shaped pages on an NBA card | **fixed**, `d8092f9` |
| S3 — untested frontend mirrors | **fixed**, `d8092f9` |

**B2's fix came with a measurement that makes the bug worse than this document
first said.** Replayed against the 168 real picks of the completed 2025 NBA
draft, resolving them through the football id map — which is exactly what the
old code did — would have produced:

    168 picks
    103 written as player_id = null
     65 written as a REAL, WRONG NFL PLAYER

The 65 are the dangerous half and were not anticipated anywhere above. Sleeper's
player-id namespaces are per-sport and **753 ids belong to a player in both**, so
a miss is not the only failure mode: a hit on the wrong sport is silent, survives
every downstream check, and feeds the profile fit. It is also why the obvious
cheap fix — one sport-less combined id map — was rejected: it would have made all
168 resolve to *something*.

Verified after the fix by flipping the 2025 NBA draft to `drafting` and running
one real poll tick against Sleeper: 168 of 168 picks still resolved to the right
players, zero rows differing from a snapshot taken beforehand.

**B2 also turned out to be two bugs on one screen.** `onTheClockSlot` was plain
snake, so the live room named the wrong manager as being on the clock from round
3 on for any draft with a reversal round — which the 2026 NBA draft has. Stored
picks were never affected: `PickMapper` reads Sleeper's own `draft_slot`.

**S1 verified live** on manager 48, who drafts in both leagues: the endpoint now
answers with football (reach 10.77 over 15 scored picks, FITTED) *and* basketball
(no reach signal, 0 scored, NEUTRAL, real positional tilt) instead of silently
publishing the football numbers as the answer.

Football's two baselines are still bit-identical after all of it.

---

## What is good, so the list below is read in proportion

- **`Sport` is a real runtime value.** `SportRulesRegistry` makes a second sport
  an implementation rather than a startup failure; `LeagueSettings` carries the
  sport and both rules and scoring resolve from it. This is the part that would
  have been expensive to get wrong, and it is not wrong.
- **Football did not move.** Both captured baselines are bit-identical after
  every phase including 6b, re-checked with all the NBA data sitting in the same
  database. `claude/scripts/football-parity-hash.py`.
- **The contamination fix is proven against real data**, not a fixture —
  `SportContaminationIT` was written failing first.
- **Basketball's engine is fast.** The plan doc warned that
  `BasketballRules.prepareLineup`'s ~32 array allocations per call would be "the
  first thing they notice". Measured 2026-09-09: a genuine cold-start sim of the
  2026 NBA draft (12 teams, 14 rounds, 168 picks) runs **500 iterations in 0.37s
  and 2000 in 1.42s**. That worry can be closed.
- 296 backend tests, 0 skipped against real Postgres; 80 frontend tests.

## The one structural problem

**Every football-only boundary on this branch is documented in a comment rather
than enforced by a guard.** Exactly two places in `backend/src/main` actually
refuse a non-NFL sport:

    BoardService.java:221     if (sport != Sport.NFL) return Map.of();
    FfcAdpService.java:57     if (sport != Sport.NFL) { ... }

Everywhere else, "the mock draft room is football-only by the Non-goals" and
"live draft polling for basketball is a Non-goal" are accurate statements about
*intent* sitting above code that a user can still reach with a basketball
league. The picker renders one mixed list with no switcher — which is the right
design, and also what puts every football-only surface one click from an NBA
league card.

One root cause, three exits. None needs an architectural change; each needs a
guard where the comment already is.

---

## Blockers

### B1 — Forking an NBA live draft produces a basketball room full of football players

`MockDraftService.createSessionFromDraft` derives the sport correctly
(`settings.sport()`, so the *creating* request builds an NBA board and NBA
profiles). But `MockDraftRepository.SessionRow` has no sport, so every later
request rebuilds the context through `buildContext`, which hardcodes
`Sport.NFL` — as does `submitPick`'s player lookup
(`players.idsBySleeperId(Sport.NFL)`).

Reproduced live (draft flipped to `drafting`, forked, then restored):

    POST /api/mocks/from-draft/1339351318128517120?mySlot=5

    rosterPositions: ["PG","SG","G","SF","PF","F","C","UTIL","UTIL","BN",...]
    available:       ["Jahmyr Gibbs (RB)", "Bijan Robinson (RB)",
                      "Ja'Marr Chase (WR)", "Christian McCaffrey (RB)", ...]
    picks 1-4:       player: null

A basketball lineup asking to be filled with football players, and the picks
that already happened rendered blank. It does not throw — it answers wrong. The
first user pick then fails with "unknown sleeperPlayerId", which is a confusing
message for what actually went wrong.

Reachable from the live draft room's **"Continue as a mock"** button, enabled
whenever `status === 'drafting'` — i.e. exactly on NBA draft night.

**Fix:** refuse a non-NFL sport in `createSessionFromDraft`, with a real
message. Two lines. The alternative — persisting sport on the session row and
threading it through `buildContext`/`submitPick` — is the right fix whenever the
mock room stops being football-only, and is not worth doing before then.
`mock_draft_session.sport` already exists (V6), written and read by nothing, so
the column is waiting for it.

### B2 — The live poller writes null picks for an NBA draft

`LiveDraftPoller.pollOnce` resolves picks through
`players.idsBySleeperId(Sport.NFL)`. `PickMapper` never guesses, so every NBA
Sleeper player id misses and the row is written with `player_id = null`.

While the draft is `pre_draft` the poller no-ops on picks, so nothing is wrong
today. The moment Sleeper flips the 2026 NBA draft to `drafting`, the poller
writes 168 rows of nulls into `draft_pick` — picks the board and the engine both
ignore, which then feed the profile fit as nothing.

The draft is **already registered for polling**:

    POST /api/drafts/1339351318128517120/track
    → {"tracking":true,"alreadyTracking":true,"status":"pre_draft","seatsMapped":12}

This is the failure that fires on the one event this project exists for.
`LeagueController.recordPick` already refuses to write a null `player_id` for
exactly this reason ("would look like a successful pick in the UI while
producing a pick row the engine and the board both ignore"). The poller should
hold the same line.

**Fix:** resolve the sport in the poller (one `leagues.byId` lookup — the class
comment correctly notes it does not otherwise need one, which is a fair cost for
not corrupting a draft), or refuse to track a non-NFL draft in `/track` and say
so in the picker. The second is smaller and matches the declared Non-goal; the
first is what NBA draft night actually needs.

---

## Should-fix in the same pass

### S1 — `/api/managers/{id}/history` reports football numbers for any manager

`LeagueHistoryController:97` calls `profiles.fit(Sport.NFL)` unconditionally and
puts the result under `draftHistory` (reach bias, positional tilt, provenance).
The page is linked from every standings row, including an NBA league's. Ten of
the twelve Ball Knowers managers are the same Sleeper id in both leagues, so this
is not an edge case — it is the same cross-sport mixing Phase 6b just removed
from the tendencies write path, in the one endpoint that was missed.

Harmless *today* only because no NBA standings are ingested, so those pages are
empty. One press of "Load past seasons" on the NBA league changes that.

**Fix:** take a `sport` param, or derive it from the league the history row came
from.

---

## Lower priority

### S2 — An NBA league's History and Power rankings are football-shaped

Both endpoints answer `200` with empty data for the NBA league, and `/power`
returns a field literally named `nflState` carrying the NFL week and season.
Cosmetic while empty; wrong once populated. Either hide the two links on a
non-NFL league card (one line in `DraftPicker.tsx`, matching the Non-goals) or
scope the endpoints.

### S3 — Two frontend mirrors of backend rules, one of them untested

The frontend necessarily re-implements rules the backend owns:

| mirror | of | tested |
|---|---|---|
| `web/src/snake.ts` | `DraftSlot.isForward` | yes — added in 6b, pinned to live engine output |
| `web/src/positions.ts` | `Position.forSport` | no |
| `web/src/teamNeeds.ts` `SLOT_ELIGIBILITY` | `FootballRules`/`BasketballRules.isEligible` | no |

`snake.ts` exists *because* the untested version of this pattern already failed
once: `DraftBoard` drew plain snake while the engine reversed round 3, and
nothing caught it. `teamNeeds` is the same shape of risk, one level less
visible. A test per mirror, in the shape `snake.test.ts` uses — asserted against
values the running backend produced, not against a re-derivation.

---

## Not blockers, worth writing down

- **`mock_draft_session.sport` is a dead column.** Added by V6, read and written
  by nothing; V6's own comment admits it was speculative. Leave it — it is what
  B1's real fix will use.
- **Test asymmetry is structural, not incidental.** 14 of 51 backend test files
  mention basketball. That ratio is fine; the branch's own history is the reason
  not to trust it anyway. `PlayerRepository.findAll` parsed positions with an
  NFL-defaulting overload and discarded the entire NBA board on read while 289
  tests stayed green. **A sport with no fixtures is invisible to a suite built
  around the sport that has them.** Live verification stays mandatory here.
- **`@RequestParam(defaultValue = "nfl")` is on seven endpoints**, each a place a
  caller silently gets football. Two of the three bugs found during 6b were
  exactly this. Defensible as a public API default; not defensible as an
  *internal* one. The frontend now passes the sport explicitly everywhere, so
  making the param required on the three manager endpoints — the ones that
  **write** — would cost nothing and close the class.

---

## Recommendation

**Only B1 is left.** Merge after it.

Original recommendation, kept as written: merge after **B1, B2 and S1**. Two guards and one parameter, with a test each.
S1 belongs in the same pass because it is the last instance of the cross-sport
bug class 6b spent its day removing.

Do **not** hold the merge for S2 or S3, and do **not** refactor the sport
plumbing — it is sound. The gap on this branch is not design. It is that
"football-only" was written in prose seven times and in code twice.
