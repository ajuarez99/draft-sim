import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import Avatar from '../components/Avatar'
import BumpChart, { type Series } from '../components/BumpChart'
import { hueFor } from '../hue'
import {
  getLeagueAnalysis,
  ingestLeagueHistory,
  type LeagueAnalysis as LeagueAnalysisData,
  type AnalysisProjections,
  type AnalysisRankingScores,
  type AnalysisRosterProjection,
  type AnalysisLineupPlayer,
  type AnalysisMatchups,
  type AnalysisMatchup,
  type AnalysisSide,
  type AnalysisScores,
  type AnalysisWeekTotal,
} from '../api'

/**
 * claude/league-analysis.md: what each roster is MADE OF, as opposed to Power
 * rankings' question of who is best. Its second pass
 * (claude/league-analysis-lineups-and-matchups.md) adds the three things that
 * answer "made of what, exactly": the lineup behind a bar, two lineups against
 * each other, and the lineup you play next week.
 *
 * The bump chart ffwrapped shows alongside these blocks is deliberately absent:
 * Power rankings already draws one, and this page links to it instead of
 * drawing a second.
 */

const SCORING_LABEL: Record<string, string> = {
  PPR: 'full PPR',
  HALF_PPR: 'half PPR',
  STANDARD: 'standard',
}

function managerName(manager: string | null, rosterId: number) {
  return manager ?? `roster ${rosterId}`
}

const pts = (n: number) => n.toFixed(1)

/**
 * Sleeper's status words are long enough to swamp a name in a lineup row
 * ("Brock Bowers LV QUESTIONABLE"), so the two that appear constantly get the
 * codes every fantasy site uses and the rest are shown as they come. The full
 * word rides in the title, so shortening it never costs the reader the fact.
 */
const INJURY_CODE: Record<string, string> = { Questionable: 'Q', Doubtful: 'D' }

function InjuryTag({ status }: { status: string }) {
  return (
    <span className="analysis-inj" title={status}>
      {INJURY_CODE[status] ?? status}
    </span>
  )
}

/**
 * A block that has nothing to show yet says why, in the same shape whichever
 * block it is. Never an empty table with headers over nothing -- that reads as
 * a broken page rather than an early one.
 */
function NotYet({ reason }: { reason: string | null }) {
  return (
    <p className="muted small analysis-notyet" role="status">
      {reason ?? 'Nothing to show yet.'}
    </p>
  )
}

function ManagerLink({
  managerId,
  manager,
  rosterId,
  avatarId,
  isMe,
}: {
  managerId: number | null
  manager: string | null
  rosterId: number
  avatarId: string | null
  isMe?: boolean
}) {
  const name = managerName(manager, rosterId)
  if (managerId == null) return <span className="muted">{name}</span>
  return (
    <Link to={`/managers/${managerId}/history`} className="standings-manager">
      <Avatar avatarId={avatarId} seed={String(managerId)} label={manager} isMe={isMe} />
      {name}
    </Link>
  )
}

