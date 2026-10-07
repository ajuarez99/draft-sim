import { apiFetch, json } from './http'

// --- specs/020-ai-weekly-recap: the AI-written weekly recap ---
// Mirrors RecapController.body (backend/.../recap) field for field; every key is always present.

export type RecapState =
  | 'FEATURE_OFF'
  | 'NOT_ENTITLED'
  | 'WEEK_NOT_FINAL'
  | 'GENERATING'
  | 'READY'
  | 'FAILED'
  | 'RATE_LIMITED'

export type RecapRevisionReason = 'NUMBERS_CHANGED' | 'NAMES_CHANGED' | 'MODEL_OR_PROMPT_CHANGED' | 'REPORT_CHANGED'

export type RecapFailureReason =
  | 'UNGROUNDED'
  | 'REFUSED'
  | 'TRUNCATED'
  | 'MALFORMED'
  | 'API_ERROR'
  | 'RATE_LIMITED_UPSTREAM'

export type RecapSection = { title: string; body: string; cites: string[] }

export type RecapResponse = {
  state: RecapState
  /** The resolved season; null when the server stopped before resolving one (FEATURE_OFF). */
  season: number | null
  week: number
  /** The stored recap's model whenever a body is sent, stale included. */
  model: string | null
  generatedAt: string | null
  revision: number | null
  /** Null at revision 1 and whenever there is no body. */
  revisionReason: RecapRevisionReason | null
  /** The body is an older recap than the current numbers. */
  stale: boolean
  headline: string | null
  sections: RecapSection[] | null
  failureReason: RecapFailureReason | null
}

export const fetchRecap = (sleeperLeagueId: string, week: number, signal?: AbortSignal) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/recap/${week}`, { signal }).then(json<RecapResponse>)
