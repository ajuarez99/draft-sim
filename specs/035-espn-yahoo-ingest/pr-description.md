## Summary

Adds **ESPN league ingest** (spec 035; Yahoo is deferred to its own spec). An ESPN league (NFL or NBA, points scoring) can be added by link and a team claimed. It then shows in the league picker with an ESPN badge, and the league pages work on it: standings, history, power, analysis, roster management and transactions. Pages that can't be produced honestly for ESPN say so instead of showing zeros.

- **Translate at the boundary.** ESPN data is written into the same tables, in the same dialect, the Sleeper ingest uses. Read paths are unchanged apart from three seams:
  - `LeagueRosterSource`
  - a per-league caller resolver for "you"
  - `LeagueAvailability`, the one source for "can this league show page X"
- **Backend platform guard.** Draft room, mocks, sims and Sleeper ingest routes refuse an ESPN key with 422 `PLATFORM_NOT_SUPPORTED`. A stranger gets the ordinary 404.
- **ESPN never feeds the draft model.** Profile fitting, ADP backfill and mock seating are filtered to Sleeper.
- **Players are matched by name with an ambiguity-refusing matcher.** Sleeper's `espn_id` crosswalk was measured as unusable: 61 of the 200 most-owned NFL players matched, one to the wrong person, and 0 NBA. Unmatched players are listed and shown by their ESPN name.
- **Private leagues** use the user's ESPN cookies. They're encrypted per league (AES-256-GCM) with `BK_CREDENTIAL_KEY`, and never logged, returned or stored in failure text. That last point is guarded by `CredentialLeakIT` at DEBUG.
- **ESPN NBA season numbering:** ESPN labels NBA seasons by the year they end (2027 for 2026-27), so ESPN season 2027 is app season 2026 (`EspnSeasons`).
- **Migration `V37__espn_platform.sql`:** additive only. It comes after main's V33 (034), V34 (033) and V36 (036). It was built as V35 and renumbered at the merge, because Flyway would refuse a V35 arriving after V36.

Process docs are in `specs/035-espn-yahoo-ingest/`: spec, plan with amendments, `plan-review.md` (21 findings, all accepted), `code-review.md` (1 blocker and 9 should-fix, all fixed with tests shown to fail without the fix), and `verification.md`.

## Before merging: deploy notes

- **Set `BK_CREDENTIAL_KEY`** on the Railway backend service: 32 random bytes, base64. Without it the app runs normally, but private ESPN leagues are refused with `CREDENTIALS_NOT_CONFIGURED`.
- **V37 runs on the prod DB** at deploy. It's additive: new columns default to `SLEEPER`, plus new tables.

## Verification

**Verified (run):**
- **Sleeper parity (SC-005).** Every Sleeper league route plus drafts, board and managers (214 routes over 10 leagues), compared before (8346cdf) and after on a scratch DB copy:
  - Status codes are identical.
  - The only structural change is the added `platform: "SLEEPER"` on `/api/drafts`.
  - `/api/managers` for both sports is byte-identical.
  - Remaining numeric diffs (0.1 rounding, last float bit) also appear between two restarts of the *unchanged* baseline. That's pre-existing nondeterminism, tracked separately.
- **Real ESPN, Allan's private NBA league.** Every error path was hit against real ESPN, and no rows were written on any of them:
  - `ESPN_PRIVATE_NO_ACCESS`
  - `ESPN_CREDENTIAL_REJECTED` (invented test cookies; the value never reached the DEBUG log)
  - `ESPN_NOT_FOUND`
  - `MALFORMED_REF`
  - `SIGN_IN_REQUIRED`, including an `espn:`-shaped header
- **Tests.** Backend 1918: the only failure is the pre-existing `BoardRepositoryLatestBeforeIT`, which depends on data the scratch DB lacks, and the one skip is pre-existing. Web: `tsc` clean, 2149 vitest passing, build ok.

**Not verified:**
- **The happy path against real ESPN data.** Allan's league is private, and no real payload has been seen yet. Every ESPN field mapping (settings, teams, schedule, draft, transactions, scoring) follows the documented guesses in research R9, each marked `// unverified (T053)`. A wrong guess fails loudly (`ESPN_SHAPE_CHANGED`) rather than writing zeros, but **adding a real ESPN league may not work until T053 is done.**
- **The ESPN UI in a browser.** The setup panel, badge, page gates and unmatched-player note are covered by vitest only.

## Merge notes

- `origin/main` (033 + 034) is already merged in. While doing that, I fixed 034's new `POST /api/ingest/brackets/{id}`, which wasn't platform-guarded. The guard's two route lists now come from one source.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
