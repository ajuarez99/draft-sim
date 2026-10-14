# Spec 033: adversarial review of the plan

2026-10-14. I didn't re-read the prose for plausibility. I **executed** research rules R1–R3
and R5 as a throwaway script over the real rows of all four league-seasons (weekly rows,
complete transactions by `created_at`, draft picks, roster→manager). The script lives in the
session scratchpad, not the repo. The outputs below come from that run.

## What the rules produced on real data

**All 26 trades.** Each traded player resolved to a window or a cause:

- **The review cases came out as intended:**
  - 816's windows start at week 6, not its leg of 3;
  - 704 starts at week 2: the week-1 duplicate goes to the roster that started him, the giver;
  - 1426 → 1804 gives week 15, then weeks 16–19;
  - 1753's assets are undone, and 1720 counts from week 16.
- **Unexplained stretch starts: 0** in NFL 2025 (3,191 player-weeks), NBA 2025 (3,894) and
  NBA 2024 (4,166). Every roster change in the scoring rows has a draft pick or a move behind it.
- NBA 2026 has no scored weeks yet. The rules must handle an empty week set; the script did.

## Findings

### F1 (blocker for the wire). Reconciliation rule 2 as written reports noise

As specified, "moves not reflected" gives **20 / 528 / 118** for NFL 2025 / NBA 2025 / NBA 2024.
Almost all are free-agent streams added and dropped in the same leg. Those players are never in
a scored snapshot, which is correct. Shipping 528 would tell readers the data is broken when it
isn't.

**Fix:**
- Exclude a move when the player's next move has a leg ≤ this move's leg (in and out within
  one week).
- Report those as their own count, `sameWeekInAndOut`.
- What's left is **5 / 2 / 1**. Every one is a waiver claim or a trade whose player was dropped
  the next leg before any scored week (e.g. NFL 2483, NBA 1177, 1943).
- Rename the remaining count `neverOnAScoredRoster`, list each one with its next move, and
  never call it an error.

### F2. The cause labels: "undone" is too broad

The script's UNDONE ("a later move takes P off R") fired on three trades that weren't undone:

- **NFL 2464:** player 4039 was traded to roster 1, and roster 1 dropped him the next week.
- **NBA 766 and 1162:** roster 8 paid $67 FAAB for 2334 and for 1438, then dropped each before
  he played a scored week.

Calling those "undone" misdescribes what the managers did.

**Fix (FR-019 / data-model `status`):**
- `UNDONE`: the later move returns P **to a giving roster of T**. That's 1753 → 1720.
- New **`LEFT_BEFORE_PLAYING`**: P was dropped or moved on by R before a scored week. That's
  4039, 2334 and 1438.
- `NO_WEEK_YET` and `NEVER_PLAYED_FOR_THEM` stay as they are.

### F3. Correction: V6's expected result was wrong

The quickstart said the NFL week-5/6 pair's first trade might be "UNDONE". It isn't:

- 2403's assets (5870, CLE) **count for weeks 5–6** for roster 7;
- 2428 (leg 6) sends them back, counted from week 7.

That's two trades, each graded on its own window, as FR-005 intends. V6 is now a precise
expectation.

### F4. A boundary week can credit the receiver with bench points he never earned

NFL 2464 was accepted 2025-10-21, a Tuesday, *after* week 7's games. Yet 5012 sits on the
receiver's (roster 3) week-7 row, unstarted, with 0.0 points. Sleeper's weekly row can reflect a
roster change made after that week's games.

- **Started points can't leak this way.** Lineups lock at kickoff, and R1 gives a week to its
  starter first.
- **Bench points and weeks counted can leak.** Here it's harmless (0.0, one week), but a
  non-zero case would add a pre-trade week's bench points.

**Fix:**
- The verdict is started points only (already true).
- `weeksCounted` counts weeks where the asset was **started** by the receiver, or listed after
  the first week he was started. A leading unstarted boundary week is shown in the stretch
  panel, but not counted.
