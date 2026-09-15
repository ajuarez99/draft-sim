import { useEffect, useRef, useState } from 'react'
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
  onStart: (opts: {
    sport: Sport
    teams: number
    rounds: number
    sourceSleeperLeagueId: string
    sourceLeagueName: string
  }) => void
}

const SPORTS: Sport[] = ['nfl', 'nba']

/**
 * "Start a mock draft" (design_handoff_multisport_mock_drafts). Forces an
 * explicit sport choice, then a specific league whose settings to clone -- the
 * fix for mocks always defaulting to football. Submitting navigates to the
 * existing seat-setup screen (`/mock/new`) rather than creating the session
 * directly: that screen's per-seat manager assignment
 * (claude/next-features-roadmap.md's manager-tendencies-and-mock-seating work)
 * is a real, valued step this modal must not skip.
 *
 * Both sports are startable as of claude/nba-mock-drafts.md. This file used to
 * carry a `MOCKABLE_SPORTS = ['nfl']` constant, a disabled NBA chip with a
 * "Soon" badge, and a step-2 message explaining the gap; the backend that
 * refusal described now supports basketball, so all three are gone.
 *
 * The league's id goes to the backend, not just its team count: rounds, the
 * roster template and the reversal round are cloned server-side off that one
 * id (see MockDraftController.CreateRequest).
 */
export default function StartMockModal({ leagues, initialSport, initialLeagueId, onClose, onStart }: Props) {
  const [sport, setSport] = useState<Sport>(initialSport)
  const leaguesForSport = leagues.filter((l) => l.sport === sport)

  // Derived per render, not seeded once into state. `leagues` can be empty
  // when this mounts -- the rail's "Mock it" navigates to Home and opens the
  // modal in the same tick, while getDrafts() is still in flight -- and a
  // useState initializer that ran against an empty list would settle on null
  // and never recover once the leagues arrived. Storing only the *explicit*
  // choice and falling back to the seed (then to the first league of the
  // sport) makes the selection heal itself the moment the list lands, and
  // makes `changeSport` a one-line reset rather than a second copy of this
  // same defaulting rule.
  const [picked, setPicked] = useState<string | null>(null)
  const leagueId =
    (picked && leaguesForSport.some((l) => l.sleeperLeagueId === picked) ? picked : null) ??
    (initialLeagueId && leaguesForSport.some((l) => l.sleeperLeagueId === initialLeagueId)
      ? initialLeagueId
      : null) ??
    leaguesForSport[0]?.sleeperLeagueId ??
    null

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  // The list scrolls at 220px, so a seeded league further down it was selected
  // and enabled the CTA while staying out of sight -- "Start NFL mock" for a
  // league the reader can't see. `block: 'nearest'` makes this a no-op when
  // the option is already visible, so a plain click never yanks the list.
  const selectedRef = useRef<HTMLLabelElement | null>(null)
  useEffect(() => {
    selectedRef.current?.scrollIntoView({ block: 'nearest' })
  }, [leagueId])

  function changeSport(next: Sport) {
    setSport(next)
    // Never leave a cross-sport league selected -- the design's own rule.
    // Clearing the explicit pick is enough: `leagueId` above re-defaults
    // within the new sport on the very next render.
    setPicked(null)
  }

  const selected = leagueId ? leagues.find((l) => l.sleeperLeagueId === leagueId) : undefined
  const canStart = !!selected

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
            {SPORTS.map((s) => (
              <button
                key={s}
                type="button"
                className={`start-mock-sport-chip ${s}${sport === s ? ' on' : ''}`}
                aria-pressed={sport === s}
                onClick={() => changeSport(s)}
              >
                {s.toUpperCase()}
              </button>
            ))}
          </div>
        </div>

        <div className="start-mock-step">
          <span className="start-mock-step-label">2 · Use settings from</span>
          {leaguesForSport.length === 0 ? (
            <p className="muted small">No {sport.toUpperCase()} leagues to clone yet — add one from Home first.</p>
          ) : (
            <div className="start-mock-league-list" role="radiogroup" aria-label="League settings to use">
              {leaguesForSport.map((l) => (
                <label
                  key={l.sleeperLeagueId}
                  ref={leagueId === l.sleeperLeagueId ? selectedRef : undefined}
                  className={`start-mock-league-option${leagueId === l.sleeperLeagueId ? ' selected' : ''}`}
                >
                  <input
                    type="radio"
                    name="start-mock-league"
                    checked={leagueId === l.sleeperLeagueId}
                    onChange={() => setPicked(l.sleeperLeagueId)}
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
            onClick={() =>
              selected &&
              onStart({
                sport,
                teams: selected.teams,
                rounds: selected.rounds,
                sourceSleeperLeagueId: selected.sleeperLeagueId,
                sourceLeagueName: selected.leagueName,
              })
            }
          >
            Start {sport.toUpperCase()} mock
          </button>
        </div>
      </div>
    </div>
  )
}
