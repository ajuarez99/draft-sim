# Spec 064: Time Machine achievements and levels

**Branch**: `064-time-machine-achievements` (off origin/main ff24e61, which has spec 063)
**Asked for**: Allan, 2026-11-13: "lets work on adding and popping up achievements. one for each win or
getting a crazy pick reach or getting a bad bust like torn acl immediately and level system".
**Direction it follows**: the "ease and fun" note queued in spec 063 (lead with what the fan got right and
what they missed).

## Decisions (Allan's, 2026-11-13, asked in one batch)

1. **Wins** are milestone badges plus playoff/title badges, not a popup per weekly win. Each weekly win gives XP.
2. **XP** comes from results and badges: finishing a game, each weekly win, playoff wins, a title, and each
   badge. Auto-drafted games count the same as hand-picked ones. Levels are cosmetic.
3. **Past games count** for whatever their saved result can prove, granted quietly (no popups). Badges that
   need data the saved result does not hold come only from games finished after this ships.

## What a fan sees

- **US1 Popups.** When a finished game's results appear, each badge unlocked by that game pops up as a toast
  (name, one line saying exactly what earned it, XP). A level-up gets its own toast. Popups show once.
- **US2 This game's XP.** The results page has a "What this game earned" panel: an itemised XP list (game
  finished, N weekly wins, playoff wins, title, each badge) that sums to the total shown.
- **US3 Level and trophy case.** The Time Machine landing page shows the level, its name, XP into the level
  with a labelled progress bar, career totals, and every badge: earned ones with the date and the game that
  earned them, locked ones with what unlocks them.

## Requirements

- **FR-001** Achievements belong to the Sleeper user (`X-Sleeper-User`), across both sports, stored on the
  server. Another user's profile is never readable.
- **FR-002** A game's frozen `result` is not changed and not recomputed. Awards are stored beside it.
- **FR-003** Each game is settled exactly once, however many times or how concurrently it is requested. A
  badge is unlocked at most once per user; the unlocking game is recorded.
- **FR-004** A failure while settling a game must never stop the game from completing or its results from
  showing. The results page renders without the award panel and says the awards could not be loaded.
- **FR-005** Games completed before this ships are settled from the saved result only, and their badges and
  level are marked seen (no popup). The profile says how many games were counted that way.
- **FR-006** Every badge states its rule in plain words and is true to the data. **There is no injury data.**
  The "stopped playing early" badge says the player's last box score came in week N; it never says he was
  hurt. The copy may joke about the feeling, not assert the cause.
- **FR-007** XP values, level thresholds and badge thresholds are hand-set and labelled as such in the code
  (`config/weights.yml` discipline: arbitrary, not principled). They are shown to the fan as numbers, so a
  change to them is visible.
- **FR-008** Old-format results (no `format`: no playoffs) earn weekly-win XP and the result-only badges that
  do not need a bracket. A standings rank of 1 in an old result is not a title.
- **FR-009** Popups are announced politely to screen readers, can be dismissed, do not trap focus, and do not
  animate under `prefers-reduced-motion`. At most three are on screen; the rest queue.
- **FR-010** ABANDONED and DRAFTING games earn nothing.

## Badge catalog (v1)

"Stored" = provable from a saved result, so past games can grant it. "New games" = needs per-pick season
values or box-score weeks, computed only for games finished after this ships. R = number of teams (one round
of picks). All thresholds are hand-set.

| Code | Name | Rule | Source |
|---|---|---|---|
| FIRST_GAME | First trip back | Finish a game | Stored |
| WINS_1 / 10 / 25 / 50 / 100 | First win, 10, 25, 50, 100 wins | Career weekly wins, regular season plus playoffs | Stored |
| PLAYOFFS | Made the bracket | Reach the playoffs | Stored (new format) |
| PLAYOFF_WIN | Survive and advance | Win a playoff game | Stored (new format) |
| CHAMPION | Champion | Win the title | Stored (new format) |
| TITLES_3 | Dynasty | Three titles | Stored (new format) |
| BOTH_SPORTS | Two-sport champion | A title in basketball and one in football | Stored (new format) |
| TOP_SEED | Best record | Finish the regular season first | Stored |
| PERFECT_SEASON | Perfect regular season | No losses or ties in the regular season (at least 10 weeks) | Stored |
| BEAT_HINDSIGHT | Better than hindsight | Outscore the greedy hindsight draft | Stored |
| CLEAN_SHEET | No busts | No pick finished below where it was taken | Stored |
| BIG_BUST | Swing and a miss | A pick in rounds 1-2 finished at least 3R places below his pick number | Stored |
| WOODEN_SPOON | Wooden spoon | Finish last | Stored |
| REACH_HIT | Called it | Took a player at least R picks ahead of his board rank and he finished at or above his pick number in season value | New games |
| REACH_MISS | Galaxy brain | Same reach, and he finished at least 2R places below his pick number | New games |
| LATE_GEM | Late-round gem | A pick in the second half of the draft finished in the top R by season value | New games |
| GONE_EARLY | Gone by week 3 | A pick in rounds 1-3 whose last box score of the real season came in week 3 or earlier (seasons of 10+ weeks) | New games |

