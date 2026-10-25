# Spec 047: bug-hunting code review

Reviewer: a separate pass (Opus), 2026-10-24. Scope: the uncommitted diff on branch `047-hall-of-fame` against
origin/main ebf4ee8, untracked files included. This is not a style pass. Evidence labels:
- **VERIFIED**: I ran it (scratch DB 5437, a backend on 8088, live Sleeper API, gradle/vitest).
- **READ**: reasoned from the code.
- **SUSPECT**: plausible, but not reproduced.

**Result: no B-level defect found.** Four should-fix items and seven nits. Nothing here changes what the feature
*is*.

## Bugs (wrong output / crash / leak)

None found. See "Checked, no defect" at the end for what was exercised.

## Should-fix

### S1. The playoff list's "can't tell" note always blames a missing bracket (READ)
- **Where:** `web/src/pages/Rivalries.tsx` `NotableRivalries` (the `unsplit` string). The data comes from
  `RivalryService.unsplitSeasons()` (`RivalryService.java`, about line 335), which keeps only `SplitNote::season`.
- **Scenario:** a season is split-noted for any of five reasons: `NO_BRACKET`, `BRACKET_PENDING`,
  `ROUND_TYPE_UNKNOWN`, `ROUND_TYPE_UNSUPPORTED` or `ROUND_UNKNOWN`. The UI prints
  "Can't tell for 2026: the bracket isn't stored." for every one of them.
  - Most likely to be hit: a playoff week of the live season scored while the next round's slot is still null
    (`BRACKET_PENDING`), or an NBA week that maps to no round (`ROUND_UNKNOWN`).
  - The matrix panel on the same page prints the correct reason text (`splitText`) for that same season, so the
    page contradicts itself.
- **Evidence:** READ. Today's scratch data has no split notes for any of the four leagues (VERIFIED: `splitNotes: []`
  on all four `/rivalries` responses), so it isn't visible yet.
- **Fix:** change the note so it can't be wrong. Either:
  - render `data.matrix.splitNotes` filtered to `playoffUnsplitSeasons`, which already carries per-season text; or
  - make `playoffUnsplitSeasons` carry `{season, reason}` and say "the playoff games for 2026 couldn't be split"
    without naming a cause.

### S2. One voting match is enough to decide the bracket's direction (READ)
- **Where:** `backend/.../engine/LastPlaceRule.java`, the direction loop (`if (w == null || l == null) continue;`,
  and `w == l` falls through without voting).
- **Scenario:** a losers bracket of 7 matches where the weeks of 6 matches lack stored points for one side, or are
  exact ties, and one match has scores. That one match sets TOILET_BOWL or CONSOLATION, and a manager is named last.
  - "Agrees on one direction" is then vacuously true for 6 of 7 matches. That is weaker than the class javadoc's
    "unless everything lines up" and the spec's "refuse on doubt".
  - A wrong direction doesn't move the name by one place. It swaps last place for roughly 7th place: the p=1 winner
    of a toilet bowl versus the bottom loser of a consolation bracket.
- **Evidence:** READ. Not reproduced on real data: every stored season has points for every bracket week.
  research R2 (amended) does literally say "every match whose weeks have stored scores … agrees", so the code
  matches the letter of the rule. The issue is the rule's floor.
- **Fix:** refuse (`NO_SCORES`, or a new `PARTIAL_SCORES`) unless every match votes. At minimum require a majority
  of matches to vote. Add a test with one scored match and six unscored ones.

### S3. Nothing checks that a match's two rosters actually played each other in the weeks it was mapped to (READ, partly VERIFIED)
- **Where:** `LastPlaceRule.java`, the week mapping. It reuses `RivalryGameKind.roundOfWeek(format, asBracket, wk)`
  with the losers bracket's own `maxRound`.
- **Scenario:** the direction is read from summed week points, and the weeks matter.
  - VERIFIED on West Coast 2025 (type 1): the p=3 match (4 v 11, `w=11`) reads TOILET from week 17 alone
    (98.32 vs 66.70). It reads CONSOLATION only when weeks 17 and 18 are summed (190.82 vs 203.08).
  - So the rule is only as good as the round-to-week alignment. That alignment is measured for 12-team / 6-playoff
    brackets whose losers bracket has as many rounds as the winners bracket. It is VERIFIED from `league_matchup`
    that 2v8 and 4v11 share matchup ids 4/5 in both weeks 17 and 18.
  - It is not measured for a losers bracket with fewer rounds than the winners bracket (for example 10 teams with 6
    in the playoffs), whose rounds Sleeper may align to the end of the playoffs rather than the start.
