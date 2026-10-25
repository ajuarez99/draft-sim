# Adversarial plan review: spec 047 (Hall of fame)

This review read spec.md, plan.md, research.md, data-model.md, contracts/api.md and quickstart.md
cold on 2026-10-24, before any code existed. It checked them against the code in worktree
`047-hall-of-fame` (main `ebf4ee8`), the shared Postgres on 5433 (read-only queries), the live
Sleeper API (public GETs), and the backend already listening on 8080. That server belongs to
another session, so its branch is unknown. Only `/superlatives` was called on it, as GETs.

Every item is labelled **measured** (run today, output quoted), **read** (from code, not
executed) or **suspect** (inferred, not run). The pair-distribution numbers come from a SQL
re-implementation of `pairCalc`'s outcome rule over `league_matchup` + `roster_week_points`
(`starters_points` compared directly). That's an approximation of `HeadToHeadService.outcome`,
not the service itself. Scripts are in the session scratchpad, not the repo.

## Claims confirmed

| Claim | Where checked | Result |
|---|---|---|
| The `chainGames` split doesn't change `/rivalries` | `RivalryService.java:224-257` (read) | ✅ The format/bracket/`pairedWithScores`/`classify` loop, `managersOfScoredLeague` and `byPair` read only `chain`. All current-season state (`standings`, `scheduled`, `lastScoredWeek`, `leg`) starts at `:259` or before `:224` and isn't used in the loop. The split is safe. `Game`, `PairKey` and `PairCalc` are private nested records, so they need package visibility for `HallOfFameService`. |
| `pairCalc` makes sense for a non-current pair | `:359-440` (read) | ✅ With `scheduledWeek = null` its status is MET / NEVER_MET / NO_SHARED_HISTORY, never NOT_YET_MET. Rivals only reads MET pairs, so that's fine. Orphan opponents are dropped from `byPair` (`:255`), so an orphan can't be a nemesis. That's correct. |
| Manager ids are stable across seasons, and "former" can be identified | `roster_season` (measured) | ✅ Former managers exist: 55 in (Foot) Ball Knowers 2025, 611 in NBA 2024, and 2, 23, 26 in West Coast 2025. No orphan rosters, and no manager on two rosters in one league. **West Coast 2025's points leader (roster 7, 1916.44) is manager 23, a former manager.** That's a ready-made fixture for the "left the league" edge case. |
| R4: both champion sites use the same `complete` | `LeagueHistoryController.java:318`, `ManagerCareerService.java:203`, `RosterSeasonRepository:297,330`, `LeagueRow.isComplete` (read) | ✅ History passes `LeagueRow.complete()`. Careers pass `StandingRow.complete()`, which `forManager` maps from `l.status` through the same `LeagueRow.isComplete`. Extracting `ChampionRule` can't change `champion` (but see F11 about `titles`). |
| R1: the bracket's p=1 winner equals the stored champion | `league_bracket_match` vs `roster_season` (measured) | ✅ 212: 2 = 2. 211: 12 = 12. 9466: 6 = 6. League 5 (FBK 2025) has 0 bracket rows locally. Runner-ups are 6, 7 and 3, matching quickstart. |
| `points_for` is the History standings figure | `standingRow` `:303` (read) | ✅ The same column, so the leader the trophy case prints is the number standings already show. |
| Past seasons' superlatives compute | `GET /superlatives` on 8080 (measured) | ✅ NBA 2024 took 0.41 s and NBA 2025 0.54 s warm. The first call was 2.1 s cold. Both returned `requestedSeason: null` and 13 kinds. |

## Findings

### F1. HIGH (measured). R2's reason for dropping last place is wrong: the losers bracket's meaning can be measured

R2 says that whether `w` of the losers-bracket p=1 match is last place "depends on a setting the API
returns as null", so reading it "either way would be a guess". The setting is null
(`loser_bracket_type: None` for all four complete leagues, measured). But the stored scores show,
per league, which way `w` runs. For every losers-bracket match I compared `w`'s summed stored
points with `l`'s (two legs for West Coast's type-1 final):

