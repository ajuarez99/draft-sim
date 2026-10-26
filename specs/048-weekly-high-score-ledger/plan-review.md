# Adversarial plan review: spec 048 (Weekly high-score ledger)

This review read spec.md, plan.md, research.md, data-model.md, contracts/weekly-pot-api.md and
quickstart.md cold on 2026-10-25, before any code existed. It checked them against the code in
worktree `048-weekly-high-score-ledger` (main `ec72031`) and against every local branch and worktree
in git.

**Nothing was measured against data.** The shared Postgres on 5433 was not running: `netstat` showed
only the unrelated 5432 listener, and Docker Desktop was not up. So every claim about row counts,
`playoff_week_start` values or commissioner rows in past seasons is still owed to verification.md.

Each item is labelled:

- **measured**: run today, with the output quoted;
- **read**: from the code, not executed;
- **inferred**: reasoned, not run.

## Claims confirmed

| Claim | Where checked | Result |
|---|---|---|
| `allScores` gives one row per roster-week with the season manager, LEFT JOINed | `RosterWeekPointsRepository.java:232-249`, `ScoreRow` `:174` (read) | ✅ `starters_points` is `numeric(8,2)` (`V5__…sql:35`), read as `BigDecimal`. `managerId`, `manager` (display name) and `avatarId` are null for an unowned roster. There's no team name, so the plan's members join is needed. |
| Sleeper writes a row for **every** roster every scored week | `LeagueHistoryIngestService.java:285-314` (read) | ✅ The loop upserts every `roster_id` that `/matchups/{week}` returns, for weeks `1..last_scored_leg`. An abandoned roster gets a row with `points` 0.0, so it counts as a payer (see F12). A bye (null `matchup_id`) still gets a score row. |
| ESPN writes rows only for decided matchups, and a bye's home side still gets one | `EspnLeagueIngestService.java:509-531` (read) | ✅ A week is recorded final only when every matchup is decided (`:529`). A partly decided week holds rows for only some rosters, and `ScoredWeeks` excludes it once any POINTS fetch row exists. |
| Median-match leagues don't change the score rows | `PlayoffFormat.medianMatch` is read only for W-L (`LeagueRepository.java:325-367`) (read) | ✅ `roster_week_points` holds the real total either way. The pot needs no median handling. |
| `ScoredWeeks.of` + `regularSeasonBound` | `ScoredWeeks.java:56-69`, `ExpectedWinsService.java:386-392`, `WeekFinality.java:53-57` (read) | ✅ Finals are a subset of stored weeks. The last regular-season week becomes final once `leg` moves past it, so the championship-week caveat (`ScoredWeeks` javadoc `:24-27`) never touches a regular-season week. `regularSeasonBound` is `through(start-1)` only when `playoff_week_start >= 2`, else `ALL_WEEKS`, as R3 says. Neither `ExpectedWinsService:403` nor superlatives handles it any other way. |
| Visibility and the commissioner gate | `LeagueMembership.java:187-211` (read) | ✅ `visibleLeague` addresses the exact row (`bySleeperId`). A blank header sees nothing unless `X-Admin-Token`. `canCommission` = app owner OR a `league_member.is_commissioner` row for the caller's manager in **that** row (ESPN via `manager_link`). The admin token can see the league but can't commission it. That's fine: it's the same as the conduct list. |
| The conduct list's 403 shape | `SuperlativesController.java:179-188` (read) | ✅ The body is `{message, commissionerKnown}` from a `LinkedHashMap`. `ManagerRepository.caller` (`:141-147`) gives `updated_by`; it can return the header's own manager id even outside the league, but the FK to `manager` still holds. |
| A past season's row records its commissioner | `LeagueHistoryIngestService.java:111,155-167` (read) | ✅ `is_owner` is written per chain row, **but only on seasons the ingest doesn't skip** (`:102-110`). See F13. |
| V41 is free | `git for-each-ref refs/heads refs/remotes` + `git ls-tree` on each, and `ls` of every `git worktree list` path (measured) | ✅ No ref or worktree has any `V4[1-9]__` or higher. The highest on `origin/main` is `V40__league_losers_bracket_match.sql`. V41 is additive (`create table`), so lessons #22's shared-DB hazard doesn't apply. Flyway ignores a future migration on older branches. |
| CORS allows PUT and `X-Sleeper-User` | `WebConfig.java:51,60` (read) | ✅ Lessons #6/#14's preflight class is already covered; spec 040's conduct-list PUT uses the same path. |
| `apiFetch` sends the identity | `web/src/api.ts:224-233`; errors `:180-198` (read) | ✅ `X-Sleeper-User` comes from `currentUserId()`. `apiError` surfaces `message` from a `{message}` body, so a 400/403 sentence reaches the page with the generic `json<T>` helper. |
| destinations.ts is the one registry, and the test enforces it | `destinations.ts:1-26`, `destinations.test.ts:207-226` (read) | ✅ The rail, `searchIndex.ts` and the switcher all read `destinationsFor`, so the palette needs no extra entry. The test fails if App.tsx adds a `/leagues/` route with no row. |

