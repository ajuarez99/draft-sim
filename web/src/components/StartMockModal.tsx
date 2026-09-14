import { useEffect, useState } from 'react'
import type { Sport } from '../api'

export type MockableLeague = {
  sleeperLeagueId: string
  leagueName: string
  sport: Sport
  teams: number
  rounds: number
  season: number
}

type Props = {
  leagues: MockableLeague[]
  initialSport: Sport
  initialLeagueId?: string | null
  onClose: () => void
  onStart: (opts: { teams: number; sourceLeagueName: string }) => void
}

const SPORTS: Sport[] = ['nfl', 'nba']

// NBA mock drafts don't exist on the backend yet -- MockDraftService refuses
// anything but Sport.NFL (multi-sport-and-rebrand.md's Non-goals). The design
// this modal implements assumed both sports were mockable; Allan's call
// (2026-09-13) was to keep the NBA chip visible rather than hide it, so the
// gap reads as "not yet" rather than as a missing sport nobody explains.
const MOCKABLE_SPORTS: Sport[] = ['nfl']

/**
 * "Start a mock draft" (design_handoff_multisport_mock_drafts). Forces an
 * explicit sport choice, then a specific league's team count/rounds to clone
 * -- the fix for mocks always defaulting to football. Submitting navigates to
 * the existing seat-setup screen (`/mock/new`) rather than creating the
 * session directly: that screen's per-seat manager assignment
 * (claude/next-features-roadmap.md's manager-tendencies-and-mock-seating
 * work) is a real, valued step this modal must not skip.
 */
export default function StartMockModal({ leagues, initialSport, initialLeagueId, onClose, onStart }: Props) {
  const [sport, setSport] = useState<Sport>(MOCKABLE_SPORTS.includes(initialSport) ? initialSport : 'nfl')
  const leaguesForSport = leagues.filter((l) => l.sport === sport)
  const [leagueId, setLeagueId] = useState<string | null>(
    initialLeagueId && leagues.find((l) => l.sleeperLeagueId === initialLeagueId)?.sport === sport
      ? initialLeagueId
      : (leaguesForSport[0]?.sleeperLeagueId ?? null),
  )

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  function changeSport(next: Sport) {
    setSport(next)
    // Never leave a cross-sport league selected -- the design's own rule.
    const firstOfSport = leagues.find((l) => l.sport === next)
    setLeagueId(firstOfSport?.sleeperLeagueId ?? null)
  }

  const selected = leagueId ? leagues.find((l) => l.sleeperLeagueId === leagueId) : undefined
  const canStart = MOCKABLE_SPORTS.includes(sport) && !!selected

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className="modal-card start-mock-modal"
        role="dialog"
        aria-modal="true"
        aria-label="Start a mock draft"
        onClick={(ev) => ev.stopPropagation()}
      >
        <button className="modal-close" onClick={onClose} aria-label="Close">
          ✕
        </button>

        <p className="start-mock-kicker cond">Practice round</p>
        <h2 className="start-mock-title cond">Start a mock draft</h2>

        <div className="start-mock-step">
          <span className="start-mock-step-label">1 · Sport</span>
          <div className="start-mock-sport-chips">
            {SPORTS.map((s) => {
              const mockable = MOCKABLE_SPORTS.includes(s)
              return (
                <button
                  key={s}
                  type="button"
                  className={`start-mock-sport-chip ${s}${sport === s ? ' on' : ''}${mockable ? '' : ' disabled'}`}
                  aria-pressed={sport === s}
                  disabled={!mockable}
                  title={mockable ? undefined : 'NBA mock drafts are coming soon'}
                  onClick={() => changeSport(s)}
                >
                  {s.toUpperCase()}
                  {!mockable && <span className="start-mock-soon">Soon</span>}
                </button>
              )
            })}
          </div>
        </div>

        <div className="start-mock-step">
          <span className="start-mock-step-label">2 · Use settings from</span>
          {!canStart && sport !== 'nfl' ? (
            <p className="muted small">
              NBA mock drafts aren't available yet — pick NFL above to start one.
            </p>
          ) : leaguesForSport.length === 0 ? (
            <p className="muted small">No {sport.toUpperCase()} leagues to clone yet — add one from Home first.</p>
          ) : (
            <div className="start-mock-league-list" role="radiogroup" aria-label="League settings to use">
              {leaguesForSport.map((l) => (
                <label
                  key={l.sleeperLeagueId}
                  className={`start-mock-league-option${leagueId === l.sleeperLeagueId ? ' selected' : ''}`}
                >
                  <input
                    type="radio"
                    name="start-mock-league"
                    checked={leagueId === l.sleeperLeagueId}
                    onChange={() => setLeagueId(l.sleeperLeagueId)}
                  />
                  <span className="start-mock-league-radio" aria-hidden="true" />
                  <span className="start-mock-league-text">
                    <span className="start-mock-league-name">{l.leagueName}</span>
                    <span className="start-mock-league-meta muted small">
                      {l.teams} teams · {l.rounds} rounds · {l.season}
                    </span>
                  </span>
                </label>
              ))}
            </div>
          )}
        </div>

        <div className="start-mock-footer">
          <button type="button" className="league-link" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="home-hero-cta start-mock-cta"
            disabled={!canStart}
            onClick={() => selected && onStart({ teams: selected.teams, sourceLeagueName: selected.leagueName })}
          >
            Start {sport.toUpperCase()} mock
          </button>
        </div>
      </div>
    </div>
  )
}