| League | Season | `w` scored **less** than `l` | `w` scored more | Reading |
|---|---|---|---|---|
| (Foot) Ball Knowers (5) | 2025 | **7 of 7** | 0 | Toilet bowl: losers advance. p=1 `w` = roster 2, which went 2-12, the worst record. |
| Ball Knowers NBA (212) | 2024 | 1 (the p=3 match) | 6 | Consolation: winners advance. The p=5 loser = roster 9, 1-20, the worst record. |
| Ball Knowers NBA (211) | 2025 | 1 (the p=3 match) | 6 | Consolation |
| West Coast FF (9466) | 2025 | 0 | 7 of 7 | Consolation |

Sources: the Sleeper `losers_bracket` for each league id, and `league_matchup` + `roster_week_points`
for `week >= playoff_week_start`.

So "can't be known" isn't true. It's knowable for FBK outright (7/7), and it's ambiguous for NBA only
because of the same stored-score drift as spec 034's B2 (both NBA p=3 exceptions). The note C1
fixes ("Sleeper doesn't report last place for these leagues") is also inaccurate. Sleeper does
report the bracket and its placements. What it leaves out is which end is last.

The *decision* to show no last place may still be right. A rule built on "every match agrees on
direction, else refuse" rests on scores the NBA data shows can drift, and FR-004 forbids inference.
But the spec amendment was justified by evidence that doesn't hold, and AGENTS.md says a wrong
claim gets corrected visibly.

**Amendment.** Rewrite R2 with the table above. Then **put the choice to Allan, because it changes
what the feature is**:

- (a) Keep no last place, with an accurate note: "Sleeper's consolation bracket doesn't say whether
  it was played as a toilet bowl, so last place isn't shown."
- (b) Fetch `losers_bracket` (a new table or migration, which is a scope change). Name last place
  only when every decided match in that league-season agrees on direction:
  - toilet bowl → the p=1 `w`;
  - consolation → the loser of the bottom placement match.

  Any disagreement → "not reported". This also needs care beyond 6-team losers brackets: West
  Coast 2026 has 14 rosters, and the bottom-placement rule there is unmeasured.

Either way, fix C1's `lastPlaceNote` text.

### F2. HIGH (measured). "Most-played" has no floor, so it will call one meeting a rivalry

R9: "Most-played: max `meetings`", with no N and no uniqueness rule. On "fantasy😍" (league 3, 2026
only), all 21 met pairs have exactly **1** meeting (measured). Discovery would list 21 pairs as
"most-played: 1 meeting". That's the exact failure the spec names: "It only has value if it never
calls a 2–0 record a rivalry". It's also a **second definition of "the most"**.
`RivalryService.recommendation` (`:645-650`, F16 of spec 034) already says "a maximum of one meeting
isn't 'the most' of anything", and it requires `maxMeetings >= 2`.

**Amendment.**

- Most-played considers only pairs with `meetings >= N`, like the other three claims. Below that it
  says "no pair has met N times yet".
- Alternatively, reuse spec 034's floor in one shared helper. Either way, one definition.
- Add `fantasy😍` (or a fixture with max meetings = 1) as an acceptance case.

### F3. HIGH (measured). With today's data, "most lopsided" and "closest" produce misleading or huge lists

Meeting distribution per chain, current-season managers only (the matrix population):

| Chain | Pairs at ≥3 meetings | Records among them |
|---|---|---|
| (Foot) Ball Knowers | 8 | 7 × 2-1, 1 × 2-2 |
| West Coast FF | 8 | 5 × 2-1 at m=3. At m=4: one even, one 4-0, one other |
| Ball Knowers NBA | 55 | m=4: 30 pairs, **16 at 2-2**, 4 sweeps (4-0). m=3, m=5 and m=6 are all ≥1 apart |

Results under R9:

- **FBK "most lopsided"** = all seven pairs at **2-1** (max |diff| = 1, every winner share = 2/3, so
  all are tied). A 2-1 record headlined as "most lopsided" makes the output look more definite than
  the data is.
- **NBA "closest"** = **16 pairs** tied at 2-2 (same diff, same meetings), all listed per FR-010.

**Amendment.**

- **Lopsided** needs a real gap. Show it only when its |diff| is greater than the closest tier's
  |diff| (and ≥ 2). Otherwise say "no pair is one-sided yet".
- **Long tie lists**: keep "list every tied entry" (FR-010), but render ties over ~3 as a count with
  an expander ("16 pairs are level at 2-2"). The tie stays visible and nothing is hidden.
- Make R9 say both explicitly, and add these three chains' measured outcomes to the quickstart as
  expected values.

### F4. HIGH (read). The rivals endpoint leaks chains, and C3 contradicts itself