- **Risk:** misaligned weeks give essentially random votes. With 3–4 matches, all of them agreeing by chance is
  2·(1/2)^n, which is 12–25%. When that happens a name comes out, because rule 4's arithmetic still holds.
- **Fix:** before a match votes, require that its `w` and `l` share a non-null `matchup_id` in `league_matchup` in
  every mapped week. Otherwise refuse (`UNSUPPORTED_SHAPE`). The data is already stored, and it turns an unmeasured
  shape into a refusal instead of a guess.

### S4. Two different opponents in one nemesis/victim list can get the same React key (VERIFIED precondition, SUSPECT collision)
- **Where:** `web/src/components/ManagerRivalsBlock.tsx`, `Side`: `key={e.opponent.rosterId}`.
- **Scenario:** Sleeper hands a departed manager's roster id to the replacement. VERIFIED on the West Coast chain:
  roster 7 is aerie33 (23) in 2025 and popsharky (7) in 2026, and rosters 11 and 12 also changed hands. A former
  opponent (newest roster 7 in 2025) and a current one (roster 7 in 2026) can be tied in the same list. They then
  get a duplicate key: React warns, and on re-render it can reuse the wrong DOM node.
- **Fix:** `key={e.opponent.managerId ?? \`r${e.opponent.rosterId}\`}`. `managerId` is never null here, because
  `chainRivals` skips null-manager rosters.

## Nits

### N1. Stale docs that now contradict the code (READ)
- `specs/047-hall-of-fame/contracts/api.md` C1 still says "`lastPlaceNote` is always present: …" below its own
  amendment that removed it.
- `data-model.md`'s amended header says "every list has the `meetings ≥ N` floor". The build changed `playoff` to
  no floor (tasks.md "Amended during build"). Say so in data-model too.
- `IngestController.brackets` javadoc: "Fetches `winners_bracket` only". It now fetches both.
- `BracketIngestService.ingestIfMissing` "Residual cost" paragraph names only `winners_bracket`. See N2.

### N2. `ingestIfMissing` re-asks for a losers bracket on every pass when it can never complete (READ)
- **Where:** `BracketIngestService.ingestIfMissing`: `if (brackets.isComplete(..) && losersBrackets.isComplete(..)) return;`
- **Scenario:** a finished season whose losers bracket Sleeper answers with `[]`, or one stored with an undecided
  match (consolation games abandoned). Every chain pass then makes one `losers_bracket` call per such season,
  forever.
- **Cost:** cheap, and it's the same class as the documented winners-bracket residual, but it's new and
  undocumented.
- **Fix:** document it next to the existing residual, or skip when the league row is complete and the losers rows
  were already fetched once.

### N3. The trophy case's Awards fetch caches a failure forever (READ)
- **Where:** `web/src/components/TrophyCase.tsx` `TrophyCaseTable.loadFor`.
- A rejected `fetchSuperlatives` promise stays in `loads.current`. Closing and reopening the row shows
  "Couldn't load this season's awards." again without retrying.
- The cache also survives a `useLeagueDataVersion` bump.
- **Fix:** delete the entry in a `.catch`, and key the cache by version.

### N4. Unspecified order for same-season chains (READ)
- `RosterSeasonRepository.forManager` orders by `l.season desc` only. Manager 7's three NFL 2026 chains therefore
  come out in whatever order Postgres picks.
- contracts C3 promises "sport, then newest season". It holds, but ties aren't broken.
- **Fix:** add `, l.id` to the order by (or sort chains by head sleeper id).

### N5. An IT that stays green if the losers ingest never writes (READ)
- `HallOfFameServiceIT.nameLastPlaceOnlyWhenTheRuleCouldReadIt` accepts `NO_LOSERS_BRACKET` for FBK and NBA as a
  pass.
- The direction itself is pinned by `LastPlaceRuleTest`, which asserts rosters 2 and 10, so a sign inversion is
  caught there.
- But a regression where `BracketIngestService` stops writing `league_losers_bracket_match` would leave this IT
  green.
- **Fix:** have the IT seed or backfill the losers bracket and require roster 2 for FBK 2025.

### N6. `TrophyCase.tsx` imports `TITLES` from `pages/Superlatives` (READ)
- This ties a shared component to a page module and pulls the whole Superlatives page into History's import graph.
- **Fix:** move `TITLES` beside `AwardWinners` (or into its own module).

