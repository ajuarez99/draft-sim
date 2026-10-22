# Verification: spec 044, lock-in threshold

Run on 2026-10-22 against this worktree's real backend (`044-api`, port 8144, on-visit refresh off) and Vite dev
server (`044-web`, port 5244). Both used the shared local Postgres on 5433. Signed in through the real UI as a
member of the NBA league (Sleeper username `nuggetchef`). Each item below is **measured** (driven and observed)
unless it says otherwise.

## Backend numbers against the research (SC-001, SC-002, SC-003, SC-007)

`GET /api/leagues/{id}/lock-ins` as a league member:

| League | Status | lock / rodeOut / rodeOutMissed / unmatched | Thresholds from | 1 / 2 / 3 / 4+ left |
|---|---|---|---|---|
| 212 (2024) | MEASURED | 1351 / 949 / 44 / **0** | [2024] | +4.3% / +22.6% / +33.4% / +41.3% |
| 211 (2025) | MEASURED | 1188 / 894 / 14 / **0** | [2024, 2025] | +6.8% / +24.8% / +35.8% / +43.8% |
| 210 (2026) | NO_SCORED_WEEKS, live OK | — | [2024, 2025] | same as 211 |

- The totals equal research R2 v2 exactly. Real unmatched credits are 0 in both seasons, so the amended SC-002
  holds.
- Per-manager and per-player medians, and every stand-in payoff cell, equal the independent Python
  recomputation. See `baseline/hand-check-us1.md` (build pass 1, all 24 manager rows to 1e-9) and
  `baseline/hand-check-us4.md` (thresholds, script vs endpoint < 1e-13).
- 212's thresholds equal research R11's 2024-only fit, which is the out-of-sample check's input.
- Access: anonymous and unknown-league requests get 404, and a member gets 200. This was also run by the code
  review.
- **SC-006, football**: league `1346366555759341568` (NFL 2026) answers `NOT_NBA` with a reason, and the panel
  renders nothing for that status (component test). The UI for that league wasn't opened, because the signed-in
  member isn't in it.

## UI (SC-004)

- **League analysis, 2025**: the Lock-ins section renders with no console errors.
  - The headline is "1198 provable locks" (lock + tieNonLast, per F12).
  - The threshold strip reads +7 / +25 / +36 / +44%.
  - Each of the 12 manager rows has a median, an exact IQR and its counts.
  - The Players list sorts the below-minimum rows last.
  - The payoff grid shows "riding it out beat it X% (n)" with "in hindsight Y%".
  - The caveats render.
  - Every margin carries its baseline label and its n.
- **The baseline toggle** switches the whole panel to Projection. 2025 has no snapshots, and it showed "1188
  locks have no frozen projection". → *Polish P1*: before the polish pass, it also listed every manager and all
  227 players as "0 locks — too few", which was noise.
- **League analysis, 2026, live**: there were 5 calls. One was **Lock**: Paul George, 37.5 pts, +75% vs last
  season's average of 21.5, 1 left, bar +7%. The other 4 were Wait. Every call names its baseline ("last season's
  avg", since 2026 has fewer than 5 games per player) and carries the US4 caveat. I checked the calls against the
  stored schedule: BOS and DET next play on 10-23, so the 10-20 game really is each player's latest lockable game
  on 10-22. The in-progress NYK–PHI box is no longer judged (code review B1, fixed).
- **Player page, Donovan Mitchell (2025), `?tab=lock-ins`**: +33% median over 15 locks, middle half +10% to
  +42%. The list has 16 rows: 15 with a margin, plus week 1, which had too few prior games. They're spread across
  3 managers, and the week-21 playoff row is labelled. This matches the US2 hand check.

## Digest card (US5, SC-008)

- I pressed "Fetch latest stats" on `/admin/digest` (the real admin ingest). It fetched 1 NBA week and 350 games.
- The Oct 21 Basketball section then showed **"Lock-in calls · Ball Knowers"**. Its copy text was 6 LOCK entries
  (Missi, Jerome, Reid, Edwards, Maluach, Young), sorted by margin, **exactly 280 characters**.
- The unrelated local NBA league "Test" shows its card's `NO_HISTORY` reason: no completed season, so no calls.
- The existing digest cards still render unchanged, and there were no console errors.
- → *Polish P5*: the entries said "vs avg" while the baseline was last season's average. That's a label that
  doesn't name its number, and it's fixed to name the baseline.

## Timing (SC-005)

- `/lock-ins` takes 2.09 s on the first request after boot, for 211 (it loads 2024 + 2025 box data for the
  thresholds), then 0.08 s warm. Spot check on 212 / 210: 0.08 s / 0.43 s (210 includes the live Sleeper
  rosters call).
- The panel fetches separately from the rest of the page, so the analysis page isn't blocked. But a cold first
  hit is over 2 s. That's recorded as measured, not fixed (code review N1).

## Polish re-check (after P1–P5, backend restarted)

- **P1**: Projection on 2025 now shows one line ("No frozen pre-game projections cover this season's locks…")
  with the left-out counts, in place of 239 empty rows.
- **P3**: the threshold strip says it uses the season-average stand-in. The toggle state was checked in the DOM
  (`segment on`, `aria-selected=true`).
- **P5**: the digest tweet now reads "+184% vs last season's avg". The longer wording fits 4 whole entries in
  251 characters.
- There were no console errors.
- **P2** and **P4** are covered by their unit tests only. P4 couldn't be shown live, because no stale `pre_game`
  row dated before today sits in front of a judged game in the local data.

## Not verified

- A **real-browser clipboard paste** of the lock-in card into X's composer (the same owed item as spec 043's V11).
- **Projection-baseline margins on real data.** There are no snapshots locally. Prod has had them since 10-20, so
  this is checkable after 2026 week 1 is final (~10-26), with the unit tests until then.
- A **2026 final week** going through US1–US3 (the F7 guard): it can't be shown until week 1 is scored. The unit
  test covers it.
- Prod. Nothing is committed or deployed.
