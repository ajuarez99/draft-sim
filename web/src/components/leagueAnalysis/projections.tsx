import { useMemo, useState } from 'react'
import Avatar from '../../components/Avatar'
import BumpChart, { type Series } from '../../components/BumpChart'
import { FOCUS_CAP, FOCUS_SLOTS, NO_PINS, managerHues, pinnedCount, pinsFull, toggleSelection, type PinSlots } from '../../managerColor'
import { type AnalysisProjections, type AnalysisRosterProjection, type AnalysisLineupPlayer, type AnalysisWeekTotal } from '../../api'
import { InjuryTag, ManagerLink, NotYet, pts, useTeamName } from './shared'

/**
 * One rostered player on a lineup card. The pill is coloured by the position
 * the player PLAYS and lettered with the slot they FILL, so a green FLEX reads
 * as "a running back in the flex" without a second column saying so.
 */
export function LineupRow({ player }: { player: AnalysisLineupPlayer }) {
  const benched = player.slot === 'BN'
  return (
    <li className={`analysis-slot${benched ? ' bn' : ''}`}>
      <span className={`pos ${player.position} analysis-slot-pill`}>
        {benched ? player.position : player.slot}
      </span>
      <span className="analysis-slot-name">
        {player.name}
        {player.team && <span className="muted small analysis-slot-team">{player.team}</span>}
        {/* The explanation for a zero, in place. Sleeper's own tag as of the
            last player ingest -- which is why it is shown as a label and never
            used to change a number. */}
        {player.injuryStatus && <InjuryTag status={player.injuryStatus} />}
      </span>
      <span className="mono analysis-slot-pts">{pts(player.points)}</span>
    </li>
  )
}

/**
 * This roster's remaining weeks, valued with the same lineup the bar is made
 * of -- so the strip sums to the bar exactly, and a bye reads as the dip it is.
 *
 * The lowest week is called out in words next to the chart rather than left as
 * "the short one". Height alone is a second encoding of a number the reader
 * cannot read off it (feedback_label_the_axis_spell_out_the_number).
 */
export function WeekStrip({ byWeek }: { byWeek: AnalysisWeekTotal[] }) {
  if (byWeek.length === 0) return null
  const max = Math.max(...byWeek.map((w) => w.points), 1)
  const low = byWeek.reduce((a, b) => (b.points < a.points ? b : a))
  const high = byWeek.reduce((a, b) => (b.points > a.points ? b : a))

  return (
    <div className="analysis-weekstrip">
      <div className="analysis-weekstrip-head">
        <h4 className="analysis-lineup-head">Week by week</h4>
        <span className="muted small">
          best <span className="mono">wk {high.week} · {pts(high.points)}</span>
          {' · '}
          worst <span className="mono">wk {low.week} · {pts(low.points)}</span>
        </span>
      </div>
      <ol className="analysis-weekbars">
        {byWeek.map((w) => (
          <li key={w.week} className="analysis-weekbar" title={`Week ${w.week}: ${pts(w.points)} projected`}>
            <span className="analysis-weekbar-track">
              <span
                className={`analysis-weekbar-fill${w.week === low.week ? ' low' : ''}`}
                style={{ height: `${Math.max(4, (w.points / max) * 100)}%` }}
              />
            </span>
            <span className="mono analysis-weekbar-week">{w.week}</span>
          </li>
        ))}
      </ol>
    </div>
  )
}

/**
 * The lineup behind the bar: every starter in the league's own slot order,
 * then everyone who did not start. The bench is not filler -- it is where the
 * zeroes live, and a zero next to a name is the honest version of "6
 * unprojected" in a tooltip.
 */
export function LineupCard({ roster }: { roster: AnalysisRosterProjection }) {
  return (
    <>
      <WeekStrip byWeek={roster.byWeek} />
    <div className="analysis-lineup">
      <div className="analysis-lineup-col">
        <h4 className="analysis-lineup-head">
          Starting lineup <span className="muted small">{roster.starters.length} slots</span>
        </h4>
        <ul className="analysis-slotlist">
          {roster.starters.map((p) => (
            <LineupRow key={`${p.slot}-${p.sleeperPlayerId}`} player={p} />
          ))}
        </ul>
      </div>
      <div className="analysis-lineup-col">
        <h4 className="analysis-lineup-head">
          Bench <span className="muted small">{roster.bench.length} players</span>
        </h4>
        {roster.bench.length === 0 ? (
          <p className="muted small">Every rostered player is in the lineup.</p>
        ) : (
          <ul className="analysis-slotlist">
            {roster.bench.map((p) => (
              <LineupRow key={`bn-${p.sleeperPlayerId}`} player={p} />
            ))}
          </ul>
        )}
      </div>
    </div>
    </>
  )
}

