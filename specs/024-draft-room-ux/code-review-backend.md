# Code review: spec 024, backend half (bug hunt)

Reviewer pass, 2026-10-09. Read-only against worktree `draft-sim-024` (branch `024-draft-room-ux`, uncommitted vs `d145f38`).
Scope: `git diff HEAD -- backend/`, the four untracked backend files (V30, `DraftTargetRepository`, `TargetController`,
`TargetControllerIT`) and the `web/src/api.ts` hunks that mirror them.

## What was actually executed (verified) vs read (inferred)

**Ran** (Postgres on localhost:5433, targeted Gradle run):
`MockDraftServiceTest` 43/43, `TargetControllerIT` 8/8, `AccessControlMvcIT` 22/22,
`LeagueControllerSeatsOwnerConfiguredIT` 2/2, plus `ingest.*`. **0 skipped, 0 failures** (counts read from the
JUnit XML, not from "BUILD SUCCESSFUL").

**Queried the local DB (read-only):**
- `flyway_schema_history` has V30 at `success = t`.
- `mock_draft_pick_source_check` is now `('USER','BOT','LIVE','AUTO')`.
- There are 47 real `AUTO` rows across 4 sessions. **Every one is on the session's `user_slot`.** All 4 sessions are
  12-team NBA, `reversal_round = 0`, and **not forked**.
- 4 of 10 `draft` rows have `pick_timer_seconds`.

**Everything else below comes from reading the code**, and is labelled that way.

## Confirmed bugs

**None found.** I looked for each class AGENTS.md lists and did not find an instance:
- SQL Postgres refuses at runtime;
- a JDBC bind type mismatch;
- `Map.of` with a null;
- a rule implemented twice that disagrees with itself;
- a preference test pointed backwards.

## Findings

### RISK-1: auto-pick is untested on a reversal-round session and on a forked mock
**Where:** `MockDraftService.auto` (~l.450-505), `chooseAuto` (~l.530-570). **Status:** inferred correct by reading; not executed.

**How it works:** two values come from the session row, `row.reversalRound()` and `row.teams()`. They decide:
- whose turn it is: `DraftSlot.slot(pickNo, row.teams(), row.reversalRound())`;
- which completed picks make up the user's roster: `settings.reversalRound()`, from the same `LeagueShape`.

`submitPick` and the engine read the same two values. A forked mock's `LIVE` picks at the user's slot land in the
roster the same way the engine replays them.

**The gap:** every auto test uses plain snake on a from-scratch mock. So does every live `AUTO` row in the local DB.

**What would break, and how it would show:**
- If a later edit dropped `reversalRound` from either call, an NBA mock with `reversal_round = 3` would build the
  user's roster from the wrong seat's picks from round 3 on.
- That would quietly change which player "would start". Nothing would crash.

**Suggested test:** one `MockDraftServiceTest` case with `reversalRound = 3` that asserts both the slot of each AUTO
pick and a chosen player.

### RISK-2: the 10-argument `DraftRepository.upsert` overload writes `pick_timer_seconds = NULL` on conflict
**Where:** `DraftRepository.java:40-48`; the 9-argument overload at `:28` chains into it.

**Current impact:** none. The only production caller, `LeagueIngestService:195`, uses the 11-argument form; I grepped
`.upsert(` across `main`. Every remaining caller is a test fixture.

**The trap:** this is exactly the "optional param that encodes a rule" pattern. The comment admits it is
"test-fixture convenience only", but the method is public in `main`. A future production caller of the shorter
overload would wipe a stored timer on every re-ingest. Nothing would fail; the format summary would just show
"no timer".

**Options:**
- Move these overloads to a test helper.
- Or have the short form leave `pick_timer_seconds` alone on conflict, for example
  `coalesce(excluded.pick_timer_seconds, draft.pick_timer_seconds)`. Note the cost: Sleeper would then be unable to
  clear a timer through that path.

### RISK-3: `LiveDraftPoller` writes the timer on every tick, unconditionally
**Where:** `LiveDraftPoller.java:~421-425`. **Status:** read.

**Cost:** one extra `UPDATE draft` per tick per drafting draft. That adds dead tuples, the same pattern as the
`updateStatus` call next to it, so it is not new in kind.

**Failure mode:** a draft response that is ever missing `settings` would null the stored timer until the next good
tick. The format summary would flicker to "no timer".

**Fix:** a cheap guard, `... where id = ? and pick_timer_seconds is distinct from ?`, removes the churn.

**Severity:** low. Sleeper's `/draft/{id}` has always carried `settings` in what this repo has observed.

### NIT-1: `auto()` re-implements the turn and status checks instead of calling `requireUsersTurn`
**Where:** `MockDraftService.auto` (~l.455, ~l.478) vs `requireUsersTurn` (~l.572).

Same rule, same messages, two copies in one file. They agree today. A third edit, such as a new status, could
update one copy and not the other.

### NIT-2: off-turn PICK loads the board before it refuses
**Where:** `MockDraftService.auto` (~l.463-470).

On `PICK`, `boards.currentBoard` and `profiles.fit` both run before the off-turn 409. Two consequences:
- An off-turn PICK pays for a full board load.
- On an empty board, the caller gets a 400 "no board" instead of the 409 "not the user's turn".

Checking the turn first would fix both.

### NIT-3: `TargetController.resolve` defaults the sport to NFL
**Where:** `TargetController.java:~132`: `leagues.byId(...).orElse(Sport.NFL)`.

This is unreachable today, because `draft.league_id` is a foreign key. But a silent football default is the shape
this repo has shipped bugs from before. `orElseThrow(IllegalStateException)` would be honest about the impossible case.

