# 13 — Not-found pages and copy consistency

**Severity: Low (design/copy). Seen locally in the browser; file:line refs re-read against
`d90c2f9`.** Spec 009's "plain wording everywhere" (T030–T033) only removed `/api/ingest`
hints from backend strings. It touched none of the six items below, so all six still apply.

## What's there now

1. **Not-found is designed in one place and raw everywhere else.**
   - `web/src/App.tsx:130-143`: the `*` route shows a "Nothing here" panel with a way back.
   - A real route with an unknown id doesn't reach it. The backend returns `notFound().build()`
     with an empty body (`LeagueAnalysisController.java:51`, `MockDraftController.java:100`).
     `json()` then falls back to `res.statusText` (`web/src/api.ts:158`; the same fallback is at
     `:1453` and `:2007`). So `/mock/99999999` shows a bare "Not Found"
     (`MockDraftView.tsx:80-85`).
   - Six league pages test `error.includes('404')`, which never matches "Not Found", so their
     friendly branch never runs: `LeagueAnalysis.tsx:1082`, `ExpectedWins.tsx:65`,
     `RosterManagement.tsx:92`, `SeasonForecast.tsx:64`, `Superlatives.tsx:103` and
     `WeeklyReport.tsx:78`. Only `LeagueHistory.tsx:470` also checks for "not found".
   - `LeagueAnalysis.tsx:1083` offers "Load this league" for any 404. But the controller also
     returns 404 to non-members (`membership.canSee`), and on a made-up id the ingest can only
     fail. Since 009, a league you belong to refreshes itself on visit, so the button is stale too.
   - *Read, not executed:* over HTTP/2, `statusText` is `""`. Production may render an empty
     red box, not "Not Found".
2. **One person, two names.** `AnalysisScoreEntry.manager`, `PowerRankingEntry.manager`
   (`api.ts:1183`, `:1270`), History standings (`LeagueHistory.tsx:102`) and `/managers` show
   the Sleeper username. Superlatives, Weekly report, Expected wins, Roster management and
   Forecast show `teamName` (`api.ts:1566-1781`). Nothing on screen connects the two names.
   `BallotMember` (`api.ts:1500`) already carries both.
3. **The home subtitle names one arbitrary league.** `DraftPicker.tsx:316-320` uses
   `drafts[0]` (`:287`), which ignores `sportFilter`. So "All sports", and even the NBA filter,
   can describe a football league.
4. **Superlatives copy.**
   - `Superlatives.tsx:358` renders "weeks 1" when there's one close game.
   - `:365` and `:374` can render "weeks 3–3".
   - `:98`'s subtitle is written for developers: "A card that isn't built yet says so…".
   - `:659` uses `--` where the rest of the UI uses an em dash.
5. **Roster management waiver rows.**
   - `RosterManagement.tsx:239-243` renders "TE5.0 · 1 wk". The number is an average
     positional rank, explained only in the legend at `:223`.
   - `:219` renders the FAAB bid as a bare "$51", with no label.
6. **A raw code formula.** `LeagueAnalysis.tsx:117` and `:167` print
   `LeagueAnalysisService.java:298` verbatim: `((avgWeeklyScore * 6) + …) / 10`. It is
   ffwrapped's published formula, kept verbatim on purpose (`LeagueAnalysisService.java:43`).

## Fix — three tasks

**A. One not-found state (items 1).**
- Pull the `App.tsx` panel into a `<NotFound what="league|mock|draft" />` component.
- Make `json()` throw a typed `ApiError` with `status`, and never use an empty message. Pages
  branch on `status === 404`, not on string matching. Update all seven call sites.
- On a league 404, say "No league here, or it isn't one of yours". Drop the "Load this league"
  button: 009's refresh-on-visit covers real members.
- Mock and draft 404s render the same component.

**B. One naming rule (item 2).** *Allan decides first.* Proposed rule:
- Inside a league page, the **team name is primary**, with the username as a muted second
  line, as `BallotMember` already allows.
- Cross-league pages (`/managers`, manager history) stay **username-primary**, because team
  names change per league and per season.
- This needs `teamName` added to `PowerRankingEntry`, `AnalysisScoreEntry` and the History
  rows, and `username` added to the five team-name payloads. Mirror each change in
  `web/src/api.ts` in the same commit. Use a `Map.of`-safe builder: `teamName` is nullable.

**C. Copy pass (items 3–6).**
- Home: when the filter is "All sports", use a generic line. Otherwise pick the newest draft
  *in the filtered sport*.
- Superlatives:
  - Add a `weekSpan(from, to)` helper: "week 3" vs "weeks 3–5", and "week 1" vs "weeks 1, 4".
  - Rewrite the `:98` subtitle for readers.
  - Change `--` to `—`.
- Roster: show the rank as "TE5 avg over 1 wk", or keep the value with a `title=`
  tooltip. Label the bid "$51 FAAB".
- Analysis: show the formula as plain maths ("average week × 6, plus (best + worst week)
  × 2, plus win % × 400, all ÷ 10"). Keep the verbatim string in a `<details>` labelled
  "ffwrapped's formula, as published", so the provenance stays visible.
- Update the tests that assert the old strings. `LeagueAnalysis.test.tsx:149` is one.

## Not in scope

- Changing the ranking formula, or anything 009's guard test (`NoIngestHintsInMessagesTest`)
  already covers.
- A site-wide `--` sweep. Most hits are code comments. Fix only the rendered one listed.

## Acceptance criteria (browser, local `bootRun` + `vite dev`)

- [ ] `/mock/99999999`, `/drafts/99999999`, `/leagues/999999/analysis` and one other league page
  (say, `/superlatives`) all show the same "Nothing here" panel with a link home. None shows
  "Not Found", an empty red box, or "Load this league".
- [ ] A real league the signed-in user isn't in shows that same panel.
- [ ] After task B, the same manager reads the same way on Analysis, Power, History,
  Superlatives and Roster management. Screenshot one manager on each.
- [ ] The home subtitle under "All sports" names no league. Under "NBA" it never names a
  football league.
- [ ] Superlatives shows "week 1" for a single week. No card shows "weeks N–N", and no
  rendered `--` remains.
- [ ] A Roster management waiver row reads without the legend, and the bid says what it is.
- [ ] The analysis page shows no identifier like `avgWeeklyScore`. The verbatim formula is still
  one click away.
- [ ] `npx tsc -b`, `npm test` and `./gradlew test` all pass. Check the skip count is 0.

## Decided 2026-09-29 (Allan)

**Naming rule: the team name first, with the Sleeper username as the secondary line, on every page.**
