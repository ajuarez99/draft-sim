# Tasks: Weekly High-Score Ledger (spec 048)

**Input**: `specs/048-weekly-high-score-ledger/` — plan.md (**read its "Amended after review" A1–A15
first; they win over the plan body**), spec.md (incl. its amendment sections), research.md,
data-model.md, contracts/weekly-pot-api.md, quickstart.md, plan-review.md.

**Tests**: included. The spec's success criteria are arithmetic invariants (zero-sum, settle-up
clears, winner matches) and AGENTS.md bug class 1 requires preference-ordering tests for scoring math.

**Analyze pass (2026-10-25)**: no CRITICAL/HIGH. Two MEDIUMs folded in: T008 must cover the settings sub-path; T014 seeds an NBA row too. T009 (api.ts) lands in the same change as T006/T007 (constitution).

**Paths**: `B = backend/src/main/java/com/ballknowers/draftsim`, `T = backend/src/test/java/com/ballknowers/draftsim`, `W = web/src`.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup

- [X] T001 Create `backend/src/main/resources/db/migration/V41__league_weekly_pot.sql`: table `league_weekly_pot` with `league_id bigint primary key references league(id) on delete cascade`, `enabled boolean not null`, `stake_cents int not null default 100 check (stake_cents between 1 and 100000)`, `updated_by bigint null references manager(id)`, `updated_at timestamptz not null default now()`. Header comment: per league-SEASON row (research R7), stake cap 100000 is hand-set and arbitrary. Confirm V40 is the highest existing migration before writing.

---

## Phase 2: Foundational (blocks every story)

- [X] T002 Create `$B/store/LeagueWeeklyPotRepository.java` (`@Repository`, `JdbcClient`, style of `LeagueConductRepository`): `record Setting(boolean enabled, int stakeCents)`; `Optional<Setting> find(long leagueId)`; `void upsert(long leagueId, boolean enabled, int stakeCents, Long updatedBy)` (insert … on conflict (league_id) do update, sets `updated_at = now()`; bind `updatedBy` as a nullable Long — beware lessons bug class 3, bind with an explicit type or `setObject`).
- [X] T003 Create pure `$B/engine/WeeklyPotLedger.java` (final class, static, no Spring/DB). Inputs: `record Score(int week, int rosterId, BigDecimal points)`, `List<Score>` already limited to DECIDED weeks, `Collection<Integer> allRosterIds`, `long stakeCents`. Output `record Ledger(List<WeekResult> weeks, Map<Integer, Balance> balances, List<Debt> pairwise, List<Debt> shortcut)`; `record WeekResult(int week, List<Integer> winners, BigDecimal winningPoints, BigDecimal runnerUpPoints, int payers, long potCents)`; `record Balance(int rosterId, int weeksWon, long collectedCents, long paidCents, long netCents)`; `record Debt(int fromRosterId, int toRosterId, long cents)`. Rules: winners = rows whose points `compareTo` max == 0 (never `equals`); every other roster with a row (even 0.0) pays `stakeCents` in total, split `stake / k` per co-winner, the first `stake % k` co-winners by ascending rosterId getting +1 cent (research R4); pairwise = per unordered pair `owed[a][b] − owed[b][a]`, listed from the debtor, omitted at 0, sorted by (from, to); shortcut = greedy **per connected component of the pairwise debt graph** (plan A1): repeatedly match the most-negative and most-positive balances in that component, ties broken by rosterId ascending, transfer the smaller magnitude. Balances include every id in `allRosterIds` (zeros allowed). Javadoc states the proof that shortcut ≤ pairwise per component and that the tie-cent order is a labelled convention.
- [X] T004 [P] Create `$T/engine/WeeklyPotLedgerTest.java` (JUnit 5, literal fixtures) covering: (a) 12 rosters, one week, untied → winner +1100, others −100, Σnet = 0; (b) ordering — the higher scorer wins, not the lower (bug class 1; swap the two scores and assert the winner swaps); (c) 2-way tie at 100¢ → 50/50 from each payer; (d) 3-way tie at 100¢ → 34/33/33 with the 34 to the lowest rosterId, and at 200¢ → 67/67/66 (plan A8); (e) `new BigDecimal("150.1")` vs `"150.10"` tie; (f) negative score is a payer and is not "everyone zero"; (g) multi-week season: for both `pairwise` and `shortcut`, applying every debt to balances yields all zeros, `shortcut.size() <= pairwise.size()`, every shortcut debt goes from a negative to a positive balance, and no zero-balance roster appears in shortcut; (h) a property-style loop over ≥200 seeded random seasons (fixed `Random` seed, 8–14 rosters, 1–14 weeks, random ties) asserting (g) and Σnet == 0; (i) a roster in `allRosterIds` with no rows appears with zeros.