### N7. A behaviour change in the `finalDisagreeText` refactor, for the record (READ)
- The moved function now prints "one side" when the winner's `teamName` is null. The old inline copy printed
  "null".
- It's an improvement, but it is a change on the `/rivalries` page that the before/after capture (which covers the
  JSON only) wouldn't see.

## Checked, no defect

- **Migration:** V40 is the next free number. VERIFIED against `origin/main` and every remote branch (highest is
  V39). It's a new file, not an edit, and its columns and primary key match V33.
- **Repository SQL actually runs (lesson classes 2/3):**
  - VERIFIED: the scratch DB holds `league_losers_bracket_match` rows for 8 leagues, written by
    `replaceForLeague` through the backfill.
  - VERIFIED: `forLeagues` and `isComplete` were exercised by `/trophy-case` on 8088.
- **Tests:** `LastPlaceRuleTest` 12, `ChampionRuleTest` 4, `HallOfFameRivalsTest` 11, `HallOfFameServiceIT` 15,
  `RivalryDiscoveryTest` 19, `BracketIngestServiceTest` 9, `RivalryParityIT` 6, `IngestControllerTest` 16. All
  pass with 0 skipped (VERIFIED, `DB_URL` = 5437).
  - `npx tsc -b` is clean.
  - vitest on TrophyCase, AwardWinners, Rivalries, ManagerHistory, LeagueHistory and Superlatives: 197 passed.
