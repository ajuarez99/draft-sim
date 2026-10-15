# Adversarial plan review: spec 034

This review read spec.md, plan.md, research.md, data-model.md, contracts/api.md and
quickstart.md cold on 2026-10-14. It checked their claims against worktree
`034-rivalry-schedule-grid` @ `8346cdf` (code identical to `main`), the local Postgres on
5433, and the public Sleeper API. No code had been written yet.

Every item is labelled **measured** (run today, output quoted), **read** (from code, not
executed) or **inferred**.

## Claims confirmed

| Claim | Where checked | Result |
| --- | --- | --- |
| Playoff start week is in `settings_json` (R1) | `LeagueRepository.playoffFormat:258-275` (read); `league` rows (measured) | ✅ 4/5: 15, 9465/9466: 15, 210/211/212: 20/19/22. All `playoff_teams` 6. Round type 0 except West Coast (1, both seasons) |
| Byes are null-`matchup_id` rows, not missing rows | `league_matchup` playoff weeks of 5, 9466, 211, 212 (measured) | ✅ e.g. 5 wk 15 `1,11,10,2:null`. But see F8: not every null row in a playoff week is a bye |
| 7 bracket matches per finished season, same shape | `winners_bracket` for 1254…, 1262…, 1229… (measured) | ✅ 7 each, `m,r,t1,t2,w,l,p,*_from` |
| Bracket pairs = the non-consolation playoff pairs (R2) | 5 wk 15–17 pairings vs 1254… bracket (measured) | ✅ wk 15 `3v4 8v9` bracket, `5v12 6v7` consolation, and so on. No bracket pair also meets in a consolation game. Single elimination plus placement games can't repeat a pair |
| `PairedGame` carries roster ids, so GameKind can match the bracket | `LeagueMatchupRepository.PairedGame:76-78` (read) | ✅ |
| `SleeperClient.winnersBracket` exists and has no caller | `SleeperClient.java:85`, grep (read) | ✅ |
| `/api/ingest/**` is admin-gated | `WebConfig:31`, `AdminGateInterceptor:12` (read) | ✅ |
| No chain stores two leagues of one season (R3) | recursive chain walk over all 10 local leagues (measured) | ✅ zero chains with `count(*) <> count(distinct season)` |
| No null `manager_id` and no manager with two rosters in one league | `roster_season` for the 7 leagues (measured) | ✅ 0 and 0. The only unique key is `(league_id, roster_id)`, so the second one isn't guaranteed (F15) |
| 2026 future fixtures | `league_matchup` (measured) | ✅ 4: weeks 1–14, 9465: 1–14, 210: 1–19. No playoff week is stored before it's scored (F7) |
| `HeadToHeadServiceTest` exists to stay green | `backend/src/test/.../engine/` (read) | ✅ |

## Findings

Ranked by severity.

**F1 (blocker). V32 is already taken, and it's already applied to the shared dev DB.**
The constitution row says "V31 is the highest on every branch (checked)". The check looked
at committed refs. Measured:
- `.claude/worktrees/033-trade-verdicts` has an **untracked** `V32__trade_assets.sql`
  (`alter table league_transaction add column draft_picks jsonb, …`).
- `flyway_schema_history` on 5433 has `32|trade assets|2026-10-14 10:47:33|t`.