- State it in "How this works". This is cheaper and more honest than a schedule lookup NFL 2025
  can't do.

### F5. The current season's live week would make "so far" verdicts swing mid-week

The refresh stores non-final weeks (`WeekFinality`: weeks are refetched until final). An
in-progress week's partial points would enter `startedPoints` and `weeksCounted`.

**Fix:** count only weeks ≤ the league's `last_scored_leg` (from `settings_json`), and say
"through week N". For finished seasons this changes nothing.

### F6. "Even" at 0.0 needs words, not a score

NBA 8044: neither side started what it received; 2144 gave 0 started for roster 2 and 2456 gave
0 started for roster 10. EVEN is true, but "Even" alone hides that nothing moved a matchup.

**Fix:** when every side's `startedTotal` is 0 with weeks counted, the wording is "Even: neither
side started what it got". The state stays EVEN, and no "0–0" is shown.

### F7. Picks: a manager change mid-season is a known false-negative

Pick resolution goes through `roster_season.manager_id`, which is one manager per roster per
season. A team handed over mid-season would fail the drafter check and show `PICK_NOT_MATCHED`.
That's the correct, honest outcome. No change; noted so nobody "fixes" it into a guess.

## Checked and fine

- **R6 backfill:** trades are stored under their leg, and `/transactions/{leg}` returns them,
  so the refetch condition finds them. The 2026 picks trade has leg 1, inside 1..lastWeek.
- **Ordering by `created_at`:** the script did, and every case above depends on it.
- **DEF assets** (CLE 10.0 / 62.0 started, DET, PIT −2.0) resolve like players. Negative
  started points exist (PIT −2.0), so a margin can be negative for one asset. Totals handle
  that, but the T-tests must include a negative.

## Amendments applied

- **research.md:** R3's causes (F2), R5 (F1), and a new R11 for F4/F5.
- **data-model.md:** `status` gains `LEFT_BEFORE_PLAYING`; `Reconciliation` fields renamed
  (F1); the `weeksCounted` rule (F4); the scored-week cap (F5); the EVEN wording (F6).
- **contracts/api.md:** the reconciliation fields and status enum.
- **quickstart.md:** V6 (F3) and V7's expected numbers (F1).

Each carries an "amended after plan review" marker.

## Amended during the build (2026-10-14, after the US1 backend agent's report)

### F4, narrowed

As first written, F4 said counting starts at the first week the receiver *started* the asset.
That drops every leading bench week. Measured on NBA 1720, Giannis (1380) sat on roster 1's
bench in weeks 16–19, so his real ownership would count only from week 20. The measured problem
(NFL 2464's 5012) was a **single** boundary week.

**Rule now:** only the **first** window week is dropped when the receiver didn't start the asset
in it. Every later week counts, started or bench.

Outcomes:
- 2464's 5012: window week 7 only, unstarted, so nothing counts. Unchanged.
- 8044's 2456: counts weeks 13–21. Unchanged.
- 1720's 1380: counts weeks 17–21, not 20–21.
- 2428's 5870: counts from week 8. Unchanged.
- 1804's 1589: counts weeks 17–19.

### F7. Verdict states for a trade with nothing counted

The data-model rule "NO_WEEK_YET when no counted week" made reversed trade 1753 (a finished
season) read "No week played yet". The agent flagged this. Rule 2 is now split:

- **2a. `UNDONE`:** every PLAYER/resolved-PICK asset on every side is `UNDONE`. Wording:
  "Undone before a scored week".
- **2b. `NO_WEEK_YET`:** no counted week, and some asset is `NO_WEEK_YET`.
- **2c. `NONE_COUNTED`:** no counted week otherwise. Wording: "Neither side got a counted week
  from this trade".

Both new states go into the contract and `api.ts`.

### Correction to T009's expectation

T009 expected "21 NBA 2025 trades after the backfill, 18 before". That was **my error**. NBA 2025
has 18 stored trades. 21 is the NBA total across seasons: 18 + 2 + 1. The agent's IT asserts one
entry per stored trade, which is right.

### Correction to F1's numbers (found in T018, 2026-10-14)

F1 said `neverOnAScoredRoster` is 5 / 2 / 1 for NFL 2025 / NBA 2025 / NBA 2024. **NFL 2025 is
6.** The Java implementation reported 6, and I checked the extra row by hand:

- Kameron Johnson (11994) was claimed by WAIVER 2424 in week 6 and dropped by FREE_AGENT 2474
  in week 7.
- He appears on **no** roster's scored row, in any week.

The error was in my script: its loop walked only players present in the weekly rows, so a
player who never appeared anywhere was never checked. NBA's 2 and 1 match the Java, and nothing
says the script's scope bug never hit them. The Java's numbers, which check every move, are the
ones of record.

The build agent's first explanation (that the script had excluded Cooper Kupp's 2464 move) was
wrong too: Kupp was in the script's 5. The IT comment is corrected.