export function ProjectionsBlock({ block }: { block: AnalysisProjections }) {
  // A Set rather than one open row: two lineups open at once is the cheapest
  // form of comparison, and the panel below is the expensive one.
  const [open, setOpen] = useState<Set<number>>(new Set())

  if (!block.available) return <NotYet reason={block.reason} />

  const max = Math.max(...block.rosters.map((r) => r.total), 1)

  function toggle(rosterId: number) {
    setOpen((prev) => {
      const next = new Set(prev)
      if (!next.delete(rosterId)) next.add(rosterId)
      return next
    })
  }

  return (
    <div className="analysis-bars">
      {block.rosters.map((r) => {
        const expanded = open.has(r.rosterId)
        return (
          <div key={r.rosterId} className={`analysis-bar-row${r.isMe ? ' mine' : ''}`}>
            <div className="analysis-bar-who">
              <span className="mono analysis-rank">{r.rank}</span>
              <ManagerLink
                managerId={r.managerId}
                manager={r.manager}
                rosterId={r.rosterId}
                avatarId={r.avatarId}
                isMe={r.isMe}
              />
            </div>

            {/* One bar, segments in the app's own position colors. The bar
                carries proportion; the numbers under it carry the values --
                nothing here is readable by hue alone. */}
            <div className="analysis-track" style={{ width: `${(r.total / max) * 100}%` }}>
              {block.positionGroups.map((g) => {
                const v = r.byPosition[g] ?? 0
                if (v <= 0) return null
                return (
                  <span
                    key={g}
                    className={`analysis-seg pos-${g}`}
                    style={{ flexGrow: v }}
                    title={`${g}: ${pts(v)} projected points`}
                  />
                )
              })}
            </div>

            <div className="analysis-bar-total mono">
              {pts(r.total)}
              <span className="muted small"> pts</span>
            </div>

            {/* Its own control rather than a clickable row: the row already
                holds a link to the manager, and nesting one interactive thing
                inside another is how a keyboard user loses both. */}
            <button
              type="button"
              className="analysis-expand"
              aria-expanded={expanded}
              aria-controls={`lineup-${r.rosterId}`}
              onClick={() => toggle(r.rosterId)}
            >
              {expanded ? 'Hide lineup' : 'Lineup'}
              <span aria-hidden="true" className="analysis-caret">
                {expanded ? '▴' : '▾'}
              </span>
            </button>

            <div className="analysis-legend">
              {block.positionGroups.map((g) => {
                const v = r.byPosition[g] ?? 0
                if (v <= 0) return null
                return (
                  <span key={g} className="analysis-legend-item">
                    <span className={`pos ${g}`}>{g}</span>
                    <span className="mono">{v.toFixed(0)}</span>
                  </span>
                )
              })}
              {r.missing > 0 && (
                <span
                  className="muted small"
                  title="Rostered players Sleeper publishes no projection for — IR, Out, PUP. They count as zero, and they are named on the bench below."
                >
                  {r.missing} unprojected
                </span>
              )}
            </div>

            {expanded && (
              <div className="analysis-lineup-wrap" id={`lineup-${r.rosterId}`}>
                <LineupCard roster={r} />
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}

/**
 * The remaining season as movement: each roster's projected rank, week by week.
 *
 * <p>This exists because the scored-week chart cannot be drawn until a season
 * has two played weeks, and a live season in week 2 has exactly one -- so the
 * page offered no chart at all in the state it is in for most of September,
 * while carrying thirteen weeks of projections it was not plotting.
 *
 * The rank comes from the backend rather than being computed here: ties go
 * through `Ranker`, and a second implementation of that rule on the frontend is
 * the bug this repo keeps re-shipping.
 */
export function ProjectedBumpBlock({ block }: { block: AnalysisProjections }) {
  const teamName = useTeamName()
  const [selection, setSelection] = useState<PinSlots>(NO_PINS)
  const toggle = (rosterId: number) => setSelection((s) => toggleSelection(s, rosterId))

  const hues = useMemo(() => managerHues(block.rosters), [block.rosters])
  const meRosterId = block.rosters.find((r) => r.isMe)?.rosterId ?? null

  const series: Series[] = useMemo(
    () =>
      block.rosters.map((r) => ({
        rosterId: r.rosterId,
        managerId: r.managerId,
        manager: teamName(r.manager, r.rosterId),
        hue: hues.get(r.rosterId)?.hue ?? 0,
        points: r.byWeek.map((w) => ({
          week: w.week,
          rank: w.rank,
          score: w.points,
          note: null,
          ballotCount: null,
          thin: false,
        })),
      })),
    [block.rosters, hues, teamName],
  )

  if (!block.available) return <NotYet reason={block.reason} />

  const weeks = block.rosters[0]?.byWeek.map((w) => w.week) ?? []
  if (weeks.length < 2) {
    return <NotYet reason="One projected week is a point, not a line." />
  }

  return (
    <>
      <BumpLegend
        rosters={block.rosters}
        selection={selection}
        meRosterId={meRosterId}
        onToggle={toggle}
      />
      <BumpChart
        series={series}
        weeks={weeks}
        teamCount={block.rosters.length}
        colorBy="focus"
        selection={selection}
        meRosterId={meRosterId}
        onToggle={toggle}
      />
    </>
  )
}

/**
 * Pin up to three rosters to compare them.
 *
 * <p>This used to be a one-at-a-time highlight with a colour swatch per manager.
 * Both halves of that were wrong. The swatch was a lie -- `hueFor` put ids 1-9
 * on hues 49-57, so fourteen managers shared two colours -- and one-at-a-time
 * is not what anyone wants from a chart whose whole point is comparison.
 *
 * <p>Three is the cap because three is what colour vision can carry on crossing
 * lines; see `web/src/managerColor.ts`. The fourth pin is disabled with the
 * reason said out loud rather than silently ignored, and the swatch is now the
 * manager's avatar, which identifies them whether or not colour survives.
 */
/** Only what the legend actually reads, so both blocks' row types fit. */
export type LegendRoster = {
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
}

export function BumpLegend({
  rosters,
  selection,
  meRosterId,
  onToggle,
}: {
  rosters: readonly LegendRoster[]
  selection: PinSlots
  meRosterId: number | null
  onToggle: (rosterId: number) => void
}) {
  const teamName = useTeamName()
  const full = pinsFull(selection)
  const pinned = pinnedCount(selection)
  const hues = managerHues(rosters)

  return (
    <div className="bump-legend">
      <span className="bump-legend-hint muted small">
        {pinned === 0
          ? `Pin up to ${FOCUS_CAP} to compare them.`
          : `${pinned} of ${FOCUS_CAP} pinned.`}
      </span>
      {rosters.map((r) => {
        const slot = selection.indexOf(r.rosterId)
        const on = slot >= 0
        const mine = meRosterId === r.rosterId
        const atCap = full && !on
        return (
          <button
            key={r.rosterId}
            type="button"
            className={`bump-legend-item bump-legend-button${on ? ' on' : ''}${mine ? ' mine' : ''}`}
            aria-pressed={on}
            disabled={atCap}
            title={
              atCap
                ? `Three is the most that stay reliably distinguishable. Unpin one first.`
                : on
                  ? `Unpin ${teamName(r.manager, r.rosterId)}`
                  : `Pin ${teamName(r.manager, r.rosterId)}`
            }
            style={on ? ({ '--pin': FOCUS_SLOTS[slot] } as React.CSSProperties) : undefined}
            onClick={() => onToggle(r.rosterId)}
          >
            <Avatar
              avatarId={r.avatarId}
              seed={String(r.managerId ?? r.rosterId)}
              hue={hues.get(r.rosterId)?.hue}
              label={r.manager}
              isMe={mine}
              className="bump-legend-avatar"
            />
            {teamName(r.manager, r.rosterId)}
            {mine && <span className="cond">you</span>}
          </button>
        )
      })}
    </div>
  )
}
