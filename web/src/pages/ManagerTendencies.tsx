import { useEffect, useState } from 'react'
import { getManagers, type ManagerSummary, type Sport } from '../api'
import { hueFor } from '../hue'
import { PROVENANCE_LABEL } from '../provenance'
import { reachGapText } from '../managerBehaviour'
import TendenciesForm from '../components/TendenciesForm'

/**
 * A manager profile is per (manager, sport) all the way down -- separate fits,
 * separate stated tendencies, separate rows in the database -- and the same
 * Sleeper user id is a manager in both of Allan's leagues. So a card here is a
 * manager AND a sport, and `managerId` alone is not a key.
 */
type SportManager = ManagerSummary & { sport: Sport }

/**
 * /managers -- the standalone place to declare tendencies for a real manager
 * without opening a draft, and to see whether that belief matches what their
 * own draft history actually says. The per-seat popover inside a board
 * (SeatPopover.tsx) covers the same PUT/DELETE endpoints for the seat you're
 * looking at mid-draft; this page is the "manage all of them, any time" view,
 * plus the stated-vs-empirical comparison SeatPopover has no room for.
 */

/** The actual point of this page: does the stated number agree with history? */
function comparison(m: SportManager) {
  const stated = m.stated.reachBias
  const empirical = m.empiricalReachBias
  if (stated == null || empirical == null) return null
  return `you said ${stated > 0 ? '+' : ''}${stated.toFixed(1)} · history says ${empirical > 0 ? '+' : ''}${empirical.toFixed(1)} over ${m.draftsObserved} draft${m.draftsObserved === 1 ? '' : 's'} (${m.picksScored} picks)`
}

// Fixed, not derived from the data: a per-render max would rescale every card
// whenever one manager's number moved, so two screenshots of this page could
// not be compared and a bar's length would mean something different each
// visit. +-8 picks covers every fitted value observed (widest so far ~14, which
// clamps and is labelled as over the edge).
const REACH_SCALE = 8

/** One manager's reach bias on the scale every other card uses. */
function ReachAxis({ reach }: { reach: number }) {
  const clamped = Math.max(-REACH_SCALE, Math.min(REACH_SCALE, reach))
  const half = (Math.abs(clamped) / REACH_SCALE) * 50
  const early = clamped > 0
  const near = Math.abs(reach) <= 0.5
  return (
    <div className="reach">
      <div className="reach-track" aria-hidden="true">
        <span className="reach-zero" />
        {!near && (
          <span
            className={`reach-fill${early ? ' early' : ' late'}`}
            // Grows out from the centre in the direction it means: early
            // (reaching) right, late (waiting) left. A single left-anchored
            // bar would make "waits 5 picks" and "reaches 5 picks" look the
            // same, which is the one distinction this page is about.
            style={early ? { left: '50%', width: `${half}%` } : { right: '50%', width: `${half}%` }}
          />
        )}
      </div>
      <div className="reach-caption">
        <span className="muted">waits</span>
        <b className={near ? 'muted' : early ? 'early' : 'late'}>
          {near
            ? 'drafts the board'
            : `${Math.abs(reach).toFixed(1)} picks ${early ? 'early' : 'late'}`}
          {Math.abs(reach) > REACH_SCALE ? ' +' : ''}
        </b>
        <span className="muted">reaches</span>
      </div>
    </div>
  )
}

/** The positional leans, in the position's own color. */
function tiltParts(m: SportManager) {
  const tilts = Object.entries(m.positionalTilt)
    .filter(([, v]) => Math.abs(v - 1) > 0.05)
    .sort((a, b) => Math.abs(b[1] - 1) - Math.abs(a[1] - 1))
    .slice(0, 2)
  if (tilts.length === 0) return <span className="muted">No strong positional lean</span>
  return (
    <>
      {tilts.map(([pos, v]) => (
        <span key={pos} className="tilt">
          <span className={`pos ${pos}`}>{pos}</span>
          {v > 1 ? 'early' : 'late'}
        </span>
      ))}
      {m.unpredictability >= 1.25 && <span className="tilt-note">erratic</span>}
      {m.unpredictability <= 0.8 && <span className="tilt-note">very predictable</span>}
    </>
  )
}

