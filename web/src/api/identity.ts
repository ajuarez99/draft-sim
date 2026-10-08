import type { Sport } from './draft'
import { apiFetch, json } from './http'

// --- claude/user-identity-and-onboarding.md §4a/§5: Sleeper-username identity ---

/** Mirrors SleeperUserController.user's response shape. */
export type SleeperUser = {
  sleeperUserId: string
  username: string
  displayName: string
  avatar: string | null
}

/**
 * The lookup's two outcomes, kept inside one 200 rather than split across a
 * status code -- see SleeperUserController.user's comment.
 */
type SleeperUserLookup = ({ found: true } & SleeperUser) | { found: false }

/**
 * null (not a thrown error) when Sleeper genuinely has no such username.
 *
 * A 404 is deliberately NOT that case any more. This used to read
 * `if (res.status === 404) return null`, which conflated "Sleeper has no such
 * name" with "this backend has no such route" -- and the second is a routine
 * state, not an exotic one, because the frontend and backend deploy
 * independently (DEPLOY.md: Vercel + Fly). The result was that the sign-in
 * gate told a visitor with a perfectly valid username to check their spelling,
 * with no way forward: the caller's retry path never fired. A 404 now throws
 * like any other unexpected status, so SignIn reaches its `unreachable` state
 * and offers a retry instead.
 */
export async function getSleeperUser(usernameOrId: string): Promise<SleeperUser | null> {
  const res = await apiFetch(`/api/sleeper/user/${encodeURIComponent(usernameOrId)}`)
  const body = await json<SleeperUserLookup>(res)
  return body.found ? body : null
}

/** Mirrors SleeperUserController.leagues' per-row response shape. */
export type SleeperLeague = {
  sleeperLeagueId: string
  name: string
  sport: Sport
  season: number
  totalRosters: number
  draftId: string | null
  status: string | null
  previousLeagueId: string | null
  /** Already has a `league` row in this app's own DB -- true means "Set up", not "start fresh". */
  ingested: boolean
}

export const getSleeperUserLeagues = (sleeperUserId: string) =>
  apiFetch(`/api/sleeper/users/${sleeperUserId}/leagues`).then(json<SleeperLeague[]>)
