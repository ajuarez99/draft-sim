# Implementation Plan: Season links keep you on the page you were on

**Branch**: `011-season-scoped-rail-links` | **Base**: `origin/main` @ `587fbc3`

The plan for this feature is `claude/season-scoped-rail-links.md`: "What's there now",
"Design" A–C and the 2026-09-30 amendments. This file only points to it.

- **Stack**: React + TypeScript + Vite (`web/src`), vitest. Backend (Spring Boot 3.5 /
  Java 21) changes only if US3's investigation calls for them.
- **Files touched**: `web/src/destinations.ts`, `web/src/components/LeagueRailSection.tsx`,
  the six one-season pages under `web/src/pages/`, and their tests. Possibly
  `PlayoffOddsService.java`, `SeasonForecastController.java` and `web/src/api.ts` (US3).
- **Schema**: no migration expected. Any migration is the next `V<n+1>`; existing ones
  are never edited.
- **Task breakdown**: `tasks.md`.