type RowProps = { m: SportManager; onChanged: () => void }

function ManagerRow({ m, onChanged }: RowProps) {
  const [editing, setEditing] = useState(false)

  const label = PROVENANCE_LABEL[m.provenance]
  const hue = hueFor(String(m.managerId))
  const avatarStyle = { background: `oklch(28% 0.03 ${hue})`, color: `oklch(82% 0.1 ${hue})` }
  const cmp = comparison(m)

  const canClear = m.provenance === 'STATED' || m.provenance === 'BLENDED'

  // The axis draws a number on a fixed scale, which is a claim that the number
  // was measured. It is only measured when this manager has scoreable picks,
  // or when a human asserted a value. Otherwise `effectiveReachBias` is the
  // league mean with this manager's name on it -- for every basketball
  // manager, permanently, and for any football draft older than the ADP
  // freshness window (multi-sport-and-rebrand.md, "Basketball has no reach
  // signal"). Drawing a bar at 0 there would read as "drafts the board", which
  // is the single most misleading thing this page could say.
  const hasReachNumber = m.picksScored > 0 || m.stated.reachBias != null
  const gap = reachGapText(m)

  return (
    <div className={`seat ${label.className}${m.provenance === 'NEUTRAL' ? ' neutral-row' : ''}`}>
      <div className="seat-head">
        <span className="avatar" style={avatarStyle}>
          {m.manager.trim().charAt(0).toUpperCase()}
        </span>
        <span className="who">{m.manager}</span>
        {/* Same pill as the picker's league cards, same reason: this is one
            mixed list with no switcher, and two of these cards can carry the
            same person's name because they are the same person in two
            leagues. Without the pill they are indistinguishable. */}
        <span className={`sport-pill ${m.sport}`}>{m.sport.toUpperCase()}</span>
        <span className="seat-head-right">
          {/* A dot, not the shouting badge this used to carry. 23 of the 24
              cards on this page are FITTED, so a bright green "FROM HISTORY"
              on every one of them marked nothing while outweighing the
              manager's own name. Same dot vocabulary as the board's column
              headers -- provenance.ts exists to keep those two in step. */}
          {label.badge && (
            <span className={`prov-dot ${label.className}`} title={`Tendencies ${label.badge}`} />
          )}
          <button
            className="seat-edit"
            onClick={() => setEditing((v) => !v)}
            title={editing ? 'Stop editing without saving' : "Edit this manager's stated tendencies"}
          >
            {editing ? 'Cancel' : 'Edit'}
          </button>
        </span>
      </div>

      {editing ? (
        <TendenciesForm
          managerId={m.managerId}
          sport={m.sport}
          initial={m.stated}
          canClear={canClear}
          onDone={() => {
            setEditing(false)
            onChanged()
          }}
          onCancel={() => setEditing(false)}
        />
      ) : (
        <>
          {m.provenance === 'NEUTRAL' && m.draftsObserved === 0 ? (
            <p className="muted small">Drafts like the room — nothing entered, no history yet.</p>
          ) : (
            <>
              {/* The page's actual question is comparative -- "who is the
                  biggest reacher in my league" -- and it used to be answered
                  by 24 sentences you had to read and hold in your head. Every
                  card draws on the same fixed scale, so the ranking is
                  visible without reading any of them.

                  A card with no reach number gets the reason in its place,
                  not a bar at zero. The tilt line still renders under it:
                  positional lean is fitted from every pick, so it survives
                  the absence of a board snapshot that kills reach. */}
              {hasReachNumber ? (
                <ReachAxis reach={m.effectiveReachBias} />
              ) : (
                <p className="muted tiny reach-gap">
                  {gap ?? 'No history and no stated value — there is no reach number to show.'}
                </p>
              )}
              <p className="small tilt-line">{tiltParts(m)}</p>
            </>
          )}
          {m.note && <p className="note small">“{m.note}”</p>}
          {cmp && <p className="tiny mono">{cmp}</p>}
        </>
      )}
    </div>
  )
}

// One request per sport, because /api/managers fits one sport at a time --
// there is no "all sports" mode and inventing one backend-side would mean
// merging two different fits into one list server-side, which is what this
// page is for. Both, then, and each card says which it is.
const SPORTS: readonly Sport[] = ['nfl', 'nba']