C3 says rivals "covers only chains visible to the caller, under the same scope
`/managers/{id}/history` applies". That scope (`LeagueHistoryController.java:350`,
`LeagueMembership.canSeeManager`) is "the caller shares **at least one** league with the manager".
After that, history returns **every** career of the manager, unfiltered (`careers.forManager`). So
"the same scope" doesn't mean "only visible chains".

This matters more for rivals than for history. Rivals names **opponents** (names, avatars, records)
in leagues the caller isn't in. Manager 7 plays in three NFL chains ((Foot) Ball Knowers, West Coast
FF and fantasy😍, measured). A caller who is only in FBK could open manager 7's page and see West
Coast's and fantasy😍's opponents.

The plan's forward walk to the chain head (`successorOf`) adds a second leak. It pulls in successor
seasons that `LeagueMembership`'s walk deliberately leaves out ("forwards only, since a successor
league you were later dropped from is genuinely not yours any more", `LeagueMembership.java:67-70`).

**Amendment.**

- C3: a chain's seasons are restricted to `membership.leagueIdsFor(caller)`, with admin seeing all.
  A chain with no visible season is omitted.
- Add an IT: a stranger who shares one league gets only that chain.
- Spell out that history's own careers scope is unchanged (out of scope), so nobody "aligns" one
  with the other later.

### F5. MEDIUM (read). C1 is wrong: `chainBySleeperId` walks backward only

C1 says "any season's id gives the same chain". `LeagueRepository.chainBySleeperId` (`:470-480`)
follows `previous_league_id` backward only. Handed NBA 2024's id, it returns 2024 alone, not 2025.

That's the right behaviour for scoping, since it matches the membership walk and `/history`. The
problem is the contract text: an implementer "fixing" code to match it would add a forward walk and
the F4 leak to the trophy case.

**Amendment.** C1: "the chain from this season back. The rail links the newest season, so the
History page sees the whole chain." No forward walk on this endpoint.

### F6. MEDIUM (read). There are two chain-discovery methods, and the plan cites them as one

Plan step 4 says rivals finds "the chain head via the forward step at `LeagueRepository.java:459`
... the approach `ManagerCareerService.java:295` uses". It doesn't. `computeRanks` (`:263-300`)
groups by **`leagueName`** and walks backward from the manager's newest season, and its own javadoc
says "this schema gives no FORWARD walk". A cycle-guarded forward walk already exists in
`LeagueSeasonResolver.seasons()` (`:131-148`).

The plan therefore names a third approach, and C3 requires the chain order to match careers, which
use `leagueName`. A chain renamed between seasons would split in careers but not in rivals. Names
are stable today (measured), but that's an assumption.

**Amendment.**

- Pick one chain identity. Recommended: the manager's newest *visible* season (F4), then
  `chainBySleeperId` backward. That's the same walk careers use, with no forward step needed.
  Opponents who joined after the manager left never met them anyway.
- Group by head `sleeperId`, and place each chain under its sport's career block. Manager 7 has
  three NFL chains, so the page needs a per-chain sub-block inside the NFL career.
- `former` = "no roster in that chain's newest season", using the same walk.

### F7. HIGH (read). When the sources disagree, the runner-up can be the champion

`sourcesDisagree` means the bracket's p=1 winner isn't the stored placement-1 roster. In that case
the stored champion is usually **the bracket's loser**, so "runner-up = the p=1 loser" names the
same manager in both cells.

