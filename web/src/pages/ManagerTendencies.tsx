import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { createPortal } from 'react-dom'
import { getLeagueHistory, getManagers, type ManagerSummary, type Sport } from '../api'
import { PROVENANCE_LABEL } from '../provenance'
import { archetype, reachGapText, relativeReachRead } from '../managerBehaviour'
import { cachedDrafts } from '../railLeague'
import TendenciesForm from '../components/TendenciesForm'
import Avatar from '../components/Avatar'
import PageHeader from '../components/PageHeader'
import HowThisWorks from '../components/HowThisWorks'
import SportFilterRail from '../components/SportFilterRail'
import { useRailContextSlot } from '../appSlots'
import { useSportFilter } from '../sportFilter'

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
// visit. The figure is picks earlier/later than the manager's own draft room
// (audit 11); measured spread on the local league is roughly +-25 with most
// managers inside +-10, so +-15 keeps the bars readable and a value past the
// edge clamps and is labelled with a trailing "+".
const REACH_SCALE = 15

/**
 * One manager's reach relative to their draft room, on the scale every other
 * card uses. The shaded band is one standard error either side of zero: a bar
 * that does not leave it is not distinguishable from the room, and the caption
 * says "drafts like the room" instead of a number.
 */
function ReachAxis({ rel, se }: { rel: number; se: number | null }) {
  const read = relativeReachRead(rel, se)
  if (!read) return null
  const clamped = Math.max(-REACH_SCALE, Math.min(REACH_SCALE, rel))
  const half = (Math.abs(clamped) / REACH_SCALE) * 50
  const early = clamped > 0
  const drawn = read.kind === 'early' || read.kind === 'late'
  const bandHalf = se == null ? 0 : (Math.min(se, REACH_SCALE) / REACH_SCALE) * 50
  return (
    <div className="reach">
      <div className="reach-track" aria-hidden="true">
        {bandHalf > 0 && (
          <span className="reach-band" style={{ left: `${50 - bandHalf}%`, width: `${bandHalf * 2}%` }} />
        )}
        <span className="reach-zero" />
        {drawn && (
          <span
            className={`reach-fill${early ? ' early' : ' late'}`}
            // Grows out from the centre in the direction it means: earlier than
            // the room right, later left. A single left-anchored bar would make
            // "5 later" and "5 earlier" look the same, which is the one
            // distinction this page is about.
            style={early ? { left: '50%', width: `${half}%` } : { right: '50%', width: `${half}%` }}
          />
        )}
      </div>
      <div className="reach-caption">
        <span className="muted">later</span>
        <b className={drawn ? (early ? 'early' : 'late') : 'muted'}>
          {read.text}
          {Math.abs(rel) > REACH_SCALE ? ' +' : ''}
          {se != null && <span className="muted mono reach-se"> (±{se.toFixed(1)})</span>}
        </b>
        <span className="muted">earlier</span>
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
  const cmp = comparison(m)

  const canClear = m.stated.note != null

  // The axis draws a number on a fixed scale, which is a claim that the number
  // was measured. It is only measured when this manager has scoreable picks,
  // or when a human asserted a value. Otherwise `effectiveReachBias` is the
  // league mean with this manager's name on it -- for every basketball
  // manager, permanently, and for any football draft older than the ADP
  // freshness window (multi-sport-and-rebrand.md, "Basketball has no reach
  // signal"). Drawing a bar at 0 there would read as "drafts the board", which
  // is the single most misleading thing this page could say.
  const hasReachNumber = m.relativeReachBias != null
  const gap = reachGapText(m)
  // spec 013 US9: one label per manager, built on the same reach read as the axis beside it.
  const arch = archetype(m)

  return (
    <div className={`mgr-row ${label.className}${m.provenance === 'NEUTRAL' ? ' neutral-row' : ''}`}>
      <div className="mgr-row-main">
        <div className="mgr-identity">
          <Avatar avatarId={m.avatarId} seed={String(m.managerId)} label={m.manager} />
          <span className="mgr-identity-text">
            {/* The page listing every manager linked to none of them: a
                manager's own history was reachable only by clicking their name
                inside a league's standings table, which is a strange place to
                have to go to read the page named after them. */}
            <Link to={`/managers/${m.managerId}/history`} className="who mgr-who-link">
              {m.manager}
            </Link>
            <span className="mgr-archetype-chip" title={arch.basis}>
              {arch.label}
            </span>
            {/* The evidence is visible, not tooltip-only (touch has no hover). A reach-based
                label's evidence IS the reach caption this row already prints, so it isn't repeated. */}
            {arch.source !== 'reach' && <span className="muted small mgr-archetype-basis">{arch.basis}</span>}
            {/* Note and comparison ride under the name rather than taking
                columns of their own: both are optional and only a handful of
                managers have either, so a column for them would be mostly
                empty width taken from the meter, which every row has. */}
            {m.note && (
              // Truncated to one line in a 170px column, so the full note has to
              // be reachable somehow -- it is the one field here a human typed.
              <span className="mgr-note" title={m.note}>
                “{m.note}”
              </span>
            )}
            {cmp && <span className="mgr-cmp mono">{cmp}</span>}
          </span>
          {/* Same pill as the picker's league cards, same reason: this is one
              mixed list, and two of these rows can carry the same person's
              name because they are the same person in two leagues. Without
              the pill they are indistinguishable. */}
          <span className={`sport-pill ${m.sport}`}>{m.sport.toUpperCase()}</span>
        </div>

        <div className="mgr-meter">
          {m.provenance === 'NEUTRAL' && m.draftsObserved === 0 ? (
            <p className="muted small">Drafts like the room — nothing entered, no history yet.</p>
          ) : (
            /* The page's actual question is comparative -- "who is the
               biggest reacher in my league" -- and every row draws on the
               same fixed scale so the ranking is visible without reading any
               of them. That only works if the scales line up, which is what
               the four-across card grid this replaced was quietly breaking:
               bars sitting in three or four different columns share no
               baseline, so the one comparison the axis exists for could not
               actually be made with it. See styles.css DENSITY -- rows for
               lists, and re-judge the shape when the content changes.

               A row with no reach number gets the reason in the bar's place,
               not a bar at zero. */
            <>
              {hasReachNumber ? (
                <ReachAxis rel={m.relativeReachBias as number} se={m.relativeReachStdErr} />
              ) : (
                // Clamped, with the full sentence on hover. Every basketball
                // manager gets one of these and they are near-identical, so
                // thirteen four-line paragraphs down a list said the same
                // thing thirteen times and buried the rows that had a number
                // -- the same reason the provenance badge became a dot.
                <p className="muted tiny reach-gap" title={gap ?? undefined}>
                  {gap ?? 'No history and no stated value — there is no reach number to show.'}
                </p>
              )}
            </>
          )}
        </div>

        {/* Positional lean is fitted from every pick, so it survives the
            absence of the board snapshot that kills reach -- its own column
            rather than a line under a meter that may not be there. */}
        <p className="small tilt-line mgr-tilt">{tiltParts(m)}</p>

        <div className="mgr-actions">
          {/* A dot, not the shouting badge this used to carry. Nearly every
              row on this page is FITTED, so a bright green "FROM HISTORY" on
              all of them marked nothing while outweighing the manager's own
              name. Same dot vocabulary as the board's column headers --
              provenance.ts exists to keep those two in step. */}
          {label.badge && (
            <span className={`prov-dot ${label.className}`} title={`Tendencies ${label.badge}`} />
          )}
          <button
            className="seat-edit"
            onClick={() => setEditing((v) => !v)}
            title={editing ? 'Stop editing without saving' : 'Edit your private note about this manager'}
          >
            {editing ? 'Cancel' : 'Edit'}
          </button>
        </div>
      </div>

      {/* Full width under the row, not inside a cell: the form is several
          fields wide and would otherwise have to fit the meter's column. */}
      {editing && (
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
  if (m.relativeReachBias != null) return 0
  if (m.draftsObserved > 0) return 1
  return 2
}

export default function ManagerTendencies() {
  const [managers, setManagers] = useState<SportManager[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  // The same filter Home uses, on the same key (sportFilter.ts). This page
  // already printed a per-row NFL/NBA pill *because* the list is mixed, and
  // had no way to narrow it -- while the rail carried a sport filter that did
  // nothing here. claude/site-wide-shell-propagation.md Phase 5.
  const [sportFilter, setSportFilter] = useSportFilter()
  const rail = useRailContextSlot()

  // The rail's currently selected league, as the rail itself passes it along
  // (Rail.tsx puts it in route state on the Managers link; AppShell reads the
  // same key for manager history). No league there -> no grouping, never a guess.
  const location = useLocation()
  const railLeagueId = (location.state as { railLeagueId?: string } | null)?.railLeagueId ?? null
  const [league, setLeague] = useState<{ name: string; sport: Sport; managerIds: Set<number> } | null>(null)
  useEffect(() => {
    if (!railLeagueId) {
      setLeague(null)
      return
    }
    let live = true
    async function load() {
      // Sport and name from the rail's own draft list; members from the league's standings.
      const [drafts, history] = await Promise.all([cachedDrafts(), getLeagueHistory(railLeagueId as string)])
      const d = drafts.find((x) => x.sleeperLeagueId === railLeagueId)
      if (!live) return
      if (!d) {
        setLeague(null)
        return
      }
      // Members of the rail league's OWN season, not every season in its chain.
      const own = history.seasons.find((s) => s.sleeperLeagueId === railLeagueId) ?? history.seasons[0]
      const ids = new Set<number>()
      for (const r of own?.standings ?? []) if (r.managerId != null) ids.add(r.managerId)
      setLeague({ name: d.leagueName, sport: d.sport, managerIds: ids })
    }
    load().catch(() => {
      if (live) setLeague(null)
    })
    return () => {
      live = false
    }
  }, [railLeagueId])

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
  const withSomethingToSay = managers ? managers.filter(hasAnythingToSay) : null

  // Counted before the filter, not after -- a count that changed to match
  // whatever you had already selected would be useless for deciding what to
  // select. Same rule as the league counts on Home.
  const counts = {
    all: withSomethingToSay?.length ?? 0,
    nfl: withSomethingToSay?.filter((m) => m.sport === 'nfl').length ?? 0,
    nba: withSomethingToSay?.filter((m) => m.sport === 'nba').length ?? 0,
  }

  const visible = withSomethingToSay
    ? withSomethingToSay.filter((m) => sportFilter === 'all' || m.sport === sportFilter)
    : null
  // Still counted against the whole fetch, so this stays "profiles with
  // nothing to say", not "profiles the sport filter also removed" -- those
  // are different claims and the sentence below makes the first one.
  const hidden = managers && withSomethingToSay ? managers.length - withSomethingToSay.length : 0

  const sorted = visible
    ? [...visible].sort((a, b) => {
        const ar = rank(a)
        const br = rank(b)
        if (ar !== br) return ar - br
        // Within the group that has a reach number: most extreme first, so the
        // order down the page agrees with what the axes show across it.
        // Alphabetical scattered the biggest reachers among the mildest ones
        // and made the shared scale harder to read than it needed to be.
        if (ar === 0) return Math.abs(b.relativeReachBias ?? 0) - Math.abs(a.relativeReachBias ?? 0)
        // Within the group that has history but no reach number there is no
        // shared scale to agree with, so order by how much history there is.
        if (ar === 1) return b.draftsObserved - a.draftsObserved
        return a.manager.localeCompare(b.manager)
      })
    : null

  // The selected league's managers first (same sport, in that league's standings),
  // each group keeping the page's own order.
  const isInLeague = (m: SportManager) =>
    league != null && m.sport === league.sport && league.managerIds.has(m.managerId)
  const inLeague = sorted ? sorted.filter(isInLeague) : []
  const rest = sorted ? sorted.filter((m) => !isInLeague(m)) : []

  return (
    <div className="content">
      {rail.node &&
        createPortal(
          <SportFilterRail
            sportFilter={sportFilter}
            onSportFilterChange={setSportFilter}
            counts={counts}
            collapsed={rail.collapsed}
          />,
          rail.node,
        )}

      {/* The heading and this paragraph used to live inside the panel below,
          which made the page's own name read as a caption for its first box.
          claude/site-wide-shell-propagation.md Phase 4. */}
      <PageHeader
        eyebrow="Across your leagues"
        title="Scouting report"
        sub="Who in your leagues reaches, who waits on a position, and who drafts like the room, read from their own draft history."
      />

      <section className="section">
        <p className="muted small">
          Football and basketball are fitted separately and listed together; the same person
          appears once per sport. Only managers who share a league with you are listed.
        </p>

        {error && <div className="error">{error}</div>}

        {sorted && sorted.length === 0 && (
          <p className="muted">
            {sportFilter === 'all'
              ? 'No managers loaded yet.'
              : `No ${sportFilter.toUpperCase()} managers with anything to show yet.`}
          </p>
        )}

        {hidden > 0 && (
          <p className="muted tiny">
            {hidden} more {hidden === 1 ? 'profile is' : 'profiles are'} empty — managers with no drafts
            and nothing entered in that sport. Every manager gets a profile per sport whether or not
            they play it.
          </p>
        )}

        {sorted && sorted.length > 0 && (
          <>
            {inLeague.length > 0 && (
              <>
                <h4 className="mgr-group">In {league?.name}</h4>
                <div className="mgr-list">
                  {inLeague.map((m) => (
                    <ManagerRow key={`${m.sport}-${m.managerId}`} m={m} onChanged={refetch} />
                  ))}
                </div>
              </>
            )}
            {inLeague.length > 0 && rest.length > 0 && <h4 className="mgr-group">Everyone else</h4>}
            {rest.length > 0 && (
              <div className="mgr-list">
                {rest.map((m) => (
                  <ManagerRow key={`${m.sport}-${m.managerId}`} m={m} onChanged={refetch} />
                ))}
              </div>
            )}
          </>
        )}

        <HowThisWorks>
          <p>
            What a manager's own draft history says, fitted by the engine — reach bias and
            unpredictability aren't something you type in, only something you can watch. A note is a
            reminder for yourself: only you can see it, and it never changes how a mock or live sim
            drafts.
          </p>
          <p>
            Reach is measured against the other managers in the same draft, not the market board.
            The board itself runs several picks off for every room, so an absolute figure would
            mostly measure that. The shaded band is one standard error: with about 15 picks per
            draft most managers sit inside it, and that reads as “drafts like the room”.
          </p>
        </HowThisWorks>
      </section>
    </div>
  )
}
