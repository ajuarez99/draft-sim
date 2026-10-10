type Props = {
  teams: number
  rounds: number
  /** Seconds per pick; null/undefined when the draft has none (or we don't know). */
  pickTimerSeconds?: number | null
  /** e.g. "snake"; null/undefined when unknown. */
  draftType?: string | null
  /** A mock with a third-round reversal says so (spec 024 FR-017); 0/undefined is plain snake. */
  reversalRound?: number
}

/** 90 -> "1 min 30 s", 120 -> "2 min", 28800 -> "8 h", 45 -> "45 s". */
export function formatTimer(seconds: number): string {
  if (seconds >= 3600) {
    const h = Math.floor(seconds / 3600)
    const m = Math.round((seconds - h * 3600) / 60)
    return m > 0 ? `${h} h ${m} min` : `${h} h`
  }
  if (seconds >= 60) {
    const m = Math.floor(seconds / 60)
    const s = seconds - m * 60
    return s > 0 ? `${m} min ${s} s` : `${m} min`
  }
  return `${seconds} s`
}

/**
 * "4 teams · 14 rounds · 2 min · snake" (spec 024 FR-017). A missing timer or
 * type is left out, not printed as "unknown": the line states what is known.
 */
export default function FormatSummary({ teams, rounds, pickTimerSeconds, draftType, reversalRound }: Props) {
  if (teams <= 0 || rounds <= 0) return null
  const parts = [
    `${teams} ${teams === 1 ? 'team' : 'teams'}`,
    `${rounds} ${rounds === 1 ? 'round' : 'rounds'}`,
    ...(pickTimerSeconds != null && pickTimerSeconds > 0 ? [formatTimer(pickTimerSeconds)] : []),
    ...(draftType ? [draftType] : []),
    ...(reversalRound != null && reversalRound > 0 ? [`order reverses from round ${reversalRound}`] : []),
  ]
  return <span className="format-summary muted small">{parts.join(' · ')}</span>
}