Consequences:
- With 034's own `V32__league_bracket_match.sql`, every bootRun and IT on this machine fails
  Flyway validation, because version 32's checksum and description don't match. *(Inferred
  from Flyway's validate rules, not run.)*
- With V33, the applied V32 is "applied but not resolved locally". It sits below the
  branch's highest version, so it isn't covered by `*:future` (lessons #22 relies on that
  pattern). That likely fails validation too, until 033 is merged and 034 rebased onto it.
  *(Inferred, not run.)*
- In prod, whichever spec merges second has to renumber.

**Amendment:** use `V33__league_bracket_match.sql`. Record in plan.md that 033 owns V32.
Build order step 1 should then do one of two things: rebase onto 033 once it merges, or run
the ITs and bootRun against a scratch database until it does. Re-check the highest version
across **worktree directories**, not only refs, right before committing. That's the check
the 017 review ran ("every local branch, remote and worktree").

**F2 (should-fix, high). A pre-playoff season's bracket isn't empty. Sleeper returns a
provisional bracket seeded from current standings.**
R10 says "An empty bracket (pre-playoffs) stores nothing", and quickstart §1 expects "0 for
2026". Measured today (week 5–6 of the NFL season, and before NBA tip-off):
- `1346366555759341568` (Foot 2026): `{"m":1,"r":1,"t1":9,"t2":2,"w":null}`,
  `{"m":2,"t1":4,"t2":1}`, `{"m":3,"r":2,"t1":10,"t2":null}`, … with 7 rows.
- West Coast 2026 and NBA 2026 look the same, 7 rows each.

So 2026 stores 7 rows now, and their pairs move every week as the standings move. Under
data-model's GameKind ("season has bracket rows, pair not in them → CONSOLATION"), any
playoff-week game scored while the stored bracket is stale is classified **as a fact**. A
real semifinal could come out as CONSOLATION. The bracket is refreshed on the same chain walk
as the points, so the window is small. But C3 doesn't specify the order of the two calls.

**Amendment:**
- Match on week as well as pair. For round type 0, round `r` is week `start + r − 1`. For
  round type 1, the last round covers two weeks. For any other round type, fall back to
  UNSPLIT.
- A game in week W is PLAYOFF only if a bracket row of W's round has that pair. If any row of
  that round still has a null `t1`/`t2`, the whole week is POSTSEASON_UNSPLIT
  (`reason: "BRACKET_PENDING"`).
- In `ingestChain`, call the bracket ingest **after** `ingestWeeklyPoints`.
- Fix quickstart §1 to expect 7 provisional rows for each 2026 season.
- Add a unit test with a stale bracket, where the pair isn't in the round's rows and a slot is
  null. It should assert UNSPLIT, not CONSOLATION.

**F3 (should-fix, high). Round type 1 is now measured. The two legs of the West Coast 2025
final had different winners, so "A won the final" can't come from one week.**
R2 marks this *inferred*. Measured: `league_matchup` 9466 pairs `3v6` in both week 17 and
week 18. Bracket m6 (`p:1`) is `t1:6,t2:3,w:6`.

| roster | wk 17 | wk 18 | sum |
|---|---|---|---|
| 6 (gregmullen, `final_placement 1`) | 129.30 | 139.74 | 269.04 |
| 3 (achan20) | 111.96 | **142.56** | 254.52 |

Read per week, the matrix and the versus page give this final as **1-1**, while the bracket
says 6 won. The CHAMPIONSHIP_REMATCH text ("Met in the 2025 final (A won 141–122)") would have
to pick a leg, and that's wrong either way. The scoring has the same problem:
`playoffBonus × playoffMeetings` counts the final twice.

**Amendment:**
- Keep the per-week count in `byKind` and the totals, so parity (FR-003) holds.
- Build `finals[]` and every reason from the **bracket row**: the winner is `w`, and the
  scores are each side's summed legs. Show it once, labelled "two-week final".
- Count bracket matches, not weeks, for `playoffBonus`.
- Add a service test with this exact shape: legs split 1-1, aggregate and `w` agree.
- The 3rd-place game (12 v 5, `p:3`) also runs both weeks. It needs the same treatment.

**F4 (should-fix). "PLAYOFF" includes the 3rd- and 5th-place games.**
Every bracket has `p:5` (the round-1 losers, measured: Foot 2025 m5 is `8 v 4` in week 16)
and `p:3`. Under data-model, those count as PLAYOFF meetings and earn `playoffBonus`.
PLAYOFF_HISTORY would then call two teams knocked out in round 1 "met in the playoffs".
That's the overclaim R2 rejected for consolation games, one level down.

**Amendment:** `placement` is already stored, so use it. Either:
- (a) restrict PLAYOFF_HISTORY and `playoffBonus` to elimination rounds (`p` null) and the
  final, with placement games named in their own words ("met in the 5th-place game"), or
- (b) split the GameKind into PLAYOFF and PLACEMENT.

This is a wording and modelling call for Allan. Record whichever he picks.

**F5 (should-fix). Delete-then-insert on an empty response can wipe a finished bracket.**
data-model's write rule is to replace on every fetch, with an empty response storing zero
rows. Memory `project_sleeper_nba_2025_matchup_drift` already records Sleeper serving
degraded data for old seasons. A transient `[]` or `null` on the C2 backfill, or on
MemberSetupController's "Load past seasons" (`MemberSetupController:95` calls `ingestChain`
with `Set.of()`), would delete a correct bracket. The ingest still reports success.