**Checkpoint**: `./gradlew test --tests '*WeeklyPotLedgerTest'` green.

---

## Phase 3: User Story 1 + 2 — season sheet and settle-up (P1) 🎯 MVP

US1 and US2 share one payload (contract), so they are built together.

**Independent test**: with a league row's pot forced on in the DB, `GET /api/leagues/{id}/weekly-pot` returns weeks, balances summing to 0, and both settle-up lists clearing every balance; the page renders them.

- [X] T005 [US1] Extract the "has the live week started" rule from `WeeklyReportService.decideLive` (`$B/engine/WeeklyReportService.java` ~line 489: any live row points > 0, OR stored player games for that sport/season/week) into one public static/helper both services call (e.g. `LiveWeekSource.hasStarted(Live live, boolean hasPlayerGames)` or a small `LiveWeekStart` class), and change `decideLive` to call it with identical behaviour (plan A2; one implementation per rule).
- [X] T006 [US1] Create `$B/engine/WeeklyPotService.java` (`@Service`). `View view(LeagueRepository.LeagueRow row, String sleeperUserId)`:
  1. setting = repo.find(row.id()) or `(false, 100)`; `canEdit = membership.canCommission(row.id(), sleeperUserId)`; `commissionerKnown = leagueMembers.anyCommissioner(row.id())`; if off → return a View with only those fields.
  2. `bound = expectedWinsService.regularSeasonBound(row)`; `regularSeasonKnown = bound.throughWeek() != null`.
  3. `snapshot = scoredWeeks.of(row.id())`; rows = `weekPoints.allScores(List.of(row.id()), bound)`.
  4. Live (plan A3): `liveWeekSource.peek(row)`; FAILED → `liveUnavailable = true`, no leader; OK → leader only if `live.week()` ≤ bound (when known), not in `snapshot.finalWeeks()`, and T005's started rule holds; leader = roster(s) at max live points (`rosterIds` list, ties included).
  5. Week walk (plan A6): weeks `1..lastWeek` where `lastWeek = bound.throughWeek()` if known and capped at `max(snapshot.latestStored(), liveWeek if any)`, else `max(latestStored, liveWeek)`; status per week: no rows → `MISSING`; rows but not final → `NOT_FINAL`; final and every stored score `compareTo(ZERO)==0` → `NO_SCORES`; else `DECIDED`.
  6. `ledger = WeeklyPotLedger.compute(decided scores, allRosterIds from rosterSeasons.forLeague(row.id()), stake)`.
  7. Names via `RosterOwners.ownerNames(rosterSeasons.forLeague(id), leagueMembers.forLeague(id), ManagerRepository.caller(managers, id, sleeperUserId))` (plan A4) → `name`, `avatarId`, `isMe`; `noManager = managerId == null`.
  8. `throughWeek` = highest week with every week ≤ it DECIDED/NO_SCORES (0 if none); `seasonFinal` = regularSeasonKnown ? every week 1..bound is DECIDED/NO_SCORES : `row.complete()`.
  9. balances ordered netCents desc, weeksWon desc, rosterId asc.
  Define `View` and nested records matching contracts/weekly-pot-api.md field-for-field (`weeks[]` includes non-DECIDED weeks with empty winners and null points). `tieCentRule` is a constant string. No `Map.of` anywhere with nullable values.
