# Adversarial plan review: spec 040

This review read spec, plan, research, data-model, contract and quickstart cold on 2026-10-20. It checked them
against branch `040-superlatives-commissioner-ux` (code identical to `main` @ `7736114`) and the local Postgres on
5433. No code was written and no backend was running (`localhost:8080` did not answer), so nothing here was checked
against a live payload.

Each item is labelled **measured** (SQL run today, result quoted), **read** (from code, not executed) or
**assumed**.

## Verdict

The design holds up. US1's counting rule is sound, and two of research's own claims reproduce exactly. One success
criterion will fail as written, though: **SC-003**, "NFL totals unchanged within 0.1". It isn't a stat-correction
edge case, as R2 suggests. It happens by construction. Two wiring traps (F2, F3) would ship silent bugs. Fix
F1–F5 in the plan before `/speckit-tasks`. F1 changes a stated success criterion, so Allan should see it, but it
doesn't change what the feature is.

## Claims confirmed

| Claim | Where checked | Result |
|---|---|---|
| R1 counts (NBA 2025, wk 1–18) | SQL over `player_absence`/`player_game` (measured) | ✅ exact: 5,104 player-weeks, 11,569 games, 2,579 zero-play weeks, 7,578 games inside them |
| R1 scoped to league 211's rostered weeks (not in research) | same, joined to `players_points` (measured) | 1,230 rostered absence-weeks / 2,511 games → **443** zero-play weeks |
| R2: NBA `players_points` is one credited game | rescored `player_game.stats × league 211 scoring_json` vs `players_points` (measured) | ✅ 3,062 of 3,196 multi-game weeks equal one of that week's games; 1 equals the sum. Credited mean 22.24 vs per-game 19.87 (**+11.9%**, not research's "14–19%"; close enough, but cite the measured figure) |
| R3: an NFL absence row never coexists with a game row | `player_absence` × `player_game` 2025 (measured) | ✅ NFL: 0 of 14,979 non-UNCLASSIFIED rows. NBA: 6,180 of 16,692 ENTRY_WITHOUT_PLAY rows do (the partial weeks). No NFL (player, week) has >1 absence or >1 game row, so **NFL counted weeks are identical to today's** |
| Absences, `playedWeeksByPlayer` and `parsedWeeks` share one window | `forLeague:261-288`, `boundedPlayerGames:1104-1118`, `absenceSuperlative:966-970` (read) | ✅ all filter on `scoredWeeksFinal` (regular season when a playoff start is set, else every stored week) |
| "Zero game rows" can't charge a fetch gap on its own | `AbsenceCost.compute` needs an absence row; `neverWalked` players fail `isRegularContributor` (read) | ✅ a week with neither row is never counted. A partial walk under-counts and never over-counts. **Assumed:** absence and game rows come from the same per-player walk, so they can't diverge within one week |
| R5: backend already accepts writes to the URL's own row | `SuperlativesController:73-128` uses `visibleLeague` (exact row, no resolver) + `canCommission(row.id)` (read) | ✅ the lock is only the frontend's `data.leagueSleeperId` (`Superlatives.tsx:159-166`) |
| Awards URL is the season being viewed | `destinations.ts:323` `ctx.season.sleeperLeagueId`; route `App.tsx:172` (read) | ✅, and the path is `/superlatives`, not `/awards` (see F11) |
| ESPN `espn:<id>` candidate keys | `LeagueAvailability:78` gates `superlatives` **and** `conduct-list` for ESPN (read) | ✅ not reachable, so it's not a concern |
| V38 is free | every worktree's `db/migration`, all refs, local `flyway_schema_history` (highest applied 37) (measured) | ✅ |
| PUT allowed by CORS | `WebConfig:51` (read) | ✅. The live browser check is still owed, as the contract says |
| R7 double count is real | `unethicalSuperlative:1242` sums `weeks().size()` across SUSPENDED + COMMISSIONER rows of one player (read) | ✅ |
| Other consumers of the changed shapes | grep for `AbsenceDetail`, `weeksAffected`, `pointsPerGame`, `AbsenceCost`, `playerStandings`, `ConductList` in backend + web (read) | Only the Superlatives page and controller, plus tests. `ManagerComparison`'s `pointsPerGame` is unrelated. `LeagueHome.AwardBlock` reads only the first kind with holders. **But see F7 (spec 038)** |

## Findings

**F1 (HIGH). SC-003 / FR-003 will fail by construction, not from stat drift.** The credited weekly mean only
samples weeks the player sat on a roster *in this league*, and it can only see those, because `players_points`
exists only for rostered weeks. Today's `pointsPerGame` (`boundedPlayerGames:1104`) averages *every* game in the
window, including weeks he was a free agent or on nobody's roster. R2 says the two "should agree except stat
corrections". That's wrong.
- **Measured** on league 5: of the 90 players with any rostered absence-week, 34 have played weeks outside every
  roster in the league. 2,636 of 2,697 player-weeks match Sleeper's `pts_ppr`, so the league is effectively PPR.
  I used `pts_ppr` as a proxy for today's rescored mean (rows without `pts_ppr` counted as 0, so this is an
  approximation, not the real `GameScoring` path). With a rough regular-contributor filter, **9 of 12 rosters
  move by more than 0.1 pt**. Roster 2 moves by −11.43, and single players move by up to ±13.6.
