# Plan 064: Time Machine achievements and levels

Read `spec.md` first. Line references are to origin/main ff24e61.

## What is there now

- A game is one row of `historical_game` keyed by `sleeper_user_id` (V43), with picks in
  `historical_game_pick` and the frozen `result` jsonb written once by
  `HistoricalGameService.finishDraft` (`historical/HistoricalGameService.java:344-375`).
- `HistoricalResult` (`historical/HistoricalResult.java`) already holds what most badges need: `standings`
  (the user's W-L-T), `userFinish`, `userOutcome`, `playoffs.rounds[].matchups[].winnerSlot`,
  `hindsight.userBeatIt`, `biggestBust` (pickNo, seasonValueRank), `format` (null = old format).
- It does **not** hold a season-value rank for every user pick, or which weeks a player had a box score.
  Both are recomputable for a current game: `HistoricalSport.playedWeeks(seasonStart, ids)` gives the
  `WeekStatLine`s, `SeasonReplay.scorePlayerWeeks(scoring, played)` gives `PlayerWeeks.seasonValue`, and
  `Comparisons.valueRanks(draftedIds, value)` gives the rank the bust callout uses.
- Board rank of a pick is `HistoricalPlayerCard.rank` from the game's stored `board` snapshot.
- Web: `pages/HistoricalRoom.tsx:286` renders `HistoricalResults` once the reveal view reaches COMPLETE;
  `pages/HistoricalDraft.tsx` is the landing page with "Your games". Types live in `web/src/api.ts` (~4736+).
- Highest migration on main and on every remote branch: V47. This spec takes **V48**.

## Design

### One mechanism: lazy settle

Awards are **not** computed inside `finishDraft`. A new `HistoricalAchievementService.settle(userId)` runs at
the top of both read endpoints below. It:

1. takes `pg_advisory_xact_lock(hashtextextended('tm-ach:' || userId, 0))` so one user's settles are serial;
2. selects that user's COMPLETE games with no `historical_game_award` row, oldest `completed_at` first;
3. for each game, evaluates it (below), inserts the award row and any newly unlocked badges
   (`on conflict do nothing`), then re-reads career totals and unlocks cumulative badges;
4. each game is evaluated inside its own try/catch **and its own savepoint** (`TransactionTemplate` with
   `PROPAGATION_NESTED`, or a per-game `REQUIRES_NEW` taken after the lock - reviewer to pick); a game that
   throws is logged at error and left unsettled, and the others still settle (FR-004).

Why lazy: an achievements bug cannot break game completion, there is one code path for new and old games, and
no startup job. Cost: the first profile read after a game does the work (one game: a `playedWeeks` query and a
scoring pass that `finishDraft` already does in well under a second - **unmeasured for settle; measure it**).

### Quiet backfill

V48 adds `historical_game.pre_achievements boolean not null default false` and sets it true for every row
that is COMPLETE at migration time. A `pre_achievements` game is evaluated from its stored result only
("Stored" badges), its badges are inserted with `seen_at = now()`, and it is flagged `from_saved_result` in
its award row. If a settle batch contained only such games, `historical_profile.seen_level` is set to the
resulting level. A game that was DRAFTING at migration time and finishes later is a normal new game.

### Evaluation (pure, no Spring, no DB): `AchievementRules`

`evaluate(GameFacts) -> GameAward(xpLines, weeklyWins, playoffWins, title, perGameBadges)` where `GameFacts`
is: sport, teams, rounds, userSlot, parsed `HistoricalResult`, and (new games only) a list of
`PickFact(pickNo, round, boardRank, seasonValueRank, lastWeekPlayed)` for the user's picks plus
`seasonWeeks`. Cumulative badges are a second pure function `cumulative(CareerTotals) -> Set<Badge>`.

- weekly wins = the user's `standings.wins` + playoff matchups whose `winnerSlot == userSlot`.
- new format = `result.format() != null`; playoff/title badges require it (FR-008).
- BIG_BUST from `biggestBust`: `round(pickNo) <= 2 && seasonValueRank - pickNo >= 3 * teams`. Note the stored
  bust is only the single largest gap, so a past game can miss a qualifying round-1 bust that was not the
  largest; for new games evaluate every pick from `PickFact`. This asymmetry is stated on the badge.
- REACH = `boardRank - pickNo >= teams`. HIT: `seasonValueRank <= pickNo`. MISS:
  `seasonValueRank - pickNo >= 2 * teams`.
- LATE_GEM: `round > rounds / 2 && seasonValueRank <= teams`.
- GONE_EARLY: `round <= 3 && lastWeekPlayed <= 3 && seasonWeeks >= 10`.
- A badge's `detail` records the player name, pick number and the numbers that satisfied the rule, so the
  toast and trophy case can state them.

`Badge` is an enum carrying name, rule text, XP and source (STORED / NEW_GAMES). `Levels` holds the threshold
function and names. Both carry a header comment: hand-set, arbitrary.

### Schema (V48__time_machine_achievements.sql)

```sql
alter table historical_game add column pre_achievements boolean not null default false;
update historical_game set pre_achievements = true where status = 'COMPLETE';

create table historical_game_award (
    game_id           bigint      primary key references historical_game (id) on delete cascade,
    sleeper_user_id   text        not null,
    sport             text        not null,
    xp                int         not null,
    weekly_wins       int         not null,
    playoff_wins      int         not null,
    title             boolean     not null,
    from_saved_result boolean     not null,
    xp_lines          jsonb       not null,   -- [{label, xp}] game lines only; badge XP is on the badge rows
    settled_at        timestamptz not null default now()
);
create index historical_game_award_user on historical_game_award (sleeper_user_id);

create table historical_achievement (
    sleeper_user_id text        not null,
    code            text        not null,
    game_id         bigint      references historical_game (id) on delete set null,
    xp              int         not null,     -- frozen at unlock, so a later retune does not rewrite history
    detail          jsonb,
    unlocked_at     timestamptz not null default now(),
    seen_at         timestamptz,
    primary key (sleeper_user_id, code)
);

create table historical_profile (
    sleeper_user_id text primary key,
    seen_level      int  not null default 1
);
```

Total XP = `sum(historical_game_award.xp) + sum(historical_achievement.xp)`; level is derived, never stored.

### API (all require `X-Sleeper-User`, 401 without, same as the other `/api/history` routes)

- `GET /api/history/profile` -> `HistoricalProfile { xp, level, levelName, levelStartXp, nextLevelXp,
  seenLevel, totals { games, gamesFromSavedResults, weeklyWins, playoffWins, titles }, badges [ { code, name,
  rule, xp, source, unlocked, unlockedAt, gameId, detail, seen } ], settleFailures }`. Settles first.
  `badges` lists the whole catalog in catalog order. `settleFailures` is the count of games that could not be
  settled this call.
- `GET /api/history/games/{id}/award` -> `HistoricalGameAward { gameId, xp, fromSavedResult, lines [ { label,
  xp } ], badges [ same badge shape ], total }`; 404 for another user's or an unknown game; 409 when the game
  is not COMPLETE; settles first. `total = xp + sum(badges.xp)`.
- `POST /api/history/profile/seen` body `{ codes: string[], level: number }` -> 204. Sets `seen_at` on those
  of the caller's badges and raises `seen_level` to `min(level, actual level)`; never lowers it.

No `Map.of` on these paths (`detail`, `unlockedAt`, `gameId` are nullable): records and mutable maps only.

### Web

- `api.ts`: the three types and calls, mirroring the records field-for-field.
- `components/AchievementToasts.tsx`: fixed-position stack, `role="status" aria-live="polite"`, max three
  visible, queue the rest, each dismissible and auto-dismissed after ~6 s (paused on hover/focus),
  reduced-motion guard. A level-up toast when `level > seenLevel`.
- `components/HistoricalAwardPanel.tsx` on the results page, after the finish panel: "What this game earned",
  itemised lines and total; badges earned in this game; a note for `fromSavedResult` games. On a fetch error:
  one muted line, results page otherwise unchanged (FR-004).
- `pages/HistoricalRoom.tsx`: when `complete && state.result` first becomes true (that is, after the reveal),
  fetch award + profile, show toasts for badges with `unlocked && !seen` and the level-up, then POST seen.
  Reopening an old game shows any still-unseen toasts once, then none.
- `pages/HistoricalDraft.tsx`: a level card (level, name, "340 of 600 XP to level 4" beside the bar: one
  encoding per mark, exact value spelled out) and the trophy case grid; locked badges show their rule, and
  "New games" badges carry the line "only from games finished after this was added".
- Styling from existing tokens in `styles.css`; tinted pills and real depth, not flat uniform cards.

## Risks the review should attack

1. Advisory lock + per-game savepoint + `on conflict do nothing`: is double-settle really impossible, and
   does one bad game really not poison the transaction?
2. `playedWeeks` for a game whose season was re-imported since (the `finishDraft` re-key guard at :356-366).
   Settle must fail that one game softly, not throw.
3. Does a `WeekStatLine` row exist for a game a player did not actually play in (DNP rows)? If so
   `lastWeekPlayed` is wrong and GONE_EARLY must use minutes/any-stat instead.
4. NFL: bye weeks, and week numbering vs `format.seasonWeeks`.
5. Whether `standings.wins` in a new-format result is regular season only (it must be, or playoff wins are
   double-counted).
6. Whether the toast fetch can fire during the paced reveal and spoil the result.
7. V48 on prod: the `update` touches every COMPLETE game row once (small table; confirm).

## Verification plan

Unit: `AchievementRulesTest` (each rule, both directions, old-format result from
`src/test/resources/historical/result-062.json`), `LevelsTest`. IT: settle idempotent under concurrent calls;
pre-achievement game settles quietly; another user's award is a 404; seen is monotonic. Web: tests for toasts
(queue, once-only), award panel error state, landing level card. Live: finish an NBA and an NFL game through
the real UI on a scratch database, see the popups, reload, check the landing page at desktop and phone width.

---

## Amended after review (2026-11-13)

`plan-review.md` found 0 blockers, 12 SHOULD, 6 NIT. No rule was pointed backwards. What changed, and why.
Where this section disagrees with the text above, this section wins.

**A1 (PR-02, PR-15, PR-17) Pick facts are captured at completion, not recomputed at settle.** The plan had
settle re-query `playedWeeks`; after a re-import a re-keyed player would silently rank last and earn false
bust badges. Instead V48 adds `historical_game.pick_facts jsonb` (nullable). `finishDraft` computes, for each
of the user's picks, `{pickNo, round, playerId, playerName, boardRank, seasonValueRank, seasonValue,
lastWeekPlayed}` plus `seasonWeeks`, from the `played` list and scoring it already has in hand, in a
try/catch that logs and leaves the column null (a capture failure never blocks completion). Settle reads only
the game row. A game with null `pick_facts` (old game, or a failed capture) settles from the saved result
with `fromSavedResult = true`.

**A2 (PR-01) Transaction mechanism.** `settle` runs in one write transaction (advisory lock first). Each game
runs in a per-game `PROPAGATION_NESTED` template built locally with `new TransactionTemplate(txManager)`
(do not mutate the shared bean, do not use `REQUIRES_NEW`: the pool is 10, 3 in tests). The catch sits
outside the nested `execute`. Badge inserts are skipped when the award insert affected 0 rows.

**A3 (PR-03) A game that cannot settle.** An unexpected exception leaves the game unsettled and counts in
`settleFailures`. `GET /games/{id}/award` then answers 200 with `settled: false`, zero XP and empty lists;
the panel says "This game's awards could not be worked out." The landing page says "N games could not be
counted" when `settleFailures > 0`.

**A4 (PR-04) Moments pop every game; badges unlock once.** Allan asked for a popup "for getting a crazy
pick reach or a bad bust"; once-only badges would go quiet after a few games. So a game's award carries
`moments`: every REACH_HIT, REACH_MISS, LATE_GEM, GONE_EARLY and BIG_BUST instance in that game, with the
player, pick and numbers. Moments carry no XP and pop as toasts every game. The badge of the same code still
unlocks once, with XP, the first time. Stored as `historical_game_award.moments jsonb`. This was my call from
his wording, not a question put to him; it is cheap to reverse (drop the moment toasts).

**A5 (PR-05) Reach rules tightened.** Reach applies only to picks in the first half of the draft
(`round <= rounds / 2`). REACH_HIT now needs a margin: `seasonValueRank <= pickNo - teams` (he outperformed
the reach slot by a full round). REACH_MISS unchanged (`seasonValueRank - pickNo >= 2 * teams`). LATE_GEM is
`round > rounds / 2` (round 8 or later in both sports today).

**A6 (PR-06) Fire rates are unmeasured.** CLEAN_SHEET and BEAT_HINDSIGHT may be close to unobtainable. They
stay as hard badges; the local database has no seasons, so nothing was measured. Verification records how
often each badge fired in the games actually played, and says so as a count, not a rate.

**A7 (PR-07) GONE_EARLY wording.** "His last box score in our data came in week N." Kaggle can drop real rows
(team mismatch, ambiguous names), so the badge states the data, not the event. A read-only prod count of
affected round 1-3 players per source is owed before trusting it; not done.

**A8 (PR-08) Titles.** Title = `userOutcome == CHAMPION` only. PLAYOFFS = the user's slot is in
`playoffs.seeds`. First place in a NO_PLAYOFFS season earns TOP_SEED, not a title.

**A9 (PR-09) Mixed first batch.** After each saved-result game that existed before the migration
(`pre_achievements`), `seen_level` is raised to the level so far, so old games never produce a level-up toast
even when a new game is in the same batch. The profile row is upserted.

**A10 (PR-10, PR-13) Toast scope.** The game page toasts only that game's newly unlocked badges, its moments,
and a level-up. Moments are "seen" per game: `historical_game_award.moments_seen_at`. The fetch, toasts and
seen calls live inside `HistoricalAwardPanel`, which is mounted only in the results branch (after the reveal
view reaches COMPLETE), so nothing can fire during a paced reveal. Seen is posted as each toast is shown;
the queue is keyed by code so StrictMode's double effect cannot duplicate.
`POST /api/history/profile/seen` body is `{ codes: string[], level: number|null, momentsGameId: number|null }`.

**A11 (PR-11) Arithmetic.** SC-002 reads: the game's lines sum to its `xp`; the profile's XP = sum of every
game's `xp` + sum of every earned badge's `xp`. The award's `total` = `xp` + the XP of badges this game
unlocked. The level bar shows XP into the level over the level's size, and the sentence uses the same two
numbers ("40 of 300 XP to level 4").

**A12 (PR-12) Deploy.** Backend first (V48), then web. The level card, trophy case and award panel render
nothing (panel: one muted line) when their request fails, with tests, so either order is safe.

**A13 (PR-14) Stated, not changed.** A playoff game decided by seed pays win XP like any win. An old-format
result's wins cover its whole season; its line is labelled "wins" rather than "regular-season wins".

**A14 (PR-16) Tests.** No header, blank header and a stranger on all three routes; 404 before 409; ITs clean
`historical_achievement`, `historical_profile` and `historical_game_award` themselves.

**A15 (PR-18) Accepted.** Retuning thresholds moves levels while `seen_level` stays; `seen_level` is clamped
to the actual level on read.

### Wire contract (final)

```
Badge        { code, name, rule, xp, source: "STORED"|"NEW_GAMES", unlocked, unlockedAt|null, gameId|null,
               detail: object|null, seen }
