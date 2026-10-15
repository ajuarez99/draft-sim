# Quickstart: verifying spec 035

## Prerequisites
- Scratch DB `draftsim035` on 5433, copied from `draftsim`:
  `pg_dump -h localhost -p 5433 -U draftsim draftsim | psql -h localhost -p 5433 -U draftsim draftsim035`.
  Drop the V32/V33 history rows in the copy only if they block V34 validation (034's F1
  precedent).
- `BK_CREDENTIAL_KEY` set in the backend's environment (a dev-only random 32-byte key).
- Backend started against `draftsim035`, with the bootRun classpath checked to be this
  worktree's (memory: a worktree preview can serve main).
- `npm run dev` from this worktree's `web/`.

## 0. Before any code change: capture the Sleeper baseline (SC-005)
For every Sleeper league in `draftsim035`, save the JSON of standings/history, power,
analysis and `/api/drafts` to `specs/035-espn-yahoo-ingest/parity/before/`.

## 1. Confirm every ESPN shape (R1, R9) against the real league
Fetch `mSettings`, `mTeam`, `mRoster`, `mMatchup`, `mDraftDetail` and `mTransactions2`
for Allan's league. Use his browser devtools if the league is private; the agent never
handles the cookies. Record a sanitized copy as test fixtures (member GUIDs kept, no
cookies). Tick each field in research R9 as confirmed or amend it in research.md with an
"amended after verification" note.

## 2. Add the league through the UI
Go to the setup page, open "Add an ESPN league", paste the link, add cookies if the league
is private, and claim a team. Expect:
- the league in the picker with an ESPN badge;
- seasons listed;
- the unmatched count shown.

## 3. Compare against ESPN (SC-002, SC-003)
- Team names, managers, team count, scoring type: all equal.
- ≥3 completed weeks' matchup scores: equal to the cent.
- 20 hand-checked player matches: zero wrong.
- Read the unmatched list by eye.

## 4. Pages (SC-004)
- In scope (home, history, power, analysis, roster-management, transactions): real data.
- Out of scope: the platform message, not an empty chart.

## 5. Errors (US1 scenario 3)
- A malformed link gives `MALFORMED_REF`.
- A non-existent id gives `ESPN_NOT_FOUND`.
- Allan's private league without cookies (if private) gives `ESPN_PRIVATE_NO_ACCESS`.
- After each, the row counts in `league`/`manager`/`draft` are unchanged.

## 6. Refresh (US3)
Trigger a refresh. It must be idempotent, so the row counts are unchanged and nothing is
duplicated. Delete the credential and refresh: the league keeps its data, and its
availability shows the failure.

## 7. Sleeper parity (SC-005)
Re-capture to `parity/after/` and diff against `before/`. The only allowed difference is the
added `platform` field.

## 8. Credential leak check
grep the backend log and every captured response for the cookie values: 0 hits. Query
`league_credential.ciphertext` and confirm it doesn't contain the plaintext.
