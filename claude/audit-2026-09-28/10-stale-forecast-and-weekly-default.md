# 10 — Season forecast goes stale silently; weekly report always opens on week 1

**Severity: Medium. Verified locally** on league 1346366555759341568, before 009 merged.
Both findings still stand after 009: re-checked in code, not in a browser.

## What's there now

- **A.** The forecast header read "THROUGH WEEK 1 · 10,000 SIMULATED SEASONS", but every other
  page showed 2 weeks scored.
  - `web/src/pages/SeasonForecast.tsx:81` prints `data.week` from the stored snapshot.
  - Odds are written only by `POST /power/compute` (`LeagueHistoryController.java:815`, odds
    at `:826`), per `claude/playoff-odds.md:135`: "never on a page load".
  - 009 does **not** change this. `refresh/LeagueRefreshService.java` calls history and
    per-game ingest only, and nothing in `refresh/` touches playoff odds or power rankings.
    009 only re-fetches the stale snapshot faster (`SeasonForecast.tsx:47` `dataVersion`).
  - The copy at `:59` and `:104` says the odds are as of the last recompute, but never says
    how far behind that is.
- **B.** The weekly report's week comes from `useState(1)`. That's `web/src/pages/WeeklyReport.tsx:28`,
  not `:27` as first reported. The input is `max={18}` (`:68`), a hardcoded NFL number that
  is wrong for NBA. The `onChange` at `:70` clamps only the low end. The payload
  (`api.ts:1745`) has no latest-scored-week field.

## Fix

1. **A: recommend the visible notice.** Don't auto-recompute on refresh, because refresh
   is triggered by a page visit, and `playoff-odds.md` bans exactly that.
   - Add `latestScoredWeek` to the `/forecast` payload
     (`SeasonForecastController.java:55`) and mirror it in `api.ts` in the same commit.
   - When it's ahead of the snapshot, show: "N weeks scored since this forecast. The odds
     below are as of week W."
   - Show a Recompute button beside the notice. It calls the existing
     `computePowerRankings` (`api.ts:1424`), and only commissioners see it.
   - Recomputing from the daily Action (not a visit) is a legitimate later option, but
     Allan decides that. Don't add it here.
2. **B:** add `latestScoredWeek` to the weekly-report payload, or have the page read it
   from an existing league call.
   - Open on that week when the URL has none. Keep the user's pick in `?week=` so a link
     reproduces the view.
   - Set the input's `max` to `latestScoredWeek`, drop the hardcoded 18, and clamp both
     ends in `onChange`.
   - With zero weeks scored, keep the existing "has not been scored" panel rather than
     week 0.

## Not in scope

- Commissioner gating. The recompute endpoint checks membership only
  (`LeagueHistoryController.java:819`), but the button says "(commissioner)"
  (`PowerRankings.tsx:887`). That's a separate audit item.
- Changing the forecast model or its iteration count.

## Acceptance criteria (in-browser, real `bootRun` + `vite dev`)

- [ ] Forecast with the snapshot at week 1 and 2 weeks scored: the notice names both
      weeks, a commissioner sees Recompute, and after clicking it the header reads "Through
      week 2" with no notice.
- [ ] A non-commissioner sees the notice but no button.
- [ ] A network trace of loading the forecast shows no `POST /power/compute`.
- [ ] `/weekly-report` with no `?week=` opens on the latest scored week (2), not week 1.
- [ ] You can't enter or arrow past the latest scored week. Check an NBA league too.
- [ ] `npx tsc -b` passes, and the `api.ts` types match the Java payloads field for field.