Moment       { code, name, text, pickNo, playerName }
GET /api/history/profile ->
  { xp, level, levelName, levelStartXp, nextLevelXp, seenLevel,
    totals { games, gamesFromSavedResults, weeklyWins, playoffWins, titles },
    badges: Badge[] (whole catalog, catalog order), settleFailures }
GET /api/history/games/{id}/award ->
  { gameId, settled, fromSavedResult, xp, lines: [{label, xp}], badges: Badge[] (unlocked by this game),
    moments: Moment[], momentsSeen, total }
POST /api/history/profile/seen { codes, level, momentsGameId } -> 204
```

## Amended after code review (2026-11-13)

From `code-review.md`. Findings fixed in this pass, each with a test that fails without the fix:

- **CR-01**: `toastsFor` no longer raises a moment toast when an unseen badge toast in the same award has the same
  code and `detail.pickNo`. `momentsGameId` is still posted (from an effect when every moment was folded away).
- **CR-02**: `AchievementToasts` (the `role="status"` region) is rendered, empty, from the panel's first render in
  every branch (loading, failed, `settled:false`, loaded). Test: the region exists, empty, before the request resolves.
- **CR-03**: REACH_HIT margin is now `min(teams, pickNo / 2)` (integer division): a hit is
  `seasonValueRank <= pickNo - min(teams, pickNo / 2)`. Pick 12 needs a top-6 finish; pick 24 and later need a full
  round, as before. **This is a hand-set, arbitrary choice made by the build session, not by Allan**; it replaces
  amendment A5's `pickNo - teams`, which made round 1 unreachable. It also changes picks 13-23: pick 20 now needs
  rank <= 10 (was <= 8); `aHitNeedsAFullRoundOfMarginNotJustTheSlot` moved to a round-3 pick (30) and a new test pins
  pick 20 and pick 24. Rule text in `Badge.java` now says: "...and he beat his pick number by a full round, or in
  round 1 finished in the top half of it."
- **CR-04**: a badge's `unlocked_at` is the unlocking game's `completed_at` (every game, cumulative badges too),
  bound as `OffsetDateTime`; `now()` only when `completed_at` is null. `seen_at` stays `now()`.
- **CR-05 (partial)**: the auto-dismiss clock stops, and `onShown` is not called, while `document.hidden`; both
  resume on `visibilitychange`. **Left alone**: marking all of a game's moments seen when the first one shows, and
  the level toast being spliced ahead of moments. Both are NITs with nothing lost (moments stay listed in the panel).
- **CR-07**: badge rule texts no longer hard-code "36 in a 12-team league" or "round 8 or later" (now "three full
  rounds", "the second half of the draft"). TOP_SEED and PERFECT_SEASON detail text says "regular season" only for a
  new-format result. The old-format XP line already read "N win(s)" (verified in code, unchanged).
- **CR-08**: a game that cannot settle now logs one WARN line (exception message, no stack trace) per attempt.
  Not done: once-per-process stack tracking, a failure table.
- **CR-10**: the reviewer's scratch ITs are adopted as `HistoricalAchievementWireIT` (long NBA and NFL seasons
  through the real routes, wire fields, CORS preflight) and `HistoricalAchievementSavepointIT` (a real `select 1/0`
  inside one game's savepoint); both use `it-hwire-` / `it-hsave-` user ids and delete their own rows.

Left alone, recorded: **CR-06** (up to three moments for one pick; each is true, NIT, accepted), **CR-09**
(`capturePickFacts` scores the season a second time; NIT, unmeasured, the verification pass reads the replay log).
