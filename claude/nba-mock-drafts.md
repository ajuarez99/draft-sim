# NBA mock drafts: removing the last "Soon"

Status: **built and verified live 2026-09-14.** Read
[What live verification found](#what-live-verification-found) at the bottom
first: it holds two bugs no test in this plan would have caught, and one
pre-existing limitation that changes what an NBA mock is actually worth.

The mock draft room was the one feature `claude/multi-sport-and-rebrand.md`
listed as a Non-goal and never came back to. `StartMockModal.tsx:28` shipped
`MOCKABLE_SPORTS = ['nfl']` and rendered the NBA chip disabled with a "Soon"
badge — the only place in the app that admitted to a missing feature.

## What I expected to find, and what is actually there

The gating comments across the code described a much larger hole than existed.
Everything below is the state **as found, before this work** -- each row checked
against the running database (`draftsim-pg`) or against the source, not assumed.
The "Build" section that follows is the plan as written; what actually landed
differs in the two places [live verification](#what-live-verification-found)
names.

| Claimed blocker | Where it is claimed | Reality |
|---|---|---|
| "`MockDraftRepository.SessionRow` has no sport column" | `MockDraftService.java:198` | **The column exists.** V6 added `mock_draft_session.sport text not null default 'nfl'`. It is never written and never selected — the row *type* is what's missing, not the column. |
| No NBA board to draft off | implied by `buildContext`'s hardcoded `Sport.NFL` | **535 blended NBA board entries** (`adp_snapshot`, source `blend`, captured 2026-09-09), built from 958 `sleeper_search_rank` rows and 336 observed picks across 3 real NBA drafts. |
| No NBA manager profiles to seat | — | **42 fitted NBA profiles** in `manager_profile`. |
| Basketball need model missing | — | `BasketballRules` is complete, and is the most rigorously verified class in the repo (transversal-matroid greedy, checked against a brute-force oracle over 168 real roster states). |
| Frontend is football-shaped | — | `positions.ts`, `pickRun.ts`, `playerName.ts` and `teamNeeds.ts` are already keyed by sport. `DraftBoard`, `PickFeed` and `OnTheClockPickInput` all **already take a `sport` prop**. |

So this is not a feature build. It is **removing three hardcoded `Sport.NFL`
literals in one service, three `sport="nfl"` literals in one view, and a
one-element frontend constant** — plus the one thing below that is a real gap.

## The one real gap: the 2026 NBA draft reverses in round 3

```
 id  | sport | season | rounds | reversal_round | status
-----+-------+--------+--------+----------------+-----------
 230 | nba   |   2026 |     14 |              3 | pre_draft
 231 | nba   |   2025 |     14 |              0 | complete
 232 | nba   |   2024 |     14 |              0 | complete
```

The draft a basketball mock would actually be practice *for* — the only one
still `pre_draft` — is a third-round reversal. `LeagueShape.toSettings()`
hardcodes `reversalRound = 0`, and `mock_draft_session` has no column for it.

`DraftSlot` already implements reversal-aware snake order (V7,
`DraftSlotReversalRoundTest`), and `LeagueSettings` already carries the field.
Only the mock's own path drops it on the floor.

**Without this, an NBA mock diverges from the real draft at 3.01 and never
re-converges** — every pick slot from round 3 on belongs to the wrong manager.
It would look completely correct on screen. This is the reason the feature is
worth doing carefully rather than by deleting a constant: the mock exists to
rehearse a specific draft, and a mock with the wrong pick order rehearses a
draft that will not happen.

## Build

### Backend

1. **`LeagueShape` carries its sport and reversal round.** Today the record is
   football by construction: `STANDARD_ROSTER` is
   `QB/RB/RB/WR/WR/TE/FLEX/FLEX/K/DEF + 5 BN`, `STANDARD_ROUNDS = 15`, and
   `toSettings()` writes `Sport.NFL, ..., 0`.
   - Add `sport` and `reversalRound` fields.
   - `standardRoster(Sport)` / `standardRounds(Sport)`. The NBA template is not
     invented — it is the real league's own, read live from
     `league.roster_positions` for all three NBA seasons:
     `PG,SG,G,SF,PF,F,C,UTIL,UTIL,BN,BN,BN,BN,BN` (14 slots, matching
     `BasketballRules`' hardcoded nine starting slots + 5 bench).
   - Keep `standard(int teams)` as the football default so existing callers and
     tests are untouched; add `standard(Sport, int)`.

2. **V14: `mock_draft_session.reversal_round int not null default 0`.** The only
   migration needed — `sport` is already there.

3. **`MockDraftRepository`** — `SessionRow` gains `sport` + `reversalRound`;
   `createSession` writes them; `find`/`lockForUpdate` select them.
   `SessionSummary` gains `sport`, which the mock-drafts list needs anyway: the
   design handoff's requirement that "every mock row is uniquely identifiable at
   a glance (sport, source league, size, progress, recency)" cannot be met today
   because nothing on the wire carries the sport.

4. **`MockDraftService`** — delete the `settings.sport() != Sport.NFL` refusal at
   `:202`; `submitPick` reads `players.idsBySleeperId(row.sport())`;
   `buildContext` reads `boards.currentBoard(shape.sport())` and
   `profiles.fit(shape.sport())`. The fork path (`createSessionFromDraft`)
   already derives correct settings via `LeagueRepository.toSettings` — sport and
   reversal round included — so it only needs the refusal removed.

5. **A guard that replaces the one being deleted.** Refusing non-NFL was doing
   real work: it failed early instead of confusingly. Its replacement is an
   empty-board check — if `boards.currentBoard(sport)` is empty, refuse with
   "no {sport} board has been built yet" rather than starting a session whose
   every pick has nothing to choose from.

6. **`CreateRequest` carries `sourceSleeperLeagueId`, not `sourceLeagueName`.**
   (Sleeper's own league id string, which is what the frontend already holds.) Today
   the from-scratch path clones only the team count and copies a display string.
   NBA needs the roster, rounds and reversal round off that same league, and the
   name is then derivable from the id — one field instead of two describing the
   same league, per the two-implementations-of-one-rule lesson in
   `claude/multi-sport-landmines.md`. The stored column stays as-is.

### Frontend

7. `StartMockModal` — delete `MOCKABLE_SPORTS`, the `disabled`, the "Soon" badge
   and the two explanatory copy branches. Pass `sport` and `leagueId` through the
   router handoff.
8. `MockSetup` — handoff gains `sport`/`rounds`/`leagueId`; `getManagers(sport)`
   instead of the hardcoded `'nfl'` (42 NBA profiles are waiting); the summary
   line names the sport.
9. `MockDraftView` — `sport={state.sport}` in the three places currently reading
   `sport="nfl"`. `MockSessionState` gains the field.
10. Mock-drafts list rows — render the sport badge the handoff specified, now
    that `MockSessionSummary.sport` exists.

### Data

11. **Rebuild the NBA board before calling this done.** The blend is from
    2026-09-09; football's is 2026-09-13. Not a blocker, a staleness fact.

## Honesty

The NBA board is thinner than football's and should say so rather than look
identical. Football blends three sources; basketball blends two, because
`FfcAdpService` refuses to write NBA rows (there is no free basketball ADP feed)
and `BoardService.loadFfc` returns an empty map for any non-NFL sport. So an NBA
board is `sleeper_search_rank` (popularity, not scoring- or size-aware) plus
observed pick order from three drafts, with the FFC weight renormalized away.

That is the same class of caveat `SimulationResult.Confidence` already carries
and must not be quietly dropped: an NBA mock's bots are picking off a board with
no market signal in it at all.

`dropOffRoster`'s second clause ("or the wider market drafts him anyway") is also
FFC-backed, so for basketball it degrades to `team != null` alone. 958
search_rank rows became 535 board entries, about right for 30 NBA rosters — but
it means a real prospect Sleeper has not assigned a team to is dropped from a
basketball board where football would have kept him.

## Not in scope

- Any new ADP source for basketball. Still a Non-goal.
- Live draft poller / SSE for basketball.
- Changing `SUPPORTED_TEAM_COUNTS`. 14 × 14 = 196 picks against 535 board
  entries is comfortable; the football cap's board-depth reasoning does not bind
  here.

---

## What live verification found

Driving the real API and then the real UI, against the real database. Both bugs
below passed every test written from the plan above, and both would have shipped.

### 1. A fourth hardcoded `Sport.NFL`, on the one path the tests didn't exercise

The plan named three sites. There were four. `buildState` loads the board itself
when it has no `DraftContext` to reuse — which is exactly the plain
`GET /api/mocks/{id}`, i.e. **every page load of the room**:

```java
List<BoardEntry> board = ctx != null ? ctx.board() : boards.currentBoard(Sport.NFL);
```

Create and pick both pass a real context, so both behaved perfectly. The symptom
was an NBA mock whose player list was football players: the API refused my own
pick with `unknown sleeperPlayerId: 9221`, and 9221 turned out to be Jahmyr
Gibbs. A basketball draft room offering Jahmyr Gibbs is precisely the failure the
deleted `Sport.NFL` refusal existed to prevent, reintroduced two feet away from
where it was removed.

`gettingAnNbaSessionListsNbaPlayersAsAvailable` now covers it, and was confirmed
to fail against the old line before being kept.

### 2. The board grid drew plain snake while the highlights used the reversal

`DraftBoard` already takes a `reversalRound` prop and already computes cell pick
numbers with it — that work landed with the live room. `MockDraftView` never
passed it, so it defaulted to 0. The result: `myPicks` came from the backend
reversal-aware (1, 24, **36**, 37, …) while the grid laid cells out in plain
snake, so from round 3 on **the user's own highlighted picks rendered in Bot 12's
column**. Visible immediately in a browser; invisible to every assertion in this
plan.

Fixed by putting `reversalRound` on `MockSessionState` and passing it through.
The lesson is the repo's own: two implementations of one rule, where the second
one silently defaults.

### 3. Every NBA manager is NEUTRAL — and cannot currently be otherwise

Not a bug in this work, and not fixable inside it, but it changes what the
feature is worth:

```
 sport | picks | with_adp
-------+-------+----------
 nba   |   336 |        0
 nfl   |   960 |      600
```

**Zero of 336 basketball picks carry an `adp_at_time`**, so `ProfileService` has
nothing to score them against and all 42 NBA managers come back `NEUTRAL` (NFL:
37 of 42 `FITTED`). Seating a real manager in an NBA mock therefore gets you a
league-average bot wearing their name.

This is the `adp_at_time` footgun in `claude/lessons.md`, in its unrecoverable
form: `backfillAdpAtTime` can only reach back as far as the board snapshots go,
and the NBA blend's earliest is 2026-09-08 while both completed NBA drafts are
2024 and 2025. Those picks can never be backfilled from data this project holds.

`MockSetup`'s header now says so ("No NBA manager has enough drafted history to
model yet, so every seat drafts league-average either way") rather than promising
tendencies it cannot deliver — the per-seat hint already read "(no data -- same
as a bot)", so the page was contradicting itself.

**What would fix it:** capturing `adp_at_time` at pick time during the 2026 NBA
draft, which the live poller already does for football. That draft is still
`pre_draft`. After it completes, NBA profiles become fittable for the first time.

### Verified working

- A 12-team NBA mock cloned from the real 2026 league: sport `nba`, 14 rounds,
  roster `PG,SG,G,SF,PF,F,C,UTIL,UTIL,BN×5`, `reversal_round = 3` persisted.
- Slot 3's pick list is `[3, 22, 34, 39, …]` — 34 in round 3, not the 27 plain
  snake gives. Round 2 ends 3→2→1 and round 3 opens 12→11→10: a true reversal.
- Bots draft real NBA players (Giannis, Scottie Barnes); a user pick resolves
  through the NBA player index; the room's picker shows NBA positions, the NBA
  roster slots and "Fills C"/"Fills PG" team needs.
- The room's header labels the user's round-2 pick **2.12** — the reversal
  reaching the copy, not just the math.
- `sleeper_search_rank` + observed picks only: the rebuild reported
  `fromFfc: 0`, with the reason ("fantasyfootballcalculator.com is football-only")
  coming from the API itself.