## Findings

### F1. HIGH (read + inferred). The fallback rule breaks US2's scenarios 1-2; per-component greedy makes it unnecessary

R5: when greedy uses more transfers than pairwise, return the pairwise list as the shortcut
(`shortcutIsPairwise: true`). Two problems:

1. **US2 scenarios 1 and 2 are false for any pairwise list.** In this game, an untied pair's net is
   `stake × (weeksWon_a − weeksWon_b)` (each pays the other one stake for every week the other won).
   Every roster owes every roster with more wins. So:
   - a **+$5 roster owes a +$20 roster** in the pairwise view, which breaks scenario 1 ("every
     payment goes from a negative balance to a positive one");
   - a **$0-balance roster** (median wins) both pays and collects, which breaks scenario 2 ("a
     manager at exactly $0 appears in no payment").

   If the fallback ever fires, the *shortcut* inherits both failures.
2. **The fallback can't be needed if greedy runs per connected component of the pairwise graph.**
   - Each pairwise component is zero-sum: its nets are sums of pair nets internal to it.
   - Greedy on a zero-sum set of `n` non-zero balances uses at most `n − 1` transfers, because every
     step zeroes at least one balance and the last step zeroes two.
   - A connected component with `m ≥ n` nodes has at least `m − 1` pairwise edges.

   So greedy per component is ≤ pairwise, component by component. That's a proof, not a heuristic.
   And in this game the pairwise graph is complete multipartite by weeks-won, so it is connected
   whenever two distinct win counts exist (inferred).

**Amendment.**
- R5: run greedy within each pairwise component, and delete the fallback and `shortcutIsPairwise`
  from the plan, data-model and contract.
- Scope US2 scenarios 1-2 explicitly to the **shortcut** list, with a dated amendment note in
  spec.md. The pairwise view can't satisfy them by construction.
- Give greedy a deterministic tiebreak (equal magnitudes broken by `rosterId` ascending), so the
  list doesn't reshuffle on reload. RecordBook's tiebreak javadoc (`RosterWeekPointsRepository:185-188`)
  is the precedent.
- Unit tests:
  - shortcut ≤ pairwise;
  - shortcut never touches a $0 roster;
  - shortcut always goes negative → positive;
  - both lists clear every balance.

### F2. HIGH (read). The live leader shows a 12-way tie at 0.0 every time a week hasn't started

R9 shows `liveLeader` when the live read is OK, the week is regular-season, and it isn't final. Two
facts make that wrong for part of every week:

- `LiveWeekSource.fetch` returns `leg`'s rows with `points` defaulted to 0.0 (`LiveWeekSource.java:253-265`).
- A newly rolled `leg` comes back with every roster at 0. `LeagueHistoryIngestService`'s javadoc
  (`:331-334`) says an unplayed week returns "every roster present … `points: 0.0`".

So the page would print "Leading week 8 (not final): 12 co-leaders, 0.0". Spec 045 already met this:
`WeeklyReportService.decideLive` (`:477-484`) requires the week to have **started** (`any points >
0`, or a stored `player_game` row) before treating it as live.

**Amendment.** R9: `liveLeader` only when some live row has points > 0, the same test as
`decideLive`. Reuse it, don't re-derive it (lessons #25). Otherwise return null with no
`liveUnavailable`. Also define the live tie rule: `rosterIds` holds every roster at the max, with
the same compare as decided weeks.

### F3. HIGH (read). Use `peek`, not `current`. The balances don't depend on the live read

The plan calls `liveWeekSource.current(row)`. That blocks the page request on Sleeper for up to
2.5 s (`application.yml:48`) on every cache miss. The cache TTL is 60 s (15 s after a failure)
(`config/weights.yml:322-323`). So the first viewer each minute per league waits up to 2.5 s for
a one-line extra.

`LiveWeekSource.peek` exists for this case (`:190-204`: "for callers whose answer does not depend on
the live read"). `WeeklyReportService:454-472` uses `current` only when the answer depends on it.
The ledger's standings never do: FR-010 says the live week moves no balance.

**Amendment.** Plan step 4 and R9: `peek(row)`.
- `liveUnavailable` is true only when the cached answer is FAILED.
- A cold cache gives `liveLeader: null` with `liveUnavailable: false`. The first view after a cold
  start or expiry has no live line and the next one does; WeeklyReport accepts the same cost.
- The PUT response must also use `peek`.

### F4. MEDIUM (read). There's already a shared roster-naming helper, and the contract has no `isMe`

The plan copies "Expected wins' pattern" (`ExpectedWinsService.java:411-428`). That's a *third*
copy. `RosterOwners.ownerNames` (`engine/RosterOwners.java:26-43`) is the extracted "one
implementation" (spec 019 review F11). Its rule is team name → manager name → "Roster N", plus
`isMe`. LockInService and PlayerStatsService use it.

Separately, plan step 8 highlights "the viewer's own row", but the contract has no `isMe` or viewer
roster id. The web could only match by username, and `RosterOwners`' javadoc forbids that ("never a
username match").

**Amendment.**
- Name rosters with `RosterOwners.ownerNames(rosterSeasons.forLeague(id), members.forLeague(id),
  membership.callerManagerIn(id, user).orElse(null))`.
- Take `managerId`/`username` from the same `StandingRow`s.
- Add `isMe: boolean` to `Balance`, or a top-level `viewerRosterId: number | null`, in Java and
  `api.ts` together.

### F5. MEDIUM (read). The roster directory: week-1 live leaders and "every manager" can't be named

The contract says `balances` holds "every roster with a row in any counted week". `weeks[].winners`
and `liveLeader.rosterIds` are bare roster ids, so the page names them by looking them up in
`balances`. Two consequences:

1. In week 1 of a season (or any season with no final week), `balances` is empty, so US4's live
   leader **can't be named**.
2. FR-005 says the sheet lists "every manager in the league". A season with zero decided weeks
   lists nobody.

**Amendment.**
- Build the balance rows from `roster_season` (every roster, $0 and 0 weeks won until it plays).
  Or add a separate `rosters` directory that weeks and the live line resolve against.
- Fixture test: a season with no final week plus a live week names its leader.

### F6. MEDIUM (read). Missing weeks, the "through week N" label and `seasonFinal` all come from the wrong count

The rules involved:

- The plan sets `seasonFinal = complete && throughWeek == boundWeek`.
- The contract says `throughWeek` = highest counted week.
- The spec's edge case says a week with "no data was ingested" is listed as "no scores recorded".

`allScores` returns only weeks that have rows, so the plan's `NO_SCORES` covers only a week of
stored zeros. That causes three problems:

- A week with **no rows** is silently absent. Page and payload still say "through week 7" with week
  5 missing, which is memory's "count and label, one source" class.
- `throughWeek == boundWeek` is true with a gap in the middle.
- A week that's stored but **not yet final** between two final weeks is dropped with no word. The
  common case is the rollover window: Sleeper's `leg` has moved and the next refresh hasn't run.
  The page then reads "through week 6 · leading week 8", and week 7 vanishes (inferred from
  `WeekFinality.java:53-57` + `LiveWeekSource.java:43-44`).

**Amendment.**
- Walk weeks `1..min(boundWeek, max stored or live week)` and give each a status: `DECIDED`,
  `NO_SCORES` (rows, all zero), `NOT_FINAL` (stored, not final) or `MISSING` (no rows, earlier than
  a final week).
- `throughWeek` = the highest week such that every week up to it is `DECIDED` or `NO_SCORES`.
- `seasonFinal` = every week `1..boundWeek` is decided-or-no-scores (`regularSeasonKnown`), else
  `row.complete()`. This also lets the ledger say "final" when the playoffs start, instead of
  waiting for the championship.
- Align the `NO_SCORES` test across documents: the plan says "max is 0", data-model says "every
  score is 0". They differ when the max is 0 and someone is negative (NFL D/ST, IDP). Pick "every
  stored score is 0" (the spec's wording) and test the negative case.

### F7. MEDIUM (read). SC-005 can't be verified as written: the app already shows dollar amounts

SC-005: "A league with the ledger off shows no dollar amounts anywhere in the app." FAAB bids
(`components/AwardWinners.tsx:290`, "Waiver ($N)") and auction prices (`pages/AdpCompare.tsx:56-242`)
already render `$` amounts for every league.

Also, under P2 the rail shows the "Weekly pot" row with glyph `$` for every league. And plan step 8
shows the commissioner the stake input (a dollar amount) while the ledger is off.

**Amendment.** Restate SC-005 and FR-001 in spec.md with an amendment note: "with the ledger off, no
*weekly-pot* amount (stake, balance, pot, payment) is shown to anyone except the commissioner's own
stake control". Quickstart scenario 1 checks the off payload and page for pot figures, not for `$`.

### F8. MEDIUM (inferred). "Every figure doubles at $2" is false whenever a tie splits unevenly

US3's Independent Test says turn on a $2 stake and "confirm every figure doubles". Quickstart 7 says
the same. Under R4, a 3-way tie works out like this:

| Stake | Shares |
|---|---|
| $1 (100¢) | 34/33/33 |
| $2 (200¢) | 67/67/66 |
| $1 doubled | 68/66/66 |

So doubling the $1 figures doesn't give the $2 ones. The claim holds only when every tie splits
evenly.

**Amendment.**
- Quickstart 7: assert doubling only for untied weeks. For tied weeks, assert R4's formula directly.
- Add a unit test with a 3-way tie at 100¢ and 200¢.
- Restate US3's Independent Test in spec.md.

### F9. MEDIUM (read). ESPN leagues: the page isn't gated, the bound can be in the wrong units, and the missing live line goes unexplained

The plan adds the route with "no gate", so ESPN leagues get the pot. Three things are unchecked:

- **The bound.** ESPN's `playoff_week_start` is `matchupPeriodCount + 1` (`EspnTranslator.java:141`),
  in **matchup periods**. ESPN NFL `roster_week_points.week` is the **scoring period**
  (`EspnSeason.java:43`). For an NFL league whose periods span several weeks
  (`espn_multi_week_periods`, `LeagueAvailability.java:153-156`), `regularSeasonBound` cuts at the
  wrong week, and "the week's top scorer" is per scoring week, not per matchup.
- **The live line.** `LiveWeekSource` returns empty for ESPN (`:174`), so an ESPN page never shows a
  live line and says nothing about why.
- **Precedent.** Expected wins and superlatives, the two pages whose rules the plan reuses, are
  `PLATFORM_NOT_SUPPORTED` for ESPN (`LeagueAvailability.java:104-106`). Memory still lists ESPN's
  happy path as unverified.

**Amendment.** Choose one, and say which in the plan:

- (a) Add a `weeklyPot` key to `LeagueAvailability.ROUTES` (`"weekly-pot"`) and refuse
  `MULTI_WEEK_PERIODS` for it. Add `gate="weeklyPot"` in App.tsx. The off-state explains that the
  live leader follows Sleeper leagues only.
- (b) Fixed-unavailable for ESPN in v1, like Expected wins.

Either way, the spec's "no provider-specific work" assumption gets an explicit sentence.

### F10. LOW (read). The destinations row is under-specified

Plan step 7 lists key/group/glyph/sports/label/href. `LeagueDestination` also requires:

- `match: /^\/leagues\/([^/]+)\/weekly-pot\/?$/`;
- `idKind: 'league'`, `requiresStatus: null`, `isAction: false`, `inRail: true`
  (`destinations.ts:62-104`);
- the `DestinationKey` union to gain `'weeklyPot'` (`:44-46`; `LeagueRailSection.tsx:14,184,355`
  types against it).

`destinations.test.ts:175-191` will check that the href uses `ctx.season`, which matches R8. No
LeagueHome or searchIndex change is needed. **Amendment:** list the full row in the plan.

### F11. LOW (read). Money input and the TS shape

- The stake input is in dollars and saved as cents. Convert with `Math.round(Number(v) * 100)`,
  never truncation (`0.29 * 100 = 28.999…`), and reject more than 2 decimals client-side. Jackson
  coerces a float to `int` by default (`ACCEPT_FLOAT_AS_INT`, inferred: Spring Boot doesn't change
  it), so `stakeCents: 150.7` would silently save 150.
- Request record: `Boolean enabled, Integer stakeCents` (boxed, so "missing" is detectable). The
  range check runs before the DB, as `validateWeeksAndReason` does, so the V41 CHECK can't surface
  as a 500 (spec 040 F10).
- `api.ts`: the off response omits the ledger fields, so type it as a union on `enabled` (or the
  fields as optional). A required-but-absent field is exactly the silent stale-type class in
  AGENTS.md.
- Tie compare: use `BigDecimal.compareTo`, not `equals`. Test fixtures built with
  `new BigDecimal("150.1")` and `"150.10"` are unequal under `equals`.

### F12. LOW (read). Zero-score and orphan rosters pay: say so

- A Sleeper roster nobody set a lineup for still gets a row (F-confirmed table) and pays a stake.
  FR-004 says "rosters that scored that week". The data-model reads that as "has a row", which
  matches US1's 11 × stake.
- An orphan roster pays and collects too (spec edge case). Its debts have nobody to pay them,
  which is fine as a ledger, but the contract has no flag for the "had no manager" note.

**Amendment.** State "a row, even 0.0, is a payer" in data-model. Render the orphan note from
`managerId == null` (or add `noManager: true`).

### F13. LOW (inferred, to measure). A past season may have no commissioner recorded

`league_member.is_commissioner` is written only when a season is ingested without being skipped
(`LeagueHistoryIngestService.java:102-111`). A season that was `loaded_complete` before
`league_member` existed would have no rows, so `commissionerKnown: false` and nobody but the app
owner can turn its ledger on.

Also, `canCommission` needs the caller to be in **that** row (`ManagerRepository.callerManagerIn`,
`:104-127`). A commissioner who joined later can't turn on a season they weren't in.

**Amendment.** verification.md runs:

```sql
select l.season, l.sleeper_id, bool_or(lm.is_commissioner)
from league l
left join league_member lm on lm.league_id = l.id
group by 1, 2
```

for the real chains, and the plan's R7 states the "joined later" limit.

### F14. LOW (read). Quickstart 4's SQL can't see a tie, and doesn't filter the counted weeks

`order by week, starters_points desc`, "top row per week", picks one of tied rows arbitrarily, and
covers playoff and non-final weeks. **Amendment:** use `rank() over (partition by week order by
starters_points desc) = 1`, restricted to `week <= regularSeasonThroughWeek` and the payload's
DECIDED weeks.

### F15. LOW (inferred). Verification preconditions

- 5433 was down today, and memory says the backend suite reports BUILD SUCCESSFUL with ITs
  **skipped** when Postgres is down. `WeeklyPotControllerIT` must show 0 skipped in verification.md.
- The pairwise list is up to N(N−1)/2 rows: 66 for 12 teams, 91 for 14. The "Every pair" tab needs
  a phone layout for that length, which plan step 8 doesn't mention.

## Verdict

The data plumbing checks out against the code:

- one score row per roster-week;
- finality and the regular-season bound compose correctly;
- the commissioner gate and the 403 shape are as described;
- V41 is free on every branch and worktree.

**Before tasks**, fold in:

- **F1**: the settle-up fallback contradicts two of US2's own scenarios. Per-component greedy
  removes it with a proof.
- **F2 / F3**: the live leader would show a 12-way 0.0 tie between weeks, and would block page
  loads on Sleeper for a line that moves no money.
- **F4–F6**: the third naming copy and the missing `isMe`, the roster directory, and week
  status/labels from one source.
- **F7 / F8**: two acceptance criteria are false as written (SC-005's "no dollar amounts", and "every
  figure doubles").
- **F9**: decide ESPN explicitly. **This one is Allan's call**: it changes whether ESPN leagues get
  the feature at all.

F10–F15 are amendments to fold in with "amended after review" notes.
