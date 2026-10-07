import type { Sport } from './draft'
import { apiFetch, json } from './http'

// Mirrors DraftGradesService's records (spec 018, contracts/api.md C1), field for field.
export type ProductionBasis = 'WEEKLY_GAME' | 'WEEKLY_AVERAGE_GAME'
export type DraftGradesReason = 'NOT_CONFIGURED' | 'DRAFT_NOT_COMPLETE' | 'NO_SCORED_WEEKS'

export type PickGrade = {
  pickNo: number
  round: number
  slot: number
  sleeperPlayerId: string
  playerName: string
  position: string | null
  production: number
  weeksPlayed: number
  slotBaseline: number | null
  valueOverSlot: number | null
  positionDrafted: number | null
  positionFinish: number | null
  countedForYou: number | null
  creditedForYou: number | null
  weeksStartedForYou: number | null
  weeksUnknownForYou: number | null
}

export type TeamGrade = {
  slot: number
  manager: string | null
  avatarId: string | null
  draftValue: number | null
  rank: number | null
  grade: string | null
  bestPickNo: number | null
  worstPickNo: number | null
}

export type DraftGrades = {
  draftId: string
  sport: Sport
  season: number
  available: boolean
  reason: DraftGradesReason | null
  productionBasis: ProductionBasis
  countedWeeks: number[]
  weeksCounted: number
  weeksMissingGameData: number[]
  gradesEarly: boolean
  earlyThresholdWeeks: number
  minPicksPerPosition: number | null
  excludedPicks: number
  unmappedPicks: number
  unpositionedPicks: number
  averageTeamRawValue: number | null
  picks: PickGrade[]
  teams: TeamGrade[]
  steals: number[]
  busts: number[]
}

export const getDraftGrades = (draftId: string) =>
  apiFetch(`/api/drafts/${draftId}/grades`).then(json<DraftGrades>)
