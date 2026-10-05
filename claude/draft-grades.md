# Draft grades: how each team's draft actually played out

Status: **design, not built.** 2026-10-05. Part of
[competitor-gap-research.md](competitor-gap-research.md) (gap #1).

> **Amended 2026-10-05, after re-checking against `origin/main`.** This doc was
> first written against a stale `009` checkout. Main had since shipped spec 013
> US6, which includes a **steals & reaches** view on finished boards
> (`web/src/stealsReaches.ts`, `CompletedDraftBoard.tsx:102`): pick number vs
> `adp_at_time`, with null shown as unknown. It also shipped **letter grades** as
> a hand-set percentile curve (`config/weights.yml:151-161`, `GradeChip`). What
> changed here: "Reach summary" below now reuses that view instead of proposing
> it, and "Letter grades" follows the spec 013 precedent instead of ruling
> letters out. The production-based half, how the picks actually played out,
> is still unbuilt.

Competitors: ffwrapped "Draft Grades", LeagueLogs "Draft Report" (steals,
reaches, picks that shaped each roster), StatChasers report card.

## Why this one first

It is the payoff of a draft simulator. The app predicts drafts, tracks them
live, and fits each manager's reach bias — and then says nothing about how the
draft *turned out*. Every input already exists, it works the same in both
sports, and the 2025 NBA draft has a complete season behind it to test against.

## What's there now

- `draft_pick` (`V1__init.sql:71`): `pick_no`, `round`, `draft_slot`,
  `manager_id`, `player_id`, and `adp_at_time` — the board position on the day.
  Reach (`pick_no` vs `adp_at_time`) is already how `ProfileService` fits reach
  bias.
- `/drafts/:draftId/board` → `CompletedDraftBoard.tsx`, fed by
  `LeagueController.realBoard` (`api/LeagueController.java:299`). Shows the
  picks that happened, plus (spec 013) the steals & reaches view against
  draft-time ADP. **No outcome column**: nothing says how a pick scored.
- **Per-player production, two ways:**
  - `player_game` (V20): raw stat lines per game. NBA 2024 + 2025 complete;
    NFL 2025 complete (26,490 rows) and 2026 to date (local DB, 2026-10-05).
    `GameScoringService.score` (`engine/GameScoringService.java`) turns a stat
    line into league points. **Verified for NBA only** (10/10 and 18/18 weeks
    reconciled, per its javadoc). Not yet checked for NFL stat keys.
  - `roster_week_points.players_points` (V5): Sleeper's own league-scored points
    per player per week — but only for weeks the player sat on a roster in this
    league.
- `adp_at_time` is null for drafts older than the ~4-day ADP snapshot window
  (memory: adp_at_time footgun). Reach is unknowable for those, permanently.
- **No simulation result is persisted.** `/api/sims` results are in-memory. So
  "what we predicted before the draft" doesn't exist for any past draft.

## Proposed design

### Two numbers per pick, never blended

1. **Production** — the player's league-scored points this season, from
   `player_game` × `GameScoringService`, regardless of who rostered him. That
   answers "was this a good pick?"
2. **Points that counted for you** — from `players_points` where he was in the
   drafting roster's starters (`roster_week_points.starters`, V18). That answers
   "did the pick help *you*?" A player who got dropped in week 3 and blew up
   elsewhere is a great pick with near-zero points for you. Both are true, and
   that is the story.

Each column is labelled with what it measures and the weeks it covers (the
label-the-axis rule).

### Value over slot

A pick is judged against **what that draft offered at that point**, not a
league-independent curve:

    slot_baseline(pick_no) = median production of the picks in this draft
                             within ±k of pick_no  (k = teams/2, hand-set)
    value_over_slot        = production − slot_baseline

Why the draft's own neighbors and not a fitted curve: one draft is 100–200
points of data, and a fitted points-by-pick curve on that is mostly noise. The
neighbor median is crude and says exactly what it is. `k` is hand-set and goes
in `config/weights.yml` labelled as such.

Positional version as a second view: rank among same-position picks in this
draft (the 3rd RB taken finished as the 9th-best drafted RB).

### Per-team

- **Draft value** = sum of `value_over_slot` across the team's picks.
- **Best pick / worst pick** for the team.
- **Reach summary**: reuse `stealsReaches.ts`'s `pickValue`. Don't recompute
  ADP deltas server-side; that would be a second implementation of a rule that
  already has one. Production-based value and ADP-based reach are different
  questions and sit in different columns.
- League-wide: **steals of the draft** (top 5 value over slot) and **busts**
  (bottom 5), each with the round it went in.

### Letter grades: reuse spec 013's

Spec 013 already established the pattern: a letter is a percentile read off a
hand-set curve labelled ARBITRARY in `config/weights.yml:151`, drawn only beside
the number it grades (`GradeChip`), with one "early" caveat per column. Team
draft value gets a grade the same way, from the same `grades` block. Don't add a
second curve.

### Early-season honesty

Every number carries `weeksCounted`, as `TransactionAnalysisService.MovedPlayer`
already does (`engine/TransactionAnalysisService.java:55`). Before week 4, the
page leads with "early — N weeks played" rather than presenting it as a verdict.

### Surface

- `GET /api/drafts/{sleeperDraftId}/grades` → `DraftGradesService` (engine).
- A "How it played out" view on `/drafts/:draftId/board`: a column per pick plus
  a team summary strip. Not a new route — it's the same draft.
- `web/src/api.ts` types added in the same change.

## Not building

- **Predicted-vs-actual for past drafts.** No pre-draft sim was stored, and
  re-running one now would fit profiles on the very draft being judged
  (leakage). If wanted, the forward-looking fix is to persist a pre-draft
  snapshot when a draft goes live — a separate, small feature.
- **Mock-draft grades.** A mock has no season behind it.
- **Grading keepers/auctions.** No league has them (competitor-gap-research.md).

## Acceptance criteria

1. NFL scoring check before anything else: `GameScoringService` on `player_game`
   reproduces `players_points` for ≥10 sampled starter-weeks in the 2025
   "(Foot) Ball Knowers" league. If it doesn't, stop and fix that first — that is
   recurring bug class #8 waiting to happen.
2. The 2025 NBA "Ball Knowers" draft: every pick has a production number; the
   page loads in the real UI against the real backend.
3. A preference-ordering test (lessons bug class #1): a synthetic draft where
   pick 10 outscores picks 1–9 must rank pick 10 as the top steal.
4. A pick whose `adp_at_time` is null shows reach as unknown, not 0.
5. A player who changed rosters shows different "production" and "counted for
   you" numbers, with both labelled.
