import { apiFetch, json } from './http'

// --- claude/power-rankings-ballots.md: member ballots (power-ranking mode 2) ---

export type BallotMember = {
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  /** Sleeper's metadata.team_name, already resolved -- the backend applies the
   *  team_name -> display_name -> null chain and treats the literal "TBD" as
   *  absent (design doc finding 19), so this is renderable as-is. */
  teamName: string | null
  isMe: boolean
}

/**
 * Mirrors LeagueHistoryController.ballot(). `canSubmit` folds together every
 * reason a ballot might be refused -- signed out, not a member of this league,
 * and a week that is not the current one -- so the client never has to
 * re-derive the rule and disagree with the server about it. Sport is no longer
 * one of those reasons (claude/nba-power-rankings.md); a league whose members
 * have never been ingested is, via `isMember`, which is what an NBA league
 * looks like before its first post-V9 ingest.
 */
export type BallotState = {
  season: number
  week: number
  canSubmit: boolean
  canCommission: boolean
  /** False when no league_member row carries is_commissioner -- i.e. this
   *  league predates the flag and needs a re-ingest. The page says so rather
   *  than silently showing nobody an editor. */
  commissionerKnown: boolean
  memberCount: number
  ballotCount: number
  members: BallotMember[]
  mine: { rosterIds: number[]; submittedAt: string } | null
}

export const getBallot = (sleeperLeagueId: string, week?: number) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/ballot${week == null ? '' : `?week=${week}`}`).then(json<BallotState>)

/** rosterIds[0] is 1st. The season is read from league.season server-side and
 *  nothing here is trusted for it, so it is deliberately not a parameter. */
export const submitBallot = (sleeperLeagueId: string, week: number, rosterIds: number[]) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/ballot`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ week, rosterIds }),
    // Was typed { saved: number }; the server has always sent { saved: true, season, week }.
    // Corrected in spec 021 when the response became a record. No caller reads it yet.
  }).then(json<{ saved: boolean; season: number; week: number }>)