- Some samples are a single week: player 4666 on roster 2 has n = 1 credited week against 6 played games. The
  per-week figure for that player is one game, presented as an average.
- **Amend**: restate FR-003/SC-003 as "NFL *counted weeks* are identical (verified: no NFL week has both an absence
  and a game row). The per-week value changes, because it now averages only weeks the player was rostered in this
  league." Report the measured per-roster deltas in verification. Don't fork the rule per sport, and don't fall back
  to `pointsPerGame`; R2's own "one rule" stance is right. To stay honest about thin data, add `weeksSampled` to
  `AbsenceDetail` and `PlayerContribution`. The note then reads "N weeks missed · X pts/week (avg of M weeks)". With
  `pointsPerGame` gone, `boundedPlayerGames` no longer needs `GameScoring.score` per row. It only needs the played
  weeks, which also removes the costliest step of the Superlatives call (038 measured 0.67 s on NBA 2025).
  `SeasonSuperlativesAbsenceWiringTest:87,101` then changes.

**F2 (HIGH). The new `Superlative` field will be silently dropped by `withUsernames`.** `forLeague:367` passes
every kind through `withUsernames` (`:1550`), which rebuilds the record with the explicit 13-argument canonical
constructor. There are 24 `new Superlative(` sites in the service (read). To keep them compiling, a builder will
add a 13-argument overload that defaults `playerContributions` to `[]`. `withUsernames` then binds to that overload,
and every contribution disappears before the controller sees it. Nothing fails, and the IT would only catch it if
it asserts non-empty contributions. The `Holder`s inside contributions also need usernames filled, or the players
view shows no username next to the team name.
**Amend**: plan names `withUsernames` explicitly (copy `playerContributions`, mapping each `team` through
`withUsername`). Add a `SuperlativesStandingsIT` assertion that `playerContributions` is non-empty for all three
kinds. Also fix the contract's `Holder` example: the wire shape is `{rosterId, managerId, teamName, username,
avatarId}` (`SuperlativesController.holderRow:270`), not `{rosterId, name, avatarId, managerId}`. Reuse
`holderRow`, and add `playerContributions` to the hand-copied `superlativeRow` (`:203`).

**F3 (MEDIUM). Every click inside the panel will also toggle the standings, and the button can vanish.** The card is
an `<article onClick={() => setOpen(true)}>` whenever `openable` (`Superlatives.tsx:215, 236-245`). The plan
stops propagation only on the "Commissioner's list" button. Every click in the panel's picker, inputs, Save, Edit
and Remove bubbles to the article and opens the standings underneath. `SuperlativeStandings:95` and `DetailList`
already stop it at their own container. Also, `openable` is false whenever UNETHICAL is empty, which is the normal
case today (NBA 2025: nobody qualifies), and the action column renders only `{openable && …}` (`:393`).
**Amend**: the panel root calls `onClick={e => e.stopPropagation()}` (and `onKeyDown`, as `DetailList` does). The
list button renders for UNETHICAL regardless of `available`, `isEmpty` or `openable`. Add a test that a click
inside the panel leaves `aria-expanded` of "See all" unchanged.

**F4 (MEDIUM). The list is unreachable or empty in two real states.**
- **(a)** When no season in the chain has been played, `forLeague` returns `available:false`
  (`SeasonSuperlativesService:243-252`). The page then renders no cards at all (`Superlatives.tsx:118-123`), so no
  Unethical card and no button. Today's bottom section has the same gate, so this isn't a regression, but FR-011
  promises pre-first-week editing. Locally, leagues 460 (NFL 2026) and 34534 (NBA 2026) have 0 scored weeks
  (measured). **Amend**: render the Unethical card, or the list button alone, in the `!data.available` branch, using
  the URL id.
- **(b)** `roster_season.players` is NULL for leagues 3, 5, 9466, 9465, 460 and 34534 (measured; only 4, 210 and
  211 have it). R4's candidates are the union of that column and `players_points`, so a new league with null
  `players` and no scored week has **zero** candidates. The picker can't add anyone, and FR-007 forbids the id
  fallback. On 5 and 9466, every candidate would also have `fantasyTeam: null`, since the "current holder" comes
  only from `players`. **Amend**: say what the picker shows when candidates are empty ("rosters haven't loaded yet;
  refresh the league"). When `players` is null, take `fantasyTeam` from the latest scored week's `players_points`.
  Note that candidates are only as fresh as the last league ingest (210 was fetched 2026-10-11).

**F5 (MEDIUM). R5 reverses a recorded code-review fix and an explicit regression test. The plan should say so.**
`Superlatives.tsx:700-710` documents the 2026-09-23 fix "never the URL's own id". `Superlatives.test.tsx:1063`
asserts "fetches, saves and deletes the conduct list against leagueSleeperId, not the URL id", and `:1108` asserts
the "opens once it has a scored week" copy. R5's labelled-season approach is reasonable, but done silently it
repeats the doc-rewrite pattern AGENTS.md forbids. Separately, `canEdit = commissionerListAvailable &&
list.canEdit` (`:793`) mixes the *resolved* season's `anyCommissioner` (row 211: 1 commissioner) with the URL row's
(row 210: 2; measured).
**Amend**: tasks rewrite, rather than delete, the two tests (the new assertion is "addresses the URL id; header
names its season when `requestedSeason != null`"). Amend the component comment with a dated note. Drive
`commissionerKnown` and `canEdit` from the conduct-list response alone.

**F6 (MEDIUM). Uncapping Jabari makes the existing "Show all" summary false.** `SuperlativeStandings.tsx:60-64`
prints "N more players with K adds each", where K is the *first* collapsed group's adds. That was only true because
the backend cut at 10 + ties, so the collapsed rows were a single tie group. Uncapped (`:838` `top(..., 10)` →
all), the collapsed rows span every add count, and the label misstates the count. That's the count-and-label lesson
again.
**Amend**: change the summary to "N more players" (or "with K or fewer adds"), plus a test with two collapsed rank
groups.

**F7 (MEDIUM). Spec 038 (Season Wrapped, planned and uncommitted in its own worktree) edits the same records.**
038's plan F5/F12 adds each roster's top pickup and a structured `week` to `Standing` /
`SuperlativeStandings.Entry`, and reads Embiid/Unethical framing text from this page (`038/plan.md:37-48`). Both
specs touch `Superlative`/`Standing`, the controller's hand-copied rows, and the waiver pickup surface. 040's WAIVER
`playerContributions` (every roster's pickups, uncapped) is exactly the data 038 F5 is about to add to `Standing`.
**Amend**: add a "Coordination with 038" line. 038 should read the top pickup from `playerContributions` instead of
adding a second per-roster pickup field (two implementations of one rule). Whichever merges second rebases the
records.

**F8 (LOW). SC-002 is true by definition.** The total is defined as Σ weeks × mean, so "no total exceeds weeks ×
mean" can't fail.
**Amend**: replace it with checks that can fail:
- a per-roster before/after table;
- every counted (roster, player, week) has 0 `player_game` rows;
- one hand-checked partial week costs 0;
- no (player, week) is counted for two rosters.

**F9 (LOW). R7 is real, but the fix belongs where it can be unit-tested.** The total sits in the private
`unethicalSuperlative` (`:1242`), and `UnethicalAwardTest.totalWeeks` (`:25-30`) re-implements the double-counting
sum, so a fix there wouldn't be tested.
**Amend**:
- Extract the per-roster total into a pure static method, point the test helper at it, and add a test for one
  player with both sources covering the same weeks.
- No existing assertion breaks: `everyRowCarriesASource` (`:98`) uses two different players.
- `LeagueConductRepository.Entry` gains a component, which touches 8 test constructor sites.
- An entry's own `playerWeeks` won't add up to the award total when Sleeper also flags the same weeks. Say so in
  the UI note, or the commissioner sees a mismatch.

**F10 (LOW). Write path details.**
- POST's upsert (`LeagueConductRepository:59-61`) must also set `applies_through_week = excluded…`. Otherwise
  re-adding a listed player keeps the stale end week.
- PUT must check `through >= from` in the controller. Otherwise the V38 CHECK constraint turns a bad request into a
  500.
- The contract has POST/PUT return a full entry with `charges`/`playerWeeks`, which means loading the season's
  weeks per write. The client already refetches the list, so return the plain row and document that `charges` is
  GET-only.

**F11 (LOW). Quickstart and test fixtures.**
- Scenario 5's URL is `/leagues/…/awards`. The real route is `/leagues/…/superlatives`.
- `SuperlativesStandingsIT` seeds `players_points` X = 100 while the rescored game is 10 pts
  (`:186-202`). Its Embiid figure goes from 10 to 100 under the new rule. That's the right outcome, but the fixture
  comment says "10 ppg", so update it deliberately.
- Scenario 6 writes a conduct row into the shared local DB. Delete it afterwards (concurrent sessions).

**F12 (LOW). The literal ask was "see all of them if I click on it".** The plan opens the full view on Teams and
puts Players behind a toggle. **Suggest**: default to Players for JOEL_EMBIID (and WAIVER), since that's what was
asked, and keep Teams one click away. This is Allan's call, so it isn't blocking.

## Assumed, not checked

- `GameScoringService` and Sleeper's `pts_ppr` agree closely enough for F1's proxy to show the direction and
  magnitude. The real per-roster numbers come from verification.
- Absence and game rows for one player-week are always written by the same walk (F-table row 5).
- `roster_season.players` is refreshed by the normal league ingest/auto-refresh in prod. I didn't trace the
  scheduler.