function RankingScoresBlock({ block }: { block: AnalysisRankingScores }) {
  if (!block.available) {
    return (
      <>
        <NotYet reason={block.reason} />
        <p className="muted small analysis-formula mono">{block.formula}</p>
      </>
    )
  }
  return (
    <>
      <div className="table-wrap">
        <table className="standings analysis-scores">
          <thead>
            <tr>
              <th className="mono">#</th>
              <th>Manager</th>
              <th className="mono">Score</th>
              <th className="mono">Record</th>
              <th className="mono">Avg</th>
              <th className="mono">High</th>
              <th className="mono">Low</th>
            </tr>
          </thead>
          <tbody>
            {block.entries.map((e) => (
              <tr key={e.rosterId}>
                <td className="mono analysis-rank">{e.rank}</td>
                <td>
                  <ManagerLink
                    managerId={e.managerId}
                    manager={e.manager}
                    rosterId={e.rosterId}
                    avatarId={e.avatarId}
                  />
                </td>
                {/* The 1-100 number and the formula's own raw output, side by
                    side. The scaling is ours, not ffwrapped's, so the page
                    shows what it scaled FROM rather than asking to be trusted. */}
                <td className="mono">
                  <span className="analysis-score-pill">{e.score.toFixed(1)}</span>
                  <span className="muted small analysis-raw">{e.raw.toFixed(1)} raw</span>
                </td>
                <td className="mono">
                  {e.wins}-{e.losses}
                  {e.ties > 0 ? `-${e.ties}` : ''}
                </td>
                <td className="mono">{pts(e.avgWeekly)}</td>
                <td className="mono">{pts(e.high)}</td>
                <td className="mono">{pts(e.low)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted small analysis-formula mono">{block.formula}</p>
    </>
  )
}

/**
 * One rostered player on a lineup card. The pill is coloured by the position
 * the player PLAYS and lettered with the slot they FILL, so a green FLEX reads
 * as "a running back in the flex" without a second column saying so.
 */
function LineupRow({ player }: { player: AnalysisLineupPlayer }) {
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
function WeekStrip({ byWeek }: { byWeek: AnalysisWeekTotal[] }) {
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
function LineupCard({ roster }: { roster: AnalysisRosterProjection }) {
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

function ProjectionsBlock({ block }: { block: AnalysisProjections }) {
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

function PositionGroupsBlock({ block }: { block: AnalysisProjections }) {
  if (!block.available) return <NotYet reason={block.reason} />
  const teams = block.rosters.length

  return (
    <div className="table-wrap">
      <table className="standings analysis-matrix">
        <thead>
          <tr>
            <th>Manager</th>
            {block.positionGroups.map((g) => (
              <th key={g} className="analysis-matrix-head">
                <span className={`pos ${g}`}>{g}</span>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {block.rosters.map((r) => (
            <tr key={r.rosterId}>
              <td>{managerName(r.manager, r.rosterId)}</td>
              {block.positionGroups.map((g) => {
                const rank = r.rankByPosition[g] ?? 0
                const points = r.byPosition[g] ?? 0
                // Strength of the tint is the rank; the rank is also printed,
                // and so is the number it came from. Color is never the only
                // thing saying how good this cell is.
                const strength = teams > 1 ? (teams - rank) / (teams - 1) : 1
                return (
                  <td
                    key={g}
                    className={`analysis-cell pos-${g}`}
                    style={{ '--tint': `${Math.round(strength * 26)}%` } as React.CSSProperties}
                    title={`${managerName(r.manager, r.rosterId)} — ${g}: ${pts(points)} projected points, ${rank} of ${teams}`}
                  >
                    <span className="mono analysis-cell-rank">{rank}</span>
                    <span className="mono analysis-cell-points">{points.toFixed(0)}</span>
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** The best projected starter on a side -- the card's one-line "why". */
function topStarter(side: AnalysisSide): AnalysisLineupPlayer | null {
  return side.starters.reduce<AnalysisLineupPlayer | null>(
    (best, p) => (best == null || p.points > best.points ? p : best),
    null,
  )
}

function GameCard({ game }: { game: AnalysisMatchup }) {
  const [a, b] = game.sides
  const mine = game.sides.some((s) => s.isMe)

  // A group of one is a bye, which the wire carries deliberately: a manager
  // with no game next week needs telling, and a missing card tells them
  // nothing.
  if (!b) {
    return (
      <div className={`analysis-game bye${mine ? ' mine' : ''}`}>
        <div className="analysis-game-side">
          <ManagerLink
            managerId={a.managerId}
            manager={a.manager}
            rosterId={a.rosterId}
            avatarId={a.avatarId}
            isMe={a.isMe}
          />
          <span className="mono analysis-game-pts">{pts(a.projected)}</span>
        </div>
        <p className="muted small analysis-game-note">No opponent this week — a bye.</p>
      </div>
    )
  }

  const margin = a.projected - b.projected
  const total = a.projected + b.projected
  const share = total > 0 ? (a.projected / total) * 100 : 50
  return (
    <div className={`analysis-game${mine ? ' mine' : ''}`}>
      {[a, b].map((side, i) => {
        const top = topStarter(side)
        return (
          <div key={side.rosterId} className={`analysis-game-row${i === 0 ? ' lead' : ''}`}>
            <div className="analysis-game-side">
              <ManagerLink
                managerId={side.managerId}
                manager={side.manager}
                rosterId={side.rosterId}
                avatarId={side.avatarId}
                isMe={side.isMe}
              />
              <span className="mono analysis-game-pts">{pts(side.projected)}</span>
            </div>
            {/* Under the manager it belongs to. A shared footer of two top
                scorers made the reader guess which was whose, which is a
                second thing for one mark to encode. */}
            {top && (
              <p className="analysis-game-top muted small">
                <span className={`pos ${top.position}`}>{top.position}</span>
                {top.name}
                <span className="mono"> {pts(top.points)}</span>
              </p>
            )}
          </div>
        )
      })}

      {/* Two projected totals subtracted, and the page says exactly that. It
          is deliberately not dressed up as a win probability: that needs a
          variance model this block does not have. */}
      <div
        className="analysis-game-split"
        role="img"
        aria-label={`${managerName(a.manager, a.rosterId)} ${pts(a.projected)}, ${managerName(b.manager, b.rosterId)} ${pts(b.projected)}`}
      >
        <span className="analysis-game-fill" style={{ width: `${share}%` }} />
      </div>

      <p className="analysis-game-margin">
        {margin === 0 ? (
          <span className="muted small">Dead level on projection.</span>
        ) : (
          <>
            <strong>{managerName(a.manager, a.rosterId)}</strong>
            <span className="muted small"> projected ahead by </span>
            <span className="mono">{pts(margin)}</span>
          </>
        )}
      </p>
    </div>
  )
}

function MatchupsBlock({ block }: { block: AnalysisMatchups }) {
  if (!block.available) return <NotYet reason={block.reason} />
  return (
    <div className="analysis-games">
      {block.matchups.map((m) => (
        <GameCard key={m.matchupId} game={m} />
      ))}
    </div>
  )
}

/**
 * Every scored week, read across. Points are the value; the week's best is
 * marked, because "who won the week" is the one thing a grid of numbers this
 * dense will not give up on its own.
 */
function ScoresBlock({ block }: { block: AnalysisScores }) {
  const [highlighted, setHighlighted] = useState<number | null>(null)

  const series: Series[] = useMemo(
    () =>
      block.rosters.map((r) => ({
        rosterId: r.rosterId,
        managerId: r.managerId,
        manager: r.manager,
        hue: hueFor(String(r.managerId ?? r.rosterId)),
        // `thin` and `note` belong to power rankings' ballot coverage; a scored
        // week has no such notion, so every point here is solid.
        points: r.weeks.map((w) => ({
          week: w.week,
          rank: w.rank,
          score: w.points,
          note: null,
          ballotCount: null,
          thin: false,
        })),
      })),
    [block.rosters],
  )

  if (!block.available) return <NotYet reason={block.reason} />

  // With one scored week, total == avg == high == low == that week, and the
  // row reads as five copies of one number pretending to be five facts. Same
  // discipline as the ranking score's own gate: the honest answer early is no
  // answer, said out loud.
  const summarisable = block.weeks.length > 1

  return (
    <>
      <div className="table-wrap">
        <table className="standings analysis-scores-grid">
          <thead>
            <tr>
              <th>Manager</th>
              {block.weeks.map((w) => (
                <th key={w} className="mono analysis-scores-week">
                  {w}
                </th>
              ))}
              {summarisable && (
                <>
                  <th className="mono">Total</th>
                  <th className="mono">Avg</th>
                  <th className="mono">High</th>
                  <th className="mono">Low</th>
                </>
              )}
            </tr>
          </thead>
          <tbody>
            {block.rosters.map((r) => {
              const byWeek = new Map(r.weeks.map((w) => [w.week, w]))
              return (
                <tr
                  key={r.rosterId}
                  className={`${r.isMe ? 'mine ' : ''}${highlighted === r.rosterId ? 'on' : ''}`}
                >
                  <td>
                    <ManagerLink
                      managerId={r.managerId}
                      manager={r.manager}
                      rosterId={r.rosterId}
                      avatarId={r.avatarId}
                      isMe={r.isMe}
                    />
                  </td>
                  {block.weeks.map((w) => {
                    const cell = byWeek.get(w)
                    if (!cell) {
                      return (
                        <td key={w} className="mono muted analysis-scores-cell" title="no game scored">
                          —
                        </td>
                      )
                    }
                    return (
                      <td
                        key={w}
                        className={`mono analysis-scores-cell${cell.rank === 1 ? ' best' : ''}`}
                        title={`${managerName(r.manager, r.rosterId)} — week ${w}: ${pts(cell.points)}, ${cell.rank} of ${block.rosters.length}`}
                      >
                        {pts(cell.points)}
                      </td>
                    )
                  })}
                  {summarisable && (
                    <>
                      <td className="mono">{pts(r.total)}</td>
                      <td className="mono">{pts(r.avg)}</td>
                      <td className="mono">{pts(r.high)}</td>
                      <td className="mono">{pts(r.low)}</td>
                    </>
                  )}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {summarisable ? (
        <>
          <p className="muted small analysis-bump-note">
            The same weeks as movement: where each roster ranked on points in each one. Click a line
            to follow it.
          </p>
          <BumpChart
            series={series}
            weeks={block.weeks}
            teamCount={block.rosters.length}
            highlighted={highlighted}
            onHighlight={setHighlighted}
          />
        </>
      ) : (
        <p className="muted small analysis-bump-note">
          One scored week is a column, not a line. The movement chart, and the total, average, high
          and low — which are all that same number until there are two weeks — appear from week two.
        </p>
      )}
    </>
  )
}

type SlotRow = { slot: string; left: AnalysisLineupPlayer | null; right: AnalysisLineupPlayer | null }

/**
 * Both lineups arrive in the league's own slot order, so the rows are that
 * order walked once, taking each side's nth player at each slot. Pairing by
 * array index alone would slide every row out of alignment the moment one
 * roster has nobody at a position -- which happens, because a roster that
 * dropped its kicker really does field fewer starters.
 */
function slotRows(left: AnalysisLineupPlayer[], right: AnalysisLineupPlayer[]): SlotRow[] {
  const order: string[] = []
  for (const p of [...left, ...right]) if (!order.includes(p.slot)) order.push(p.slot)

  const rows: SlotRow[] = []
  for (const slot of order) {
    const ls = left.filter((p) => p.slot === slot)
    const rs = right.filter((p) => p.slot === slot)
    for (let i = 0; i < Math.max(ls.length, rs.length); i++) {
      rows.push({ slot, left: ls[i] ?? null, right: rs[i] ?? null })
    }
  }
  return rows
}

function CompareCell({ player, won }: { player: AnalysisLineupPlayer | null; won: boolean }) {
  if (!player) return <span className="muted small analysis-vs-empty">nobody</span>
  return (
    <span className={`analysis-vs-player${won ? ' won' : ''}`}>
      <span className="analysis-vs-name">
        {player.name}
        {player.injuryStatus && <InjuryTag status={player.injuryStatus} />}
      </span>
      <span className="mono analysis-vs-pts">{pts(player.points)}</span>
    </span>
  )
}

/**
 * Two rosters, slot against slot, over the same rest-of-season window. Every
 * number here is already on the page -- this is the same lineups read a second
 * way, which is the whole reason it costs no request.
 */
function HeadToHead({ block }: { block: AnalysisProjections }) {
  const rosters = block.rosters

  // Your roster against the league leader, and rank 1 against rank 2 for a
  // reader who is signed out. The rosters arrive ranked, so "the leader" is
  // just the first one that is not already picked.
  const initial = useMemo(() => {
    const mine = rosters.find((r) => r.isMe) ?? rosters[0]
    const other = rosters.find((r) => r.rosterId !== mine.rosterId) ?? mine
    return { left: mine.rosterId, right: other.rosterId }
  }, [rosters])

  const [leftId, setLeftId] = useState(initial.left)
  const [rightId, setRightId] = useState(initial.right)

  const left = rosters.find((r) => r.rosterId === leftId) ?? rosters[0]
  const right = rosters.find((r) => r.rosterId === rightId) ?? rosters[0]
  const rows = useMemo(() => slotRows(left.starters, right.starters), [left, right])

  if (rosters.length < 2) {
    return <NotYet reason="One roster is not a comparison." />
  }

  const margin = left.total - right.total
  const ahead = margin > 0 ? left : right

  const picker = (value: number, onChange: (id: number) => void, label: string) => (
    <label className="analysis-vs-pick">
      <span className="muted small">{label}</span>
      <select value={value} onChange={(e) => onChange(Number(e.target.value))}>
        {rosters.map((r) => (
          <option key={r.rosterId} value={r.rosterId}>
            {r.rank}. {managerName(r.manager, r.rosterId)}
            {r.isMe ? ' (you)' : ''}
          </option>
        ))}
      </select>
    </label>
  )

  return (
    <div className="analysis-vs">
      <div className="analysis-vs-picks">
        {picker(leftId, setLeftId, 'Left')}
        <span className="analysis-vs-versus cond">vs</span>
        {picker(rightId, setRightId, 'Right')}
      </div>

      <div className="analysis-vs-head">
        <div className="analysis-vs-side">
          <ManagerLink
            managerId={left.managerId}
            manager={left.manager}
            rosterId={left.rosterId}
            avatarId={left.avatarId}
            isMe={left.isMe}
          />
          <span className="mono analysis-vs-total">{pts(left.total)}</span>
        </div>
        <div className="analysis-vs-margin">
          {leftId === rightId ? (
            <span className="muted small">Same roster on both sides.</span>
          ) : margin === 0 ? (
            <span className="muted small">level</span>
          ) : (
            // The magnitude, with the name of whoever it belongs to. Signing it
            // against the left side and then naming the winner produced
            // "-144.6 for kieriskash", a number arguing with its own label.
            <>
              <span className="mono analysis-vs-margin-n">{pts(Math.abs(margin))}</span>
              <span className="muted small">
                {' '}
                ahead for {managerName(ahead.manager, ahead.rosterId)}
              </span>
            </>
          )}
        </div>
        <div className="analysis-vs-side right">
          <span className="mono analysis-vs-total">{pts(right.total)}</span>
          <ManagerLink
            managerId={right.managerId}
            manager={right.manager}
            rosterId={right.rosterId}
            avatarId={right.avatarId}
            isMe={right.isMe}
          />
        </div>
      </div>

      <ul className="analysis-vs-rows">
        {rows.map((row, i) => {
          const l = row.left?.points ?? 0
          const r = row.right?.points ?? 0
          return (
            <li key={`${row.slot}-${i}`} className="analysis-vs-row">
              <CompareCell player={row.left} won={l > r} />
              <span className={`pos ${row.left?.position ?? row.right?.position ?? ''} analysis-vs-slot`}>
                {row.slot}
              </span>
              <CompareCell player={row.right} won={r > l} />
            </li>
          )
        })}
      </ul>

      {/* The column is signed against the left roster, so the page says so
          rather than leaving the reader to infer it from one row. */}
      <p className="muted small analysis-vs-groups-note">
        Difference is {managerName(left.manager, left.rosterId)} minus{' '}
        {managerName(right.manager, right.rosterId)}.
      </p>
      <div className="analysis-vs-groups">
        {block.positionGroups.map((g) => {
          const l = left.byPosition[g] ?? 0
          const r = right.byPosition[g] ?? 0
          const diff = l - r
          return (
            <div key={g} className="analysis-vs-group">
              <span className="mono">{l.toFixed(0)}</span>
              <span className={`pos ${g}`}>{g}</span>
              <span className="mono">{r.toFixed(0)}</span>
              <span className={`analysis-vs-group-diff${diff === 0 ? '' : diff > 0 ? ' left' : ' right'}`}>
                {diff === 0 ? 'level' : `${diff > 0 ? '+' : ''}${diff.toFixed(0)}`}
              </span>
            </div>
          )
        })}
      </div>
    </div>
  )
}

export default function LeagueAnalysis() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const [data, setData] = useState<LeagueAnalysisData | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  // Only the matchup block moves with the chosen week, so only it is refetched
  // and swapped in. Replacing the whole response would reset every open lineup
  // drawer to answer a question that did not touch them.
  const [week, setWeek] = useState<number | null>(null)
  const [weekLoading, setWeekLoading] = useState(false)

  useEffect(() => {
    if (!sleeperLeagueId) return
    setData(null)
    setError(null)
    setWeek(null)
    getLeagueAnalysis(sleeperLeagueId)
      .then(setData)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }, [sleeperLeagueId])

  async function pickWeek(next: number) {
    if (!sleeperLeagueId) return
    setWeek(next)
    setWeekLoading(true)
    try {
      const fresh = await getLeagueAnalysis(sleeperLeagueId, next)
      setData((prev) => (prev == null ? fresh : { ...prev, matchups: fresh.matchups }))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setWeekLoading(false)
    }
  }

  // Same convention as LeagueHistory: a button that fires the ingest, never a
  // curl line printed for the reader to run.
  async function loadHistory() {
    if (!sleeperLeagueId) return
    setLoading(true)
    setError(null)
    try {
      await ingestLeagueHistory(sleeperLeagueId)
      setData(await getLeagueAnalysis(sleeperLeagueId))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  const window = data?.window

  return (
    <div className="content">
      <PageHeader
        eyebrow="League"
        title="League analysis"
        sub="What each roster is made of: a composite ranking score from games already played, the rest of the regular season projected onto the lineup each manager would actually start, and next week's games read off those lineups. Who is best — the ladders, the vote, the bump chart — is Power rankings."
      />

      {error && (
        <div className="error history-error">
          <span>{error.includes('404') ? "This league hasn't been loaded yet." : error}</span>
          <button className="action-button" onClick={loadHistory} disabled={loading}>
            {loading ? 'Loading…' : 'Load this league'}
          </button>
        </div>
      )}

      {!data && !error && (
        <p className="muted small" role="status" aria-busy="true">
          Loading league analysis…
        </p>
      )}

      {data && (
        <>
          <section className="panel">
            <div className="panel-head">
              <h2>Ranking score</h2>
              {sleeperLeagueId && (
                <Link className="chip" to={`/leagues/${sleeperLeagueId}/power`}>
                  Power rankings →
                </Link>
              )}
            </div>
            <p className="muted small">
              A composite of scoring and record over the {data.rankingScores.weeksScored} week
              {data.rankingScores.weeksScored === 1 ? '' : 's'} played, scaled 1–100 with the league
              average at 50.
            </p>
            <RankingScoresBlock block={data.rankingScores} />
          </section>

          <section className="panel">
            <div className="panel-head">
              <h2>Roster projections</h2>
              <span className="muted small">
                {window && window.weeks > 0
                  ? `weeks ${window.fromWeek}–${window.toWeek} · ${SCORING_LABEL[data.scoringKey] ?? data.scoringKey}`
                  : SCORING_LABEL[data.scoringKey] ?? data.scoringKey}
              </span>
            </div>
            <p className="muted small">
              Rest-of-season points for the starting lineup each roster would field, split by the
              position the starter actually plays — a running back filling a flex slot counts under
              RB. Open a lineup to see the players the number is made of.
            </p>
            <ProjectionsBlock block={data.projections} />
          </section>

          <section className="panel">
            <div className="panel-head">
              <h2>Position group rankings</h2>
            </div>
            <p className="muted small">
              The same projections read down the columns: where each roster stands at each position.
              Big number is the rank, small number is the projected points behind it.
            </p>
            <PositionGroupsBlock block={data.projections} />
          </section>

          <section className="panel">
            <div className="panel-head">
              {/* The week is named only when there IS one. A finished season
                  refuses this block because its regular season ended at week
                  14, and heading that refusal "Week 18 matchups" would assert
                  a week the reason underneath denies. */}
              <h2>
                {data.matchups.available ? `Week ${data.matchups.week} matchups` : 'Upcoming matchups'}
              </h2>
              {window && window.toWeek >= window.fromWeek && (
                <div className="analysis-weekpick" role="group" aria-label="Choose a week">
                  {Array.from(
                    { length: window.toWeek - window.fromWeek + 1 },
                    (_, i) => window.fromWeek + i,
                  ).map((w) => {
                    const on = (week ?? data.matchups.week) === w
                    return (
                      <button
                        key={w}
                        type="button"
                        className={`chip analysis-weekchip${on ? ' on' : ''}`}
                        aria-pressed={on}
                        disabled={weekLoading}
                        onClick={() => pickWeek(w)}
                      >
                        {w}
                      </button>
                    )
                  })}
                </div>
              )}
            </div>
            <p className="muted small">
              The league's real pairings, each side's lineup rebuilt for the chosen week rather than
              sliced out of the rest-of-season total — a bye or a one-week injury moves who starts.
              The margin is two projections subtracted, not a win probability.
            </p>
            <div aria-busy={weekLoading}>
              <MatchupsBlock block={data.matchups} />
            </div>
          </section>

          <section className="panel">
            <div className="panel-head">
              <h2>Week by week</h2>
              <span className="muted small">
                {data.scores.weeks.length} week{data.scores.weeks.length === 1 ? '' : 's'} scored
              </span>
            </div>
            <p className="muted small">
              What every roster actually scored, week by week — played games, not projections, so
              nothing here moves once a week is in the books.
            </p>
            <ScoresBlock block={data.scores} />
          </section>

          <section className="panel">
            <div className="panel-head">
              <h2>Head to head</h2>
              <span className="muted small">
                {window && window.weeks > 0 ? `weeks ${window.fromWeek}–${window.toWeek}` : null}
              </span>
            </div>
            <p className="muted small">
              Any two rosters, slot against slot, over the same rest-of-season window.
            </p>
            {data.projections.available ? (
              <HeadToHead block={data.projections} />
            ) : (
              <NotYet reason={data.projections.reason} />
            )}
          </section>
        </>
      )}
    </div>
  )
}
