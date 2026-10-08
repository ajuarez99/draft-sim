// The API client, split by domain (specs/021-codebase-cleanup US4). Import from here,
// not from './api/<domain>': 108 files and 26 vi.mock('../api') tests depend on this
// one module path. Types mirror the Java records; when you change one, change its
// file under ./api/ in the same change (AGENTS.md).
//
// http.ts is deliberately not `export *`-ed: apiFetch and json stay private to the
// api/ folder, as they were private to the single file this replaced.
export { ApiError, isNotFound } from './apiError'
export { apiUrl } from './api/http'
export * from './api/draft'
export * from './api/draftGrades'
export * from './api/identity'
export * from './api/setup'
export * from './api/managers'
export * from './api/mockDraft'
export * from './api/leagueHistory'
export * from './api/managerHistory'
export * from './api/managerComparison'
export * from './api/powerRankings'
export * from './api/leagueAnalysis'
export * from './api/simulation'
export * from './api/ballots'
export * from './api/rosterManagement'
export * from './api/expectedWins'
export * from './api/seasonForecast'
export * from './api/weeklyReport'
export * from './api/recap'
export * from './api/playerSpotlight'
export * from './api/superlatives'
export * from './api/refresh'
export * from './api/transactions'
export * from './api/schedule'
export * from './api/playerTrends'