The data-model also puts `sourcesDisagree` (bracket vs Sleeper's metadata winner) and `finalDisputed`
(bracket vs stored scores) side by side as two booleans. Both are "sources disagree" in plain
English, and spec AS 1.2's wording ("the two sources disagree") fits either one.

**Amendment.**

- When `sourcesDisagree`, `runnerUp = null`, with `runnerUpUnavailable` reason `SOURCES_DISAGREE`.
  Use an enum reason (`NO_BRACKET | SOURCES_DISAGREE | NO_LOSER`) instead of a bare boolean.
- Give the two notes distinct fixed sentences:
  - "Sleeper's bracket and its season result name different champions; the season result is shown."
  - versus spec 034's "the stored scores say otherwise".
- Also define the case where the champion is null but the bracket has a winner. Show "no champion
  reported" and no runner-up, never the bracket winner as champion.

### F8. MEDIUM (measured). "Final disputed" will sit on every NBA trophy row, and R8 leaves the method open

Measured finals, stored legs:

| League | Season | Bracket winner | Stored legs | Result |
|---|---|---|---|---|
| NBA | 2024 | roster 2 | 215.00 vs 248.00 | disputed |
| NBA | 2025 | roster 12 | 199.00 vs 234.00 | disputed |
| West Coast | 2025 (two-week) | roster 6 | 129.30 + 139.74 = 269.04 vs 254.52 | agrees |

So 2 of 4 rows, and **both** basketball rows, carry the note. Basketball is the real target sport
(memory). The champion is still right, since it comes from Sleeper's metadata, but the note now
reads as "this title is in doubt" on every NBA season.

R8 also says the plan's tasks "name the exact method once it's located". It's located now, and
there are already **two** copies in `RivalryService`:

- `pairCalc`'s `agrees` (`:404-412`, per pair, summed legs);
- `finalDisagrees` (`:589-604`, per game).

They're equivalent today.

**Amendment.**

- The trophy case reads `Final.scoresAgreeWithWinner` from the `pairCalc` of the **bracket match's
  two rosters' managers**, for that season. Not "champion vs runner-up", which breaks under F7.
  That keeps one source and adds no third copy.
- The wording should keep the champion clearly authoritative ("the stored game scores differ from
  Sleeper's result"), reusing the Rivalries sentence.
- Record the 2/2 NBA rate in research, so verification expects it and it isn't treated as a bug.

### F9. MEDIUM (read + measured). The playoff-meetings list miscounts two-week finals and misreads unsplit seasons

1. **Two-week finals.** `byKind` counts per **week** (`:377`), and a round-type-1 final has two weeks
   (`RivalryGameKind:91-93`). West Coast 2025's final (6 vs 3, weeks 17 and 18, measured) is
   `byKind.PLAYOFF = 2` for **one** playoff meeting. `DiscoveredPair` copies `byKind` only, so "2
   playoff meetings" is wrong. The per-match count already exists as `PairStats.playoffMeetings`
   (`:437`).
2. **Unsplit seasons.** (Foot) Ball Knowers 2025 has **0** bracket rows locally (measured), so all
   its playoff-week games are `POSTSEASON_UNSPLIT/NO_BRACKET`. Discovery would then say "no playoff
   meetings" for a league that certainly had playoffs. That's a false empty state (FR-013 / AS 2.4).

**Amendment.**

- `DiscoveredPair` carries `playoffMatches` (from `PairStats.playoffMeetings`), and the UI prints
  that number.
- When any season of the chain has a split note, the playoff list's empty state says "can't tell for
  {season}: the bracket isn't stored", reusing `splitNotes`, and never "no playoff meetings".

### F10. MEDIUM (measured + read). US4 can show the wrong season's awards, and "winner" isn't one shape

1. `SeasonSuperlativesService.forLeague` resolves via `LeagueSeasonResolver.Rule.PLAYED_WEEKS`
   (`:254`). A complete season with no stored weeks is answered with **an earlier season's** awards,
   flagged only by `requestedSeason != null`. The expanded row would then show another year's
   winners under its own year.
2. Winners aren't uniform across the 13 kinds (measured on NBA 2024/2025):
   - `JABARI_SMITH_JR` has `holders: []` and 2 `playerHolders`;
   - `UNETHICAL` has 0 holders with an `emptyReason`;
   - `CLOSEST_GAME` and `UNLUCKIEST` have 2 holders (ties);
   - the response-level `available: false` and the per-kind `available: false` are separate states.

   `Superlatives.tsx:209-360` handles all of these. A second renderer in `TrophyCase.tsx` would be a
   second implementation of "who won", which is what FR-012 says not to do.

**Amendment.**

- When `requestedSeason != null` or `leagueSleeperId` differs from the row's season, show "awards
  aren't available for this season" and none of the returned awards.
- Extract the winner-line rendering from `Superlatives.tsx` into a shared component used by both,
  with a vitest per state above.
- Cost is fine (0.4–0.5 s warm, ~2 s cold per season, measured), but cache per expanded row, so
  collapsing and re-expanding doesn't refetch.

### F11. MEDIUM (read). "One champion rule" doesn't cover the career `counted` gate

`ManagerCareerService` counts a title only for **counted** seasons. The `if (!counted) continue;` at
`:185` comes before the champion check at `:203`. The trophy case has no such gate. So a complete
season with a stored champion and no `roster_week_points` would show a trophy-case champion while
that manager's page says 0 titles.

All 4 complete seasons have stored weeks today (measured: max weeks 17/21/24/18), so nothing differs
now. But FR-002's "one implementation" isn't fully true.

**Amendment.**

- Name the extra gate in R4, and decide whether it stays (recommended: yes, out of scope).
- Add an assertion beside SC-001: for every manager, `titles` = the number of trophy-case rows naming
  them as champion, over counted seasons.

### F12. MEDIUM (read). Deploy skew: the two Railway services deploy independently

Memory records the web and backend services drifting apart on 2026-09-15. C2 says `discovery` is
"always present", but a web deploy that lands before the backend sees `undefined`. The repo already
has a precedent: in `api.ts`, `Rivalries.recommendation.week` is commented "An older backend omits
it (undefined); the page treats both alike". The two new endpoints will 404 on an older backend in
the same way.

**Amendment.**

- Type `discovery?:` in api.ts and render nothing when it's absent.
- `getTrophyCase` / `getManagerRivals` treat a 404 as "section not available" (hide it), not as a
  page error.
- Keep the TS types in the same task as the Java records (hard rule).

### F13. MEDIUM (read). No preference-ordering tests are specified (lessons #1)

`RivalryDiscoveryTest` is named but its assertions aren't. Every claim here is a ranking, which is
the class where a well-formed sign inversion passes structural tests.

`PairRecord` is oriented `a < b` **by manager id** (`:121`). So for a manager who is the pair's `hi`,
"wins" are `bWins`. A nemesis computed from `aWins` without flipping names the victim as the nemesis,
and stays green on every count/threshold test.

**Amendment.** Assert orderings explicitly:

- **Lopsided**: 4-0 above 3-1, and 3-0 above 5-2 by share.
- **Closest**: 2-2 above 3-2, and 3-2 above 2-1 by meetings.
- **Nemesis**: computed for a manager who is `hi` in every pair, with a 0-3 opponent above 1-3 and a
  2-1 opponent never named.
- **Victim**: the mirror.
- The F2/F3 floors.

### F14. LOW (read). Names disagree between plan and research

Plan step 4 has `HallOfFameService.rivals(managerId)`. Research R6 has `RivalsService.forManager`.
**Amendment:** pick one before tasks.

### F15. LOW (read). `ManagerRef` nullability isn't stated

The schema allows an orphan roster (`StandingRow.managerId` null, handled at `RivalryService:219`).
Count today is 0 (measured). A champion, runner-up or points leader on an orphan roster needs
`managerId: null` with no link (FR-011). **Amendment:** data-model `ManagerRef.managerId: number |
null`, with a mutable map or record, never `Map.of`.

### F16. LOW (read). A stale "unavailable" sentence on the manager page

`ManagerCareerService:126-128` says `playoffAppearances` is unavailable because "the bracket is not
parsed". After 047 the History page shows bracket-derived runner-ups. **Amendment:** either update
that reason (the bracket is stored, but appearances aren't derived) or record it under "not
building" as a known stale sentence. Don't leave it silently contradicted.

### F17. LOW (suspect). SC-006 captures must come from a frozen database

`/rivalries` includes `seasonGrid` (`currentWeek`, TBD cells) for live 2026 seasons, and the shared
5433 DB is refreshed by other sessions. **Amendment:** quickstart should state that both captures
run against the same scratch-DB snapshot, before and after, with the poller and refresh off.

## Verdict

The plan's structure is sound and mostly verified:

- the `chainGames` split is safe;
- the champion rule is the same at both sites;
- the bracket p=1 data agrees with stored champions 3/3;
- past superlatives compute cheaply.

**Before tasks**, it needs five fixes:

- **F1**: research R2's last-place reasoning is contradicted by measurement. (Foot) Ball Knowers is
  a toilet bowl, 7/7. This is **Allan's call**, since it changes what the feature is.
- **F2 / F3**: the discovery ranking would headline 1-meeting "rivalries" and 2-1 "lopsided" pairs,
  which is against the project's honesty rule.
- **F4**: the rivals endpoint would leak other leagues' opponents.
- **F7**: the runner-up can equal the champion when the sources disagree.

F5–F13 are amendments to fold in with "amended after review" notes. F14–F17 are cleanup.