**Amendment:**
- A null or empty response never deletes existing rows.
- A stored bracket whose every row has `w` set is final. C2 skips it unless `?force=true`.
- Handle a null body (`body(List.class)` can return null) as "no data", not as an NPE.
- Use `references league (id) on delete cascade`, like V13 and V19.

**F6 (should-fix). Finished seasons of every other prod league stay UNSPLIT forever.**
The refresh skips `loaded_complete` seasons (`LeagueRefreshService.refreshChain:193`,
`ingestChain:99` `continue`s before any call). The deploy step is "the C2 backfill on prod
for each league", but prod is multi-user (memory: multi-user onboarding). Every member
league's finished seasons stay POSTSEASON_UNSPLIT unless an operator finds and backfills each
one.

**Amendment:** make backfill self-healing. In the chain walk, a **skipped** season with
zero bracket rows gets exactly one `winners_bracket` call. That's no `/matchups` and no
standings, so it can't touch scores. With F5's freeze, it's one call per season, once. Keep
C2 for a manual re-run.

Separately, decide what a failed bracket fetch does to the refresh. Today, an exception in
`ingestChain` fails the whole chain (`refreshChain:236-250`). That's probably right (a stale
bracket shouldn't freeze into `loaded_complete`), but state it and test it.

**F7 (should-fix). The playoff week being played is always TBD.**
`ingestRemainingFixtures` stores only `week < playoffWeekStart` (`LeagueHistoryIngestService:331-335`).
`ingestWeeklyPoints` stops at `last_scored_leg` (`:251, :266`). So a playoff week's pairings
are stored only once it's scored. Measured: 2026 `league_matchup` holds weeks 1–14 (4, 9465)
and 1–19 (210), with no playoff weeks. During week 15, the grid will say TBD for every manager,
which is the one week a commissioner most wants it.

**Amendment:** pick one and write it down.
- (a) Fill winners-bracket opponents from stored bracket rows for the round of week W, where
  both `t1` and `t2` are set. Consolation stays TBD.
- (b) Keep TBD, with a cell sentence saying why: "pairings arrive once the week is scored".
  It must not suggest running an ingest (`NoIngestHintsInMessagesTest` scans `engine/`).

Either way, don't widen `ingestRemainingFixtures` inside this spec.

**F8 (should-fix). In playoff weeks, a null row usually isn't a bye.**
R9's rule is "a week with pairings where this manager has none is BYE". Measured:
- Foot 2025 week 17 nulls are rosters `12, 8, 7, 4`. 4 and 8 were eliminated: they played the
  5th-place game in week 16.
- West Coast 2025 weeks 17 and 18 nulls are `1, 7, 9, 10`.

Labelling those "bye" is false.

**Amendment:**
- In playoff weeks, the state is `NO_GAME` ("season over" or "out").
- Use `BYE` only when the bracket says so: a round-2 slot filled without a `*_from`, like
  Foot 2025 rosters 1 and 11 in week 15.
- Regular-season weeks keep `BYE`. No current league has an odd roster count, so there's no
  live case for it.

**F9 (should-fix). SC-005 and quickstart §5 need a "next week", and the contract has no
field for it. The season source isn't pinned.**
`seasonGrid` carries no current week. The client can't highlight "your next opponent"
without guessing from the absence of results. That's wrong for an in-progress week, which
has no result yet and is still the current week.

**Amendment:**
- Add a nullable `currentWeek`, read from `leg` through the existing `LeagueRepository` reader,
  which is never defaulted (`:276-278`). Mirror it in `api.ts`.
- State explicitly that the service reads the league row named by the URL
  (`bySleeperId`, the row `visibleLeague` returned) and **never** `LeagueSeasonResolver`.
  This is the 017 review's F1 trap. NBA 2026 has zero scored weeks (measured), so the resolver
  would answer 2025.
- Add a test with a two-season chain whose newest season has no scores.