- [X] T007 [US1] Create `$B/api/WeeklyPotController.java`: `GET /api/leagues/{sleeperId}/weekly-pot` with `@RequestHeader(value="X-Sleeper-User", required=false)`; `membership.visibleLeague(sleeperId, user)` → 404 when empty (exact URL row, no `LeagueSeasonResolver`, research R8). Serialize the off-state with only `season, sport, enabled, stakeCents, canEdit, commissionerKnown` (use `@JsonInclude(NON_NULL)` on the view or a separate off record — but then make sure legitimately-null ledger fields like `runnerUpPoints`, `liveLeader`, `regularSeasonThroughWeek`, `managerId`, `avatarId` are still emitted as null in the ON state; simplest: two records).
- [X] T008 [US1] Add the `weeklyPot` key to `$B/**/LeagueAvailability.java`: `ROUTES` `m.put("weeklyPot", List.of("weekly-pot"))` and `FIXED` `m.put("weeklyPot", "The weekly pot follows Sleeper leagues only.")` (plan A9). Read `PlatformGuard`'s path matching and make sure it refuses BOTH `/weekly-pot` and `/weekly-pot/settings` for ESPN leagues (add `"weekly-pot/settings"` to the list if it matches by exact segment); update any test that enumerates the keys.
- [X] T009 [P] [US1] Add to `$W/api.ts`: types mirroring the Java records field-for-field — `WeeklyPotWeekStatus = 'DECIDED'|'NO_SCORES'|'NOT_FINAL'|'MISSING'`, `WeeklyPotWeek`, `WeeklyPotBalance` (`rosterId, managerId: number|null, name, avatarId: string|null, isMe, noManager, weeksWon, collectedCents, paidCents, netCents`), `WeeklyPotDebt`, `WeeklyPotLiveLeader`, and `WeeklyPot` as a **union on `enabled`** (`WeeklyPotOff | WeeklyPotOn`, plan A11); `getWeeklyPot(sleeperLeagueId)` using the existing `apiFetch` helper (it already sends `X-Sleeper-User`).
- [X] T010 [P] [US1] Add destination row to `$W/destinations.ts` (plan A10): add `'weeklyPot'` to `DestinationKey`; row `{ key: 'weeklyPot', group: 'season', glyph: '◎', sports: ['nfl','nba'], label: 'Weekly pot', href: (ctx) => \`/leagues/${ctx.season.sleeperLeagueId}/weekly-pot\`, match: /^\/leagues\/([^/]+)\/weekly-pot\/?$/, idKind: 'league', requiresStatus: null, isAction: false, inRail: true }` placed after `superlatives`, with the house-style comment on why both sports. Add the route in `$W/App.tsx`: `<Route path="/leagues/:sleeperLeagueId/weekly-pot" element={<KeyedByLeague page={WeeklyPot} gate="weeklyPot" />} />`. Run `destinations.test.ts`.
- [X] T011 [US1] Create `$W/pages/WeeklyPot.tsx` (+ styles in `$W/styles.css` under a `.wp-` prefix, following existing page/tint conventions; dark card/avatar look, tinted pills, no flat uniform cards — table where content is tabular). Sections: header (season, "$X a week from everyone to the top scorer", "through week N" or "Final", regular-season range or the "playoffs start isn't recorded, so every scored week counts" note); live line when `liveLeader` ("Leading week 8 — not final: Team, 88.2"; "Couldn't check the live week" when `liveUnavailable`); **standings table** (rank, avatar + name, weeks won, collected, paid, net as green/red tinted pill, `isMe` row highlighted, "had no manager" note when `noManager`); **week by week** list newest first (week, winner(s) with a "Tie" badge, score, margin to runner-up, pot; non-DECIDED weeks shown muted with their status in words: "No scores recorded", "Not final yet", "Not loaded yet"). Money formatted from cents with `Intl.NumberFormat('en-US', {style:'currency', currency:'USD'})`. Phone (≤600px): standings collapse to rank/name/net with weeks won under the name.
- [X] T012 [US2] In `$W/pages/WeeklyPot.tsx` add the **Settle up** section: two tabs, "Shortcut" (default) and "Every pair"; one sentence: both clear the same balances, the shortcut uses fewer payments and can send money between people who never beat each other, and in "Every pair" someone who's up overall can still owe one person. Rows "Alex pays Jordan $3.00". "Every pair" on phone is grouped by payer and collapsible (plan A15). Footnote `tieCentRule` when any DECIDED week has >1 winner. Label "as of week N" unless `seasonFinal`.
- [X] T013 [P] [US1] Create `$W/pages/WeeklyPot.test.tsx` (vitest + RTL, mirror `Rivalries.test.tsx` mocking style): renders standings in payload order with formatted money; tie badge; non-DECIDED week labels; settle-up tab switch; off-state shows no pot amount; viewer row highlighted from `isMe` (never username matching).
- [X] T014 [US1] Create `$T/api/WeeklyPotControllerIT.java` (`@SpringBootTest @AutoConfigureMockMvc`, skip-when-Postgres-down pattern and throwaway-seed/cleanup style of `AdpCompareMvcIT`; far-future season e.g. 2094, ids prefixed `it048-`): seed a 4-roster NFL league row with playoff start, member/commissioner rows, `roster_week_points` for weeks 1–3 (one tie week) plus a playoff week, and `league_weekly_pot` enabled. Assert: 404 for stranger; ON payload JSON shape incl. explicit nulls; playoff week absent; tie split; Σnet = 0; both settle-up lists clear balances; off-state has no `balances` key; ESPN-platform league row → platform refusal from `PlatformGuard`; one NBA league row (2-roster minimum, two final weeks) returns a correct winner so FR-013's both-sports claim is exercised.

