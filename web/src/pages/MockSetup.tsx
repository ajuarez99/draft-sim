import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { createMockSession } from '../api'

// Same domain as LeagueShape.SUPPORTED_TEAM_COUNTS (engine/LeagueShape.java) --
// one team-size dropdown across the whole app, per claude/next-features-roadmap.md §3.1.
const TEAM_SIZES = [8, 10, 12, 14] as const

/**
 * `/mock/new`: team size + "which seat is you," nothing else. v1 scope per
 * §3.3/§7#4 of the roadmap -- every other seat defaults to a bot; a roster
 * editor and real-manager seat assignment are both deferred, not designed out.
 */
export default function MockSetup() {
  const navigate = useNavigate()
  const [teams, setTeams] = useState<(typeof TEAM_SIZES)[number]>(10)
  const [userSlot, setUserSlot] = useState(1)
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function handleTeamsChange(next: number) {
    setTeams(next as (typeof TEAM_SIZES)[number])
    if (userSlot > next) setUserSlot(1)
  }

  async function start() {
    setCreating(true)
    setError(null)
    try {
      const session = await createMockSession(teams, userSlot)
      navigate(`/mock/${session.id}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setCreating(false)
    }
  }

  return (
    <div className="content">
      <section className="panel add-draft">
        <h2>New mock draft</h2>
        <p className="muted small">
          Bots fill every seat but yours and auto-pick down the snake order. Take your own picks on your turn.
        </p>

        {error && <div className="error">{error}</div>}

        <div className="controls">
          <label>
            teams
            <select value={teams} onChange={(e) => handleTeamsChange(Number(e.target.value))} disabled={creating}>
              {TEAM_SIZES.map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </select>
          </label>
          <label>
            your slot
            <select value={userSlot} onChange={(e) => setUserSlot(Number(e.target.value))} disabled={creating}>
              {Array.from({ length: teams }, (_, i) => i + 1).map((slot) => (
                <option key={slot} value={slot}>
                  {slot}
                </option>
              ))}
            </select>
          </label>
          <button onClick={start} disabled={creating}>
            {creating ? 'starting…' : 'start mock draft'}
          </button>
        </div>
      </section>
    </div>
  )
}