**F10 (should-fix). The spec and plan disagree on "no history".**
Spec US3.7 says a league with **no prior seasons** gets "not enough history". data-model's
`NO_HISTORY` is "no scored season before **or in** the requested one". So a first-year league
in week 6 would get a full set of thin pairings.

**Amendment:** decide which one holds, then amend the other with a visible note. The spec's
version is the more honest one. Five weeks of a first season is a schedule, not a rivalry.

**F11 (should-fix). "Never met" reads as final for pairs that simply haven't been scheduled
yet.**
Measured over current-manager pairs:

| Chain | 0 meetings | 1 | 2 | ≥3 | pairs |
|---|---|---|---|---|---|
| Foot | 7 | 22 | 29 | 8 | 66 |
| West Coast | **43** | 28 | 14 | 6 | 91 |
| NBA | 0 | 3 | 8 | 55 | 66 |

West Coast went from 12 to 14 rosters with turnover. Many of its 43 zero pairs are on the
2026 schedule for later weeks. R8 would print "never met" for them in October.

**Amendment:** add `NOT_YET_MET` for pairs whose only shared seasons are in progress and that
`scheduledPairs` pairs in a later week. It still scores 0. Also add the zero column to R5's
table. Its "pairs ≥ 3" totals included former managers, while the matching only sees
current pairs (8/66, 6/91, 55/66).

**F12 (should-fix). The matching is underspecified exactly where determinism and the
"why" text depend on it.**
- **Odd n.** Choose the state: `(mask, byeUsed)` or a dummy vertex with 0-weight edges, and
  say which.
- **Who sits out, and why.** The DP sits out whoever maximises the total. That isn't
  necessarily the manager with the "fewest qualifying rivalries" (the spec's example), so that
  sentence could be false. The `why` must state what actually decided it, with numbers. For
  example: "leaving X out gives the highest total; X's best remaining pairing scored 0.5".
  The alternative is to make the rule literally "fewest qualifying rivalries" and fold it into
  the objective.
- **Tie-break.** "Keeps the first-found optimum" depends on the recursion, and "rounded to
  1e-9" is not the same as an epsilon compare. Specify both:
  - The lowest unset bit `i` pairs with `j` ascending, and the bye option goes first or last.
  - A candidate replaces the best only if it's greater than best + 1e-9.
  - Add a test with two equal-weight optima that asserts which one wins.

**F13 (should-fix). The parity test and the click-through aren't well-defined across the
two NFL chains.**
`HeadToHeadService.compute` buckets every shared league of one sport together
(`HeadToHeadService.java:96-106`). Its `aWins`/`bWins` span Foot **and** West Coast.
- Parity has to recount from `meetings` filtered by `sleeperLeagueId ∈ chain`, not compare
  totals. Measured: only **one** manager is in both NFL chains (overlap 4↔9465 = 1), so no real
  pair spans two chains. The filter path never runs on live data, and the test needs a
  synthetic two-chain fixture.
- A cell's click-through opens a page whose totals can differ from the cell for such a pair.
  Either label the link ("all leagues together") or have the versus page print per-league
  subtotals.

**F14 (nit). The score doesn't encode what the spec says, and one ordering needs a
decision.**
- The spec's assumption ranks "close records with many meetings first, then playoff history
  and **streaks**". The score has no streak term, so STREAK only ever appears as the reason
  for a pair that was matched for other reasons. Say so, or add a term.
- With the starting values, a single 1-0 meeting in a final scores 0.5 + 1.5 + 2.5 = **4.5**.
  That beats a 2-2 pair in 4 meetings (4.0). Defensible, but pin it with a preference test in
  whichever direction Allan picks.
- The formula's other orderings check out: 1-0 0.5, 2-0 0.67, 3-0 0.75, 1-1 2, 0-0-2 ties 2,
  2-1 2.25, 7-1 2.67, 4-4 8, 5-5 10.

**F15 (nit). R3's assumption can be removed rather than pinned.**
Call `pairedWithScores(List.of(leagueId), …)` once per chain league, at most 3 queries. That's
exactly how HeadToHeadService does it (`:143`), and the `(season, week)` uniqueness question
disappears. It holds today (measured), but nothing enforces it.

Also guard two cases the schema allows:
- `aManagerId == bManagerId`: `roster_season` is unique only on `(league_id, roster_id)`.
- A null manager: `pairedWithScores` left-joins. Count it in `allOpponents` as "unknown",
  not silently drop it.

**F16 (nit). Reason wording.**
- `MOST_MEETINGS` can tie. Measured, the max is unique today (Foot: one pair at 4, NBA: one
  at 6), but say "tied for most (k pairs)" when it isn't.
- `CLOSEST` is a superlative for a rule that's only "|w−l| ≤ 1". Print "close", or make it
  literally the minimum.
- Define STREAK when there's a tie: does a tie end the streak?

**F17 (nit). Config, copy and small consistency points.**
- **Partial config block.** A partial `draftsim.rivalry` (one key null) should be
  `CONFIG_MISSING`, the same as a missing block.
- **`splitNotes` copy.** Their sentences live in `engine/`, so they must not say "run the
  backfill".
- **Median game.** NBA 2026 has `league_average_match = 1` (measured). The grid's W/L is the
  H2H game only, while the standings order of `rows` includes median wins. Note it on the page.
- **Stale career note.** `ManagerCareerService:127` says "the bracket is not parsed", which
  stops being true. Leave it, but don't let it contradict this page. It's a follow-up for
  playoff appearances.

**F18 (nit). R10's reason is right, but overstated.**
`league_week_fetch` has every POINTS week `final` for 5, 9466, 211 and 212 (measured: 17/17,
18/18, 21/21, 24/24), and `WeekFinality.mayStopRefetching` is `storedFinal && week <
lastScoredLegNow`. So a full re-ingest refetches only each finished season's **last** week.
That happens to be the championship week, and for NBA 2025 it's week 21 of the degraded
endpoint. So the bracket-only endpoint is still the right call. Correct R10's wording
("reruns the whole chain") so nobody later "simplifies" the reasoning away.

**F19 (nit). Frontend.**
- The `history` row's href is whole-chain (`destinations.ts`: "whole chain: History walks
  every season itself"). Rivalries is half whole-chain (the matrix) and half one-season (the
  grid). The `ctx.season` choice is right for the grid. Say in the row's comment why it
  differs from its group neighbour.