### F8. A trade that only restored the status quo was credited as a win (found in the live browser check)

NBA 2025 trade 1720 (accepted 2026-02-07, leg 16) read "+62.0 started points over 6 weeks". But in
the scored rows neither player ever moved: Reaves (2316) sits on roster 11 and Giannis (1380) on
roster 1 for weeks 1-21. 1720 only restored the status quo after 1753 (accepted 2026-02-05) was
undone before any week was scored. Crediting either side with points from a player it already had
is confident-looking nonsense.

**Fix (amended after live verification):**
- New asset status `ALREADY_HELD`: the receiver already held the player (R1) before the trade's
  leg, meaning the window's stretch starts before the leg. Nothing counts. It is checked before
  the window counting and the cause logic.
- New verdict state `RESTORED`: after NOT_COMPARABLE, every player/resolved-pick asset on every side
  is ALREADY_HELD or UNDONE, and at least one is ALREADY_HELD. 1753 stays UNDONE; 1720 is RESTORED.

**Misfire scan, executed** over all four league-seasons through the real read path (`TradeVerdictServiceIT.onlyTrade1720HasAlreadyHeldAssets`): exactly two assets read ALREADY_HELD, 1720's Giannis (1380, roster 1, stretch 1-21 DRAFT) and Reaves (2316, roster 11, stretch 1-21 DRAFT). NFL 2428 stays COUNTED from week 7.

## Code review (T026)

Two bugs found by the bug-hunting review of `OwnershipTimeline`, both fixed with tests.

- **R1, live-season mislabel.** In an incomplete season, a received player with no later move taking him off
  the receiver could fall through to `NEVER_PLAYED_FOR_THEM`. Case: an NFL trade accepted after week N's
  games, so the receiver's week-N row lists him unstarted (the F4 boundary week, dropped) and week N+1 isn't
  stored. Every window week is final, so neither NO_WEEK_YET condition fired, for a player on that roster right
  now. **Fix:** in an incomplete season, no later move off the receiver means `NO_WEEK_YET`, whatever the
  window. `NEVER_PLAYED_FOR_THEM` stays for complete seasons. **Test:**
  `OwnershipTimelineTest.aBoundaryWeekReceiverInALiveSeasonIsNoWeekYetNotNeverPlayed`, which also asserts
  the same rows in a complete season stay `NEVER_PLAYED_FOR_THEM`.
- **R2, pick status.** A resolved pick's status was `NO_WEEK_YET` or else `COUNTED`, so a drafted player dropped
  before he ever scored showed `COUNTED` on the pick with 0 points. **Fix:** the pick passes the nested
  player's status through (contracts/api.md comment updated). **Test:**
  `OwnershipTimelineTest.aResolvedPickReadsAsItsDraftedPlayerDoes`.