- **Ordering tests (lesson #1):** they would catch a sign inversion.
  - Lopsided: 4-0 > 3-1 and 3-0 > 5-2. Closest: 2-2 > 3-2 and 3-2 > 2-1. Nemesis: 0-3 > 1-3, with the manager on
    the `hi` side.
  - `LastPlaceRuleTest` pins roster 2 (FBK) and roster 10 (WC) from real fixtures.
- **Last-place direction, on live data:**
  - VERIFIED: Sleeper's live `losers_bracket` for all four leagues matches the fixtures.
  - VERIFIED: the WC 2025 losers final and p=3 match span weeks 17 and 18 (same matchup ids in `league_matchup`), so
    summing two weeks for type 1 is correct for the losers bracket too.
  - VERIFIED: the NBA refusal comes from one match, p=3 (Sleeper says 1 beat 4; stored 234.5 vs 239.0 in the last
    week). That's the same last-week stored-score drift as the NBA finals, so refusing is the right outcome.
    Sleeper's live NBA 2025 `/matchups/19-21` now even pairs those rosters differently, which is the degraded data
    research R10 warns about.
  - VERIFIED: West Coast 2026's 14-team bracket (p = 1, 3, 5, 7) passes the consolation arithmetic: 6 + 7 + 1 = 14.
- **Trophy case output (8088, as manager 7):** FBK 2025 gives champion r1, runner-up r3, last r2 (TOILET_BOWL). WC
  2025 gives r6 / r3 / r10 (CONSOLATION). NBA 2025 and 2024 refuse last place with MIXED_DIRECTION and have
  `finalDisputed: true`. The NBA awards guard is satisfied: `/superlatives` for each past season id answers for that
  same id and season with `requestedSeason: null`.
- **Scope:**
  - VERIFIED: `/trophy-case` with no identity → 404, and a stranger → 404.
  - VERIFIED: `/managers/7/rivals` with no identity → 404.
  - VERIFIED: as aerie33 (only WC 2025) it is 200 with `chains: []`. Manager 7 never played a season aerie33 can
    see, so nothing leaks.
  - There is no forward walk: `chainBySleeperId` follows `previous_league_id` only.
- **Response hygiene (lesson class 4):** `Content-Type: application/json` on both new routes. There is no `Map.of`
  on the new response paths; they are records, and every nullable field is a boxed or nullable type.
- **CORS (lesson class 6):** VERIFIED. An OPTIONS preflight on `/trophy-case` with `x-sleeper-user` gets
  `Access-Control-Allow-Origin` and `-Headers` from the global `/api/**` mapping.
- **api.ts mirrors the records field for field:** I diffed every new record literally: `TrophySeason` (10 fields),
  `TrophyCase`, `ManagerRef`/`HofManagerRef`, flattened `PointsLeader`, `RivalEntry`, `ChainRivals`,
  `ManagerRivals`, `DiscoveredPair` (`RivalryPair` + `playoffMatches`) and `Discovery` (7 fields). `discovery?` is
  optional, and both fetchers map 404 to null.
- **`/rivalries` beyond `discovery`:** the chain loop moved into `chainGames()` verbatim (I read the diff), and
  T033's before/after capture covered it.
  - Discovery rules match R9 + A2/A3/A8:
    - floor `≥ N`;
    - lopsided `≥ 2` and wider than the closest tier;
    - every tie returned;
    - `playoffMatches` counted per `legsOfMatch` entry (per match, not per week).
  - VERIFIED on live data: FBK has eligible gaps {0, 1} and lopsided empty. NBA has closest = 16 pairs at 2-2 and
    lopsided = 4 pairs at 4-0.
- **AwardWinners extraction:** `diff -w` of the 330 removed lines against the new component shows only the
  wrapper, the imports and the moved helpers. The rendering body is identical.
- **ChampionRule:** History, careers and the trophy case all call the same function. HallOfFameServiceIT's SC-001
  and A10 equality tests pass on real data.
- **BracketIngestService:**
  - each bracket has its own skip and its own "empty never deletes" rule;
  - a null body is handled;
  - it never calls `/matchups`;
  - `ingestIfMissing` fetches only the missing half (tested).

## Fixes applied (2026-10-24)

| Finding | What changed |
|---|---|
| S2 | `LastPlaceRule`: every match must vote. A match with no stored points on either side -> `NO_SCORES`; an exact tie -> `MIXED_DIRECTION` (reused, so `api.ts` and the TrophyCase text are unchanged). Class javadoc and research.md R2 ("Amended after code review") updated. New `LastPlaceRuleTest` cases: one unscored match refuses, one tied match refuses. |
| S3 | New `LastPlaceRule.Paired` input (6th argument of `evaluate`). A match votes only if its winner and loser shared a `league_matchup` matchup id in every mapped week; otherwise `UNSUPPORTED_SHAPE`. `HallOfFameService` builds it from `LeagueMatchupRepository.between` (new constructor dependency). Test: misaligned pairing refuses on both the FBK and West Coast fixtures, and no pairing data refuses. |
| S1 | `Rivalries.tsx` `NotableRivalries`: the playoff note now prints each unsplit season's own `matrix.splitNotes[].text` (the server's text, as the grid does), with a reason-free sentence when a season has no note. No backend or `api.ts` change was needed (the notes were already on the payload). Both Rivalries tests updated; one now covers a `BRACKET_PENDING` season. |
| S4 | `ManagerRivalsBlock.tsx`: key is `opponent.managerId`, falling back to roster id plus name. New `ManagerHistory.test.tsx` case with two tied opponents sharing roster 7 (asserts no duplicate-key warning). |
| N1 | contracts/api.md C1 (no `lastPlaceNote`; per-row `lastPlace`/`lastPlaceReason`), data-model.md (playoff list has no floor; `lastPlaceNote` dropped from the TrophyCase shape), `IngestController.brackets` javadoc (both brackets). |
| N2 | Documented in the `BracketIngestService.ingestIfMissing` javadoc. No behaviour change. |
| N3 | `TrophyCase.tsx`: a rejected awards fetch is removed from the cache (reopening retries), and the cache resets when the table gets a new `data` payload (a league refresh). New test. |
| N4 | `RosterSeasonRepository.forManager` orders by `l.season desc, l.id`. |
| N5 | `HallOfFameServiceIT.nameLastPlaceOnlyWhenTheRuleCouldReadIt`: fails if a season has winners-bracket rows but no losers rows (the ingest stopped writing); asserts FBK 2025 = roster 2 TOILET_BOWL, West Coast 2025 = roster 10 CONSOLATION, NBA = MIXED_DIRECTION whenever losers rows exist, and tolerates `NO_LOSERS_BRACKET` only for a season with no losers rows. The scratch DB has all four, so every concrete branch ran. |
| N6 | `TITLES` moved to `web/src/awardTitles.ts`; `Superlatives`, `LeagueHome` and `TrophyCase` import from there. |
| N7 | No change. Recorded: the `finalDisagreeText` refactor now prints "one side" for a null team name instead of "null". An improvement; the before/after capture covers JSON only. |

### Last place after S2/S3 (strict rule)

- FBK 2025: roster 2, TOILET_BOWL (unchanged). Fixture test and `HallOfFameServiceIT` (real stored pairing).
- West Coast 2025: roster 10, CONSOLATION (unchanged). Same two checks.
- NBA 2024 and NBA 2025: refused, still `MIXED_DIRECTION` (the fixtures vote both ways; nothing became `NO_SCORES` or `UNSUPPORTED_SHAPE`).