- Add `'rivalries'` to the `DestinationKey` union.
- At 375px there are two wide tables (14×14 and 14×17). Give each its own scroll container
  with a sticky first column, as `ScheduleGrid`'s `sg-table` already does, so the page itself
  never scrolls sideways.

## Spec coverage

| Acceptance | Covered by the plan? |
|---|---|
| US1.1–1.4, FR-001–003, 005 | yes |
| US1.5 / FR-004 split | yes, but the semantics need F2–F4 |
| US2.1–2.4 | yes. "Bye" needs F8 |
| US2.5 TBD | yes, but the in-progress week is always TBD (F7) |
| US3.1–3.6 | yes. The odd-case "why" needs F12 |
| US3.7 | contradicts data-model (F10) |
| SC-005 | no contract field for the next week (F9) |
| SC-001 parity | needs a cross-chain fixture (F13) |

## Summary by severity

- **Blocker:** F1 (V32 is taken by 033 and already applied to the shared DB).
- **Should-fix:**
  - F2: a provisional bracket exists pre-playoffs, so classification needs week-aware
    matching.
  - F3: the two-week final split 1-1, so the final must come from the bracket.
  - F4: 3rd- and 5th-place games count as "playoff".
  - F5: an empty response wipes a stored bracket.
  - F6: other prod leagues never get backfilled.
  - F7: the current playoff week is always TBD.
  - F8: "bye" mislabels eliminated teams.
  - F9: no `currentWeek` field, and the season source isn't pinned.
  - F10: the spec and plan disagree on NO_HISTORY.
  - F11: NEVER_MET vs not-yet-met.
  - F12: odd-n state, honest sit-out reason, exact tie-break.
  - F13: parity across two NFL chains, plus the click-through mismatch.
- **Nit:**
  - F14: score vs the spec's stated priorities.
  - F15: query per league instead of pinning R3; guard self-pairs and null managers.
  - F16: reason wording.
  - F17: partial config, copy and median.
  - F18: R10's reasoning.
  - F19: frontend.