### NIT-4: a test comment is wrong
**Where:** `AccessControlMvcIT.targetsReadIsEmptyWithNoIdentityAndAMemberSeesTheirOwn`.

The comment says "a 409 here would mean no board". `TargetController.read()` never throws on an empty board; every
target simply goes to `missing`. The assertion (`notTheScopingNotFound`) is still right.

### NIT-5: two V30 indexes are redundant
**Where:** V30.

`draft_target_live_idx` and `draft_target_mock_idx` duplicate the leading columns of the matching partial unique
indexes, `*_uq`, which already serve `(owner, scope)` lookups. They are extra write cost with no read benefit.

**Do not change this by editing V30.** V30 is applied locally, and migrations are append-only.

### NIT-6: `/auto` checks the body before ownership
**Where:** `MockDraftController.auto`.

The body check runs before ownership. A stranger sending `{}` gets a 400 instead of a 404. Nothing leaks, because
the 400 does not depend on whether the session exists.

## Areas checked and found sound

**`submitPick` after the refactor** (read the diff line by line). Its behaviour is identical:
- The status check, the turn and slot computation (with `row.reversalRound()`) and the error messages are unchanged.
- The only reorder is `readSeats` moving ahead of the status check. That is a pure JSON parse.
- `recordUserPick` writes the same row (`round = DraftSlot.round(pickNo, teams)`, `source "USER"`) and makes the same
  `completed.put`.
- `shapeOf(row)` builds the same `LeagueShape` as before.

**`auto()` transactions and locking.**
- `@Transactional` on a public method, called through the proxy, with one `lockForUpdate`, the same as `submitPick`.
- A mid-FINISH exception rolls back everything.
- `mayUse` runs before the lock, the same as `submitPick`.

**`auto()` loop.**
- Each iteration consumes at least one pick, and the loop is bounded by `totalPicks + 1`.
- The bots-first branch, for a FINISH that isn't the user's turn, is reachable only when the state is forced.
- When the board is empty, the draft is marked COMPLETE at `pickNo`, matching how `MockDraftEngine` ends one.
- A `ctx` rebuilt from a copy of `completed` after each user pick is what the engine needs. `chooseAuto` reads only
  the parts of `ctx` that don't change within a request (byId, rules, settings, cfg, `valueOf`) and takes the
  drafted set from `completed`.

**`chooseAuto` against `PickDecider`.**
- The roster comes from this seat's completed picks via `ctx.byId`, using the same `prepareLineup(roster, settings,
  ctx::valueOf)`.
- It applies the same `isDraftable(e, lineup, round, settings.rounds())` gate.
- `benchFloor` is `ctx.cfg()`, which is `scoring.forSport(sport)`, the same object `FootballRules` and
  `BasketballRules` floor `rosterNeed` with.
- Already-drafted targets and targets off the board are skipped (`drafted` set, `byId` lookup). So are targets that
  fail the gate.
- Targets are stored per sport (ids are validated with `idsBySleeperId(sport)`), so a cross-sport id cannot slip in.

**The preference-ordering tests are real.**
- Tests (a), (b), (d) and the NBA pair each assert which player id was picked.
- The NBA pair differs only in the value of the user's center; the two rosters have identical position counts. So
  `isDraftable` gives the same answer for idx 8 in both. The (c) case therefore fails under `> 0`, as A1 requires,
  and does not pass because the gate happened to block the guard.

**`DraftTargetRepository.replace`.**
- The advisory lock is taken in the transaction's first statement. Under READ COMMITTED, the following `delete`
  snapshot sees the previous writer's commit.
- The race test shares a player between its two lists, so without the lock it would hit the partial unique index.
  It runs 15 rounds and passed.
- A `hashtext` collision, whether 32-bit or from the `owner:` string aliasing, can only serialize two unrelated saves.
  It cannot corrupt anything. No other code in `main` uses advisory locks, so there is no cross-feature key space
  to collide with.
- Rank is 0..n-1 from list order, and binds are typed (`setLong` / `setString` / `setInt`).

**`TargetController`.**
- Anonymous GET returns an empty list. Anonymous PUT returns 401 before any other check, admin token included.
- A non-member, an unknown draft, or someone else's mock gets 404 on both verbs (`visibleDraft` / `usableSport`).
- Neither or both scopes is a 400. So are a duplicate id, an unknown id, more than 50 ids, and a null list.
- `missing` keeps the stored name and invents no ADP.

**`createSessionFromDraft`** copies the caller's list only, inside the same transaction (`@Transactional` joins), and
skips the copy for an anonymous caller.

**`DraftRepository`.**
- The `pick_timer_seconds` bind is `Types.INTEGER`, and is null-safe.
- `updatePickTimer` binds `setNull(INTEGER)`.
- `format()` maps a SQL null to a Java null.
- The new `draft_type = excluded.draft_type`: the only production caller passes Sleeper's own type, and the column is
  already NOT NULL from V1. No behaviour change beyond the intended one.

**`LeagueController`.** `seats()` puts the nullable fields into a `LinkedHashMap`, so there is no `Map.of` null trap.
The IT asserts that the key is present with a null value.

**V30.**
- It is the next number after V29, and the constraint name is dropped and re-added as V4 did.
- Cascade on mock, no FK on the draft id (by design), and `player` FK. Nothing in `main` deletes players or mock
  sessions.

**`api.ts`.** It mirrors the Java side field for field:
- `SeatsResponse.draftType?` and `pickTimerSeconds?` are `| null`;
- `MockPick.source` gains `'AUTO'`;
- `DraftTargets.players` / `missing` match `TargetController.DraftTargets`;
- the request shapes match `PutRequest` and `AutoRequest`.
