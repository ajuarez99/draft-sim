import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { createMockSession, getManagers, type ManagerSummary } from '../api'

// Same domain as LeagueShape.SUPPORTED_TEAM_COUNTS (engine/LeagueShape.java) --
// one team-size dropdown across the whole app, per claude/next-features-roadmap.md §3.1.
const TEAM_SIZES = [8, 10, 12, 14] as const

/** Short hint next to a manager's name so picking them reads as "real signal" vs "same as a bot." */
function provenanceHint(p: ManagerSummary['provenance']) {
  switch (p) {
    case 'NEUTRAL': return ' (no data -- same as a bot)'
    case 'STATED': return ' (your call, no history)'
    case 'FITTED': return ' (from history)'
    case 'BLENDED': return ' (your call + history)'
  }
}

/**
 * `/mock/new`: team size, "which seat is you," and now which of your real
 * managers (if any) sit in the other seats -- the real-manager seat
 * assignment MockSetup's original comment deferred (claude/next-features-
 * roadmap.md §3.3/§7#4). Any slot left on "Bot" is still the unmodelled
 * league-average drafter, same as before.
 */
export default function MockSetup() {
  const navigate = useNavigate()
  const [teams, setTeams] = useState<(typeof TEAM_SIZES)[number]>(10)
  const [userSlot, setUserSlot] = useState(1)
  const [managers, setManagers] = useState<ManagerSummary[] | null>(null)
  const [managerSeats, setManagerSeats] = useState<Record<number, number>>({})
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    getManagers().then(setManagers).catch(() => {}) // non-critical -- the mock still works with every seat left on Bot
  }, [])

  function handleTeamsChange(next: number) {
    setTeams(next as (typeof TEAM_SIZES)[number])
    // A shrink can both push userSlot back to 1 AND leave a manager already
    // assigned to slot 1 -- clamp against whichever slot ends up being the
    // user's, not just the old one, or the two collide on submit.
    const resolvedUserSlot = userSlot > next ? 1 : userSlot
    if (userSlot > next) setUserSlot(resolvedUserSlot)
    setManagerSeats((prev) => {
      const clamped: Record<number, number> = {}
      for (const [slot, managerId] of Object.entries(prev)) {
        if (Number(slot) <= next && Number(slot) !== resolvedUserSlot) clamped[Number(slot)] = managerId
      }
      return clamped
    })
  }

  function handleUserSlotChange(next: number) {
    setUserSlot(next)
    // A manager can't share the seat you just claimed.
    setManagerSeats((prev) => {
      if (!(next in prev)) return prev
      const { [next]: _dropped, ...rest } = prev
      return rest
    })
  }

  function setSeatManager(slot: number, managerId: number | null) {
    setManagerSeats((prev) => {
      const next = { ...prev }
      if (managerId == null) delete next[slot]
      else next[slot] = managerId
      return next
    })
  }

  async function start() {
    setCreating(true)
    setError(null)
    try {
      const session = await createMockSession(teams, userSlot, managerSeats)
      navigate(`/mock/${session.id}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setCreating(false)
    }
  }

  const otherSlots = Array.from({ length: teams }, (_, i) => i + 1).filter((s) => s !== userSlot)

  return (
    <div className="content">
      <section className="panel add-draft">
        <h2>New mock draft</h2>
        <p className="muted small">
          Bots fill every seat but yours and auto-pick down the snake order. Take your own picks
          on your turn -- assign a real manager to a seat below to see their tendencies play out
          instead of a league-average bot.
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
            <select value={userSlot} onChange={(e) => handleUserSlotChange(Number(e.target.value))} disabled={creating}>
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

        {managers && managers.length > 0 && (
          <div className="seat-assign-list">
            <p className="muted small">Other seats (optional -- leave any on Bot)</p>
            {otherSlots.map((slot) => (
              <div key={slot} className="seat-assign-row">
                <span className="slot-label small muted">seat {slot}</span>
                <select
                  aria-label={`seat ${slot}`}
                  value={managerSeats[slot] ?? ''}
                  onChange={(e) => setSeatManager(slot, e.target.value === '' ? null : Number(e.target.value))}
                  disabled={creating}
                >
                  <option value="">Bot</option>
                  {managers.map((m) => (
                    <option key={m.managerId} value={m.managerId}>
                      {m.manager}
                      {provenanceHint(m.provenance)}
                    </option>
                  ))}
                </select>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  )
}