/**
 * Is there anything to say about this manager in this sport?
 *
 * ProfileService.fit(sport) returns a profile for EVERY manager in the
 * database, not only the ones who play that sport -- so asking for both sports
 * (which this page does) turns 42 managers into 84 cards, 29 of them people
 * who have never been in an NBA league and never will be. Measured live
 * 2026-09-09: nba total 42, withHistory 13, stated 0.
 *
 * The endpoint is right to answer that way -- SeatPopover looks a manager up
 * by id and needs the profile to exist even when it is empty -- so the list is
 * where the filtering belongs. The count of what was dropped is still printed,
 * because "there is nothing here" and "we are not showing you something" are
 * different claims.
 */
function hasAnythingToSay(m: SportManager): boolean {
  return (
    m.draftsObserved > 0 ||
    m.stated.reachBias != null ||
    m.stated.unpredictability != null ||
    m.stated.note != null
  )
}

/**
 * How much this card has to say, low number first. The old key was
 * `provenance === 'NEUTRAL'`, which put every basketball manager in the
 * bottom bucket beside the genuinely empty seats -- they are NEUTRAL for
 * lack of a *reach* fit (ProfileService's hasData is picksScored > 0) while
 * still carrying a real positional tilt fitted from two seasons of picks.
 */
function rank(m: SportManager): number {
  if (m.picksScored > 0 || m.stated.reachBias != null) return 0
  if (m.draftsObserved > 0) return 1
  return 2
}

export default function ManagerTendencies() {
  const [managers, setManagers] = useState<SportManager[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  function refetch() {
    Promise.all(
      SPORTS.map((sport) => getManagers(sport).then((ms) => ms.map((m) => ({ ...m, sport })))),
    )
      .then((perSport) => {
        setManagers(perSport.flat())
        setError(null)
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }

  useEffect(refetch, [])

  // Configured seats first (real signal, worth reading), neutral ones after --
  // eleven identical "nothing entered" cards burying the three that matter is
  // exactly the noise provenance.ts already avoids on the board's own headers.
  const visible = managers ? managers.filter(hasAnythingToSay) : null
  const hidden = managers && visible ? managers.length - visible.length : 0

  const sorted = visible
    ? [...visible].sort((a, b) => {
        const ar = rank(a)
        const br = rank(b)
        if (ar !== br) return ar - br
        // Within the group that has a reach number: most extreme first, so the
        // order down the page agrees with what the axes show across it.
        // Alphabetical scattered the biggest reachers among the mildest ones
        // and made the shared scale harder to read than it needed to be.
        if (ar === 0) return Math.abs(b.effectiveReachBias) - Math.abs(a.effectiveReachBias)
        // Within the group that has history but no reach number there is no
        // shared scale to agree with, so order by how much history there is.
        if (ar === 1) return b.draftsObserved - a.draftsObserved
        return a.manager.localeCompare(b.manager)
      })
    : null

  return (
    <div className="content">
      <section className="panel">
        <div className="panel-head">
          <h2>Manager tendencies</h2>
        </div>
        <p className="muted small">
          What a manager's own draft history says, fitted by the engine -- reach bias and
          unpredictability aren't something you type in, only something you can watch. Add a
          note as a reminder for yourself; it never changes how a mock or live sim drafts.
          Football and basketball are fitted separately and listed together; the same person
          appears once per sport.
        </p>

        {error && <div className="error">{error}</div>}

        {sorted && sorted.length === 0 && <p className="muted">No managers ingested yet.</p>}

        {hidden > 0 && (
          <p className="muted tiny">
            {hidden} more {hidden === 1 ? 'profile is' : 'profiles are'} empty — managers with no drafts
            and nothing entered in that sport. Every manager gets a profile per sport whether or not
            they play it.
          </p>
        )}

        {sorted && sorted.length > 0 && (
          <div className="manager-grid">
            {sorted.map((m) => (
              <ManagerRow key={`${m.sport}-${m.managerId}`} m={m} onChanged={refetch} />
            ))}
          </div>
        )}
      </section>
    </div>
  )
}