**Checkpoint**: MVP — ledger visible for a league with the pot on (set via SQL).

---

## Phase 4: User Story 3 — commissioner turns it on (P2)

**Independent test**: as commissioner, toggle on at $2 → figures reflect $2 (untied weeks double); non-commissioner gets 403 and no control.

- [X] T015 [US3] In `$B/api/WeeklyPotController.java` add `PUT /api/leagues/{sleeperId}/weekly-pot/settings`, body record `WeeklyPotSettingsRequest(Boolean enabled, JsonNode stakeCents)` or equivalent that **rejects non-integral numbers** (plan A11: Jackson would coerce 150.7 → 150): 404 not visible; 403 via `membership.canCommission` with body `{message, commissionerKnown}` built with `LinkedHashMap` (message "only this league's Sleeper commissioner may change the weekly pot" / "No commissioner is recorded for this league yet."), same shape as `SuperlativesController.forbidden`; 400 `{message}` when `enabled` or `stakeCents` missing, non-integral, or outside 1..100000 — all checked before the DB; 200 → `repo.upsert(row.id(), enabled, stake, ManagerRepository.caller(...))` then return the same body as GET.
- [X] T016 [P] [US3] Add `saveWeeklyPotSettings(sleeperLeagueId, {enabled, stakeCents})` to `$W/api.ts` (PUT via the existing fetch helper; surface 403/400 `message`).
- [X] T017 [US3] Create `$W/components/CommissionerPotPanel.tsx` and mount it in `WeeklyPot.tsx` when `canEdit`: toggle + stake input in dollars, converted with `Math.round(Number(v) * 100)`, rejecting >2 decimals and values outside $0.01–$1,000 client-side; save → re-render from the returned body; show server `message` on error. Off-state copy for everyone: one sentence on what the pot is; for non-commissioners when `!commissionerKnown`: "No commissioner is recorded for this league yet." No pot amounts shown to non-commissioners while off.
- [X] T018 [US3] Extend `$T/api/WeeklyPotControllerIT.java`: PUT as non-commissioner → 403 with `commissionerKnown`; missing field / `0` / `100001` / `150.7` → 400; commissioner PUT `{true, 200}` → 200 and untied-week figures are exactly double the 100¢ ones; tied week follows R4 at 200¢; PUT `{false, 200}` then GET → off body, then `{true, 200}` restores the stake.
- [X] T019 [P] [US3] Extend `$W/pages/WeeklyPot.test.tsx`: panel only when `canEdit`; dollars→cents conversion (`0.29` → 29, `1.5` → 150, `1.234` rejected).

---

## Phase 5: User Story 4 — who's leading this week (P3)

**Independent test**: with a stubbed live read, the leader shows as not final and balances are unchanged.

- [X] T020 [US4] Add a unit test `$T/engine/WeeklyPotServiceTest.java` (mocks/stubs for repositories and `LiveWeekSource` via its test constructor seam): live week started → leader with ties; all live points 0 and no player games → no leader; live week already final → no leader; live week beyond regular season → no leader; FAILED → `liveUnavailable` true; and the week walk: a stored-not-final week between finals is `NOT_FINAL` and caps `throughWeek`; a missing week is `MISSING`; all-zero final week is `NO_SCORES` and moves no money; `seasonFinal` true once every regular-season week is decided even if the league row isn't complete.
- [X] T021 [P] [US4] Extend `$W/pages/WeeklyPot.test.tsx`: live line text and "Couldn't check the live week".

---

## Phase 6: Polish & verification prep

- [X] T022 Run `cd backend && ./gradlew test` with Postgres on 5433 up; record the test count and **skipped count** (must be 0 for `WeeklyPotControllerIT`, plan A15). Run `cd web && npx tsc -b && npm run build && npx vitest run`.
- [X] T023 [P] Update `README.md` / `HANDOFF.md` only where they list league pages (add "Weekly pot"), no other doc churn.

---

## Dependencies & order

- T001 → T002 → (T003 → T004) → T005 → T006 → T007/T008 → T014.
- Web: T009, T010 can start after the contract (parallel with backend); T011 → T012 → T013.
- US3 (T015–T019) needs T007 and T011. US4 (T020–T021) needs T006/T011.
- T022 last.

## Parallel examples

- After T003: T004 (tests) ∥ T005.
- After T006: T009 ∥ T010 ∥ T008.
- US3: T016 ∥ T019 once T015/T017 are drafted.

## Implementation strategy

MVP = Phases 1–3 (sheet + settle-up, pot forced on via SQL). Then US3 (the switch), then US4. Live
verification (quickstart 1–12) follows in `verification.md`.