Season-value rank is the same one the bust callout already uses: rank among every drafted player by the sum
of weekly lock-in scores (`Comparisons.valueRanks`).

## XP and levels (hand-set)

- Game finished 100; weekly win (regular season or playoff) 10; playoff win +40 on top; title +250.
- Badge XP: 50 (FIRST_GAME, WINS_1, WOODEN_SPOON, BIG_BUST, GONE_EARLY, REACH_MISS), 100 (WINS_10, PLAYOFFS,
  PLAYOFF_WIN, TOP_SEED, CLEAN_SHEET, REACH_HIT, LATE_GEM), 200 (WINS_25, CHAMPION, BEAT_HINDSIGHT), 300
  (WINS_50, PERFECT_SEASON, BOTH_SPORTS), 500 (WINS_100, TITLES_3).
- Level n starts at `100 * n * (n - 1) / 2` total XP (level 2 at 100, 3 at 300, 5 at 1000, 10 at 4500). No cap.
- Level names: 1-2 Rookie, 3-4 Starter, 5-7 Veteran, 8-11 All-Star, 12-16 MVP, 17+ Hall of Famer.

## Not being built, and why

- **Per-weekly-win popups**: Allan chose milestones; a 19-week season would fire ten toasts.
- **Injury labels**: no injury feed exists; a "torn ACL" claim would be invented.
- **Leaderboards / seeing other people's badges**: needs a sharing model the game does not have.
- **Anti-farming for auto-draft**: Allan chose that auto-drafted games count.
- **Mutating or re-scoring stored results** to backfill pick-level badges: results are frozen (spec 053 FR-013),
  and a re-import may have re-keyed players since.
- **Sound**: none.

## Acceptance criteria

- **SC-001** Finishing a new game that earns badges shows one toast per new badge, then never again on reload.
- **SC-002** The game's XP lines sum to the game's XP; the profile's XP equals the sum of every game's XP plus
  every earned badge's XP (amended after review 2026-11-13: the first wording counted badge XP twice).
- **SC-003** Opening the profile twice, or from two tabs at once, does not double-count a game (IT with
  concurrent calls).
- **SC-004** A user with games finished before the migration sees their level and stored badges with no
  popups, and a line saying how many games were counted from saved results.
- **SC-005** Each "New games" rule has a preference-ordering test that pins its direction (a reach that paid
  off earns REACH_HIT and not REACH_MISS; pick 3 finishing 150th is not a hit; a round-6 pick gone in week 2
  does not earn GONE_EARLY).
- **SC-006** Both sports verified live in the browser: NBA and NFL games finished, popups seen, landing page
  correct, phone width checked.

## Amended after review (2026-11-13)

See plan.md "Amended after review". What changes for the fan: reach, bust, late-gem and gone-early **moments
pop every game** they happen (no XP), while the badge of the same name unlocks once (A4); "Called it" needs a
first-half pick that beat its slot by a full round (A5); "Gone by week 3" reads "his last box score in our
data came in week N" (A7); first place in a season with no playoffs is not a title (A8).

## Retuned after measurement (2026-11-13)

Allan asked for three badges to be retuned after verification showed two never firing and one firing in most
football games. The catalog table above is left as first written; these three rows are superseded. All three
are still hand-set. The counts come from the 81 games left in the verification database (42 basketball, 39
football, almost all auto-drafted), counted with SQL over the stored results and pick facts. They are counts
from a small, unrepresentative sample, not rates, and the new rules were not re-run through the live app.

| Code | Was | Fired | Now | Would have fired |
|---|---|---|---|---|
| BIG_BUST "Swing and a miss" | Round 1-2 pick at least 3R places below his pick number | 15 of 41 NBA, 27 of 39 NFL | Round 1-2 pick in the bottom third of all drafted players by season value | 5 of 41 NBA, 7 of 39 NFL |
| BEAT_HINDSIGHT, renamed "Near-perfect draft" | Outscore the greedy hindsight draft | 0 of 81 (best 87.8% NBA, 77.8% NFL) | Score at least 85% of it in basketball, 75% in football | 3 of 42 NBA, 4 of 39 NFL |
| CLEAN_SHEET, renamed "Steady hands" | No pick finished below his slot | 0 of 80 (fewest was 3 picks) | At most two picks finished a full round or more below their slot | 5 of 41 NBA, 2 of 39 NFL |

Consequences:

- The hindsight bar differs by sport because football scores run lower against hindsight in this sample
  (median 68% against basketball's 78%). One shared bar would be out of reach in one sport or routine in the other.
- "Steady hands" now needs every pick's season rank, so it moved from Stored to New games: a game settled from
  its saved result alone cannot earn it.
- "Near-perfect draft" is still Stored, so a past game at or over the bar earns it quietly.
- Badge codes are unchanged. Games already settled keep what they earned; nothing is re-settled.
- The earlier counts in verification.md (13 of 36 and 25 of 36 for the bust) were over the 72 scripted games
  only; the "Fired" column here recounts the old rule over all games with pick facts.
