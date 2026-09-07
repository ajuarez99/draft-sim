import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { createMockSession, getManagers, type ManagerSummary } from '../api'
import { hueFor } from '../hue'
import { roundPickLabel } from '../roundPickLabel'

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
 *
 * B2 (claude/design-review-next-steps.md): this used to be up to thirteen
 * identical full-width `<select>`s stacked under two tiny dropdowns and a
 * "Start" button wedged above all of it -- the CTA sat above the thing it
 * configured, and nothing on screen showed the shape of the room you were
 * seating. It is now a seat strip: click a seat to take it, an inline select
 * on every other seat to hand it to a real manager, and the CTA at the
 * bottom next to a one-line summary of what you're about to start.
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

  const assignedCount = Object.keys(managerSeats).length
  const botCount = teams - 1 - assignedCount
  // "3 real managers · 6 bots · you at 1.01" -- lets the whole room be
  // confirmed at a glance instead of re-reading every seat before hitting
  // the button next to it.
  const summary = `${assignedCount} real manager${assignedCount === 1 ? '' : 's'} · ${botCount} bot${botCount === 1 ? '' : 's'} · you at ${roundPickLabel(userSlot, teams)}`

  return (
    <div className="content">
      <section className="panel add-draft">
        <h2>New mock draft</h2>
        <p className="muted small">
          Bots fill every seat but yours and auto-pick down the snake order. You take your own
          picks on your turn. Assign a real manager to a seat to see their tendencies play out
          instead of a league-average bot.
        </p>

        {error && <div className="error">{error}</div>}

        {/* Exclusive choice among four fixed values -> `.segmented`, per the
            control-hierarchy rule. Deliberately not inside `.controls`:
            `.controls button:not(.chip)` (0,1,1) would repaint every
            `.segment` (0,1,0) as a solid teal button, the exact collision
            E2 found and fixed for `.chip` -- nothing had put a `.segment`
            inside a `.controls` block until now, so it never got the same
            guard. Easier to just not do the thing that needs guarding. */}
        <div className="mock-team-size">
          <span className="mock-team-size-label muted tiny">Teams</span>
          <div className="segmented" role="group" aria-label="Number of teams">
            {TEAM_SIZES.map((n) => (
              <button
                key={n}
                type="button"
                className={`segment${teams === n ? ' on' : ''}`}
                aria-pressed={teams === n}
                onClick={() => handleTeamsChange(n)}
                disabled={creating}
              >
                {n}
              </button>
            ))}
          </div>
        </div>

        {/* The seat grid itself needs no manager data -- pick numbers and
            "Bot" render immediately. Only the per-seat assignment select
            depends on this load, so unlike the old layout (where the whole
            assignment section was hidden behind this line) this is now
            purely informational: it disappears and a small select fades
            into seats that were already sitting at their final position and
            size, rather than a whole section appearing underneath them. */}
        {managers == null && (
          <p className="muted small" role="status" aria-busy="true">
            Loading managers you can seat…
          </p>
        )}

        <div className="mock-seat-strip" role="group" aria-label="Draft seats -- click one to make it yours">
          {Array.from({ length: teams }, (_, i) => i + 1).map((slot) => {
            const pickLabel = roundPickLabel(slot, teams)
            const isMine = slot === userSlot
            const assignedId = managerSeats[slot]
            const assignedManager = assignedId != null ? managers?.find((m) => m.managerId === assignedId) : undefined
            // Hashed on the manager's name, the same device the league crest
            // and the board's seat popover use, so "this seat has a real
            // manager behind it" reads the same color everywhere in the app.
            const hue = assignedManager ? hueFor(assignedManager.manager) : undefined

            return (
              <div key={slot} className={`mock-seat${isMine ? ' mine' : assignedManager ? ' assigned' : ''}`}>
                <button
                  type="button"
                  className="mock-seat-face"
                  style={assignedManager ? { background: `oklch(30% 0.05 ${hue})`, color: `oklch(84% 0.12 ${hue})` } : undefined}
                  onClick={() => handleUserSlotChange(slot)}
                  aria-pressed={isMine}
                  aria-label={isMine ? `Your seat, pick ${pickLabel}` : `Take pick ${pickLabel} as your seat`}
                  disabled={creating}
                >
                  <span className="mock-seat-pick">{pickLabel}</span>
                  <span className="mock-seat-who">{isMine ? 'You' : (assignedManager?.manager ?? 'Bot')}</span>
                </button>

                {/* Compact inline select, not a full-width row -- this
                    replaces a `<select>` per seat, not just its look. Same
                    `seat ${slot}` aria-label as before, since that's what a
                    screen reader (and the tests) key off of; only the
                    footprint changed. Your own seat never gets one -- you
                    can't hand your own pick to someone else. */}
                {!isMine && managers && managers.length > 0 && (
                  <select
                    aria-label={`seat ${slot}`}
                    className="mock-seat-select"
                    value={assignedId ?? ''}
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
                )}
              </div>
            )
          })}
        </div>

        {/* CTA after the configuring, not above it -- the room's shape is
            now the thing you look at before hitting this, not two dropdowns
            you fill in before ever seeing a seat. */}
        <div className="controls mock-seat-cta">
          <p className="muted small mock-seat-summary">{summary}</p>
          <button onClick={start} disabled={creating}>
            {creating ? 'Starting…' : 'Start the draft'}
          </button>
        </div>
      </section>
    </div>
  )
}
