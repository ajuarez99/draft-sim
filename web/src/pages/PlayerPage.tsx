import { Link, useParams } from 'react-router-dom'
import Avatar from '../components/Avatar'
import NotFound from '../components/NotFound'
import PageHeader from '../components/PageHeader'
import PlayerFace from '../components/PlayerFace'
import {
  getPlayerStats,
  type PlayerCounting,
  type PlayerGameLogRow,
  type PlayerOwnership,
  type PlayerRanks,
  type PlayerStatsPage,
  type PlayerWindow,
  type PlayerWindowKind,
  type Rate,
} from '../api'
import { useLeagueDataVersion } from '../leagueDataVersion'
import {
  FANTASY_LABEL,
  REAL_STAT_LABEL,
  fallbackNote,
  longDate,
  ownershipAsOf,
  rankMoveSentence,
  reasonSentence,
  reasonShort,
  scoringKeyLabel,
  seasonLabel,
  type StatReason,
} from '../statCopy'
import { useBlock } from '../useBlock'

/**
 * specs/022-player-stat-analysis US1: one player in one league.
 *
 * Every figure is labelled "Real stat" (what he did on the court) or "This
 * league's fantasy" (what the league's scoring made of it), and a number that
 * cannot be given is replaced by the sentence saying why -- never a blank cell
 * or a zero standing in for "unknown". The page holds no sport rule: whether it
 * exists for a league is the destinations table's call, and the server answers
 * NOT_BASKETBALL for anything else.
 */

const WINDOWS: { kind: PlayerWindowKind; label: string }[] = [
  { kind: 'SEASON', label: 'Season' },
  { kind: 'LAST_10', label: 'Last 10' },
  { kind: 'LAST_5', label: 'Last 5' },
]

const DASH = '—'

const MINUS = '−'
/** toFixed with the page's one minus sign (U+2212) instead of a hyphen. */
const fixed = (n: number, digits: number) => n.toFixed(digits).replace('-', MINUS)
const num = (n: number | null | undefined, digits = 1) => (n == null ? DASH : fixed(n, digits))
const signed = (n: number, digits = 1) => `${n > 0 ? '+' : n < 0 ? '−' : ''}${Math.abs(n).toFixed(digits)}`
const minutes = (n: number) => fixed(n, 1)
const shortDay = (iso: string) =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })

/**
 * A percent-point rate. With a value: the percentage, and the makes-attempts beneath
 * when given. Without one: a short stated reason, the full sentence as title and
 * aria-label -- never a bare dash or a 0%.
 */
function RateValue({ r, made, att }: { r: Rate; made?: number; att?: number }) {
  if (r.value == null) {
    const code: StatReason = r.reason ?? 'UNAVAILABLE'
    const sentence = reasonSentence(code)
    return (
      <span className="pp-rate-none" title={sentence} aria-label={sentence}>
        {reasonShort(code)}
      </span>
    )
  }
  return (
    <>
      <span>{`${fixed(r.value, 1)}%`}</span>
      {made != null && att != null && <span className="pp-made muted small">{`${Math.round(made)}-${Math.round(att)}`}</span>}
    </>
  )
}

/** Every Rate with no value in these windows, so the page can say why once (deduplicated). */
function rateReasons(windows: PlayerWindow[]): StatReason[] {
  const out = new Set<StatReason>()
  for (const w of windows) {
    for (const r of [w.shooting.fgPct, w.shooting.tpPct, w.shooting.ftPct]) {
      if (r.reason) out.add(r.reason)
    }
  }
  return [...out]
}

function OwnerChip({ o }: { o: PlayerOwnership }) {
  switch (o.state) {
    case 'ROSTERED':
      return (
        <span className="pp-owner">
          <Avatar avatarId={o.avatarId} seed={o.ownerName ?? String(o.rosterId ?? '')} label={o.ownerName} isMe={o.isMe} />
          <span className="pp-owner-name">{o.ownerName ?? 'A team in this league'}</span>
          {o.isMe && <span className="lh-yours">Yours</span>}
        </span>
      )
    case 'FREE_AGENT':
      return <span className="lh-fa">Free agent</span>
    case 'NOT_DRAFTED':
      return <span className="muted small">{reasonSentence('NOT_DRAFTED')}</span>
    case 'UNAVAILABLE':
      return <span className="muted small">{reasonSentence('UNAVAILABLE')}</span>
  }
}

function Header({ d }: { d: PlayerStatsPage }) {
  const p = d.player
  const name = p.name ?? 'Unknown player'
  const own = d.ownership
  const ownLabel = own ? (ownershipAsOf(own.asOf, d.season) ?? 'Ownership') : null
  const nowLabel = d.currentOwnership && d.requestedSeason != null ? `Now (${seasonLabel(d.requestedSeason)})` : null
  const nowAsOf = d.currentOwnership ? ownershipAsOf(d.currentOwnership.asOf, d.requestedSeason ?? d.season) : null
  const otherTeams = d.teamsThisSeason.length > 1

  return (
    <section className="pp-card pp-head" aria-label="Player">
      <PlayerFace
        sport={d.sport}
        sleeperId={p.sleeperPlayerId}
        team={p.team}
        position={p.positions[0] ?? ''}
        name={name}
        size={72}
      />
      <div className="pp-head-main">
        <h2 className="pp-name">{name}</h2>
        <p className="pp-id">
          {p.positions.map((pos) => (
            <span key={pos} className="pp-pos">
              {pos}
            </span>
          ))}
          {p.team && <span className="pp-team">{p.team}</span>}
          {!p.known && <span className="muted small">{`We have games for this player but no player record (id ${p.sleeperPlayerId}).`}</span>}
        </p>
        {otherTeams && (
          <p className="muted small pp-line">{`Played for ${d.teamsThisSeason.join(', ')} in ${seasonLabel(d.season)}.`}</p>
        )}
        {d.teamGamesMissed > 0 && (
          <p className="muted small pp-line">{`Missed ${d.teamGamesMissed} of his team’s games in ${seasonLabel(d.season)}.`}</p>
        )}
        <div className="pp-own-list">
          {own && (
            <div className="pp-own">
              <span className="pp-own-label">{ownLabel}</span>
              <OwnerChip o={own} />
            </div>
          )}
          {d.currentOwnership && nowLabel && (
            <div className="pp-own pp-own-now">
              <span className="pp-own-label">{nowLabel}</span>
              <OwnerChip o={d.currentOwnership} />
              {nowAsOf && <span className="muted small">{nowAsOf}</span>}
            </div>
          )}
        </div>
      </div>
    </section>
  )
}

function SeasonPicker({ d, leagueId, playerId }: { d: PlayerStatsPage; leagueId: string; playerId: string }) {
  if (d.seasons.length === 0) return null
  return (
    <nav className="pp-seasons" aria-label="Season">
      {d.seasons.map((o) => {
        const here = o.sleeperLeagueId === leagueId
        return (
          <Link
            key={o.sleeperLeagueId}
            to={`/leagues/${encodeURIComponent(o.sleeperLeagueId)}/players/${encodeURIComponent(playerId)}`}
            className={`pp-season${here ? ' on' : ''}${o.hasGames ? '' : ' empty'}`}
            aria-current={here ? 'page' : undefined}
          >
            {seasonLabel(o.season)}
            {!o.hasGames && <span className="pp-season-note">no games yet</span>}
          </Link>
        )
      })}
    </nav>
  )
}

function SeasonLine({ d }: { d: PlayerStatsPage }) {
  const rows = WINDOWS.flatMap((w) => {
    const win = d.windows[w.kind]
    return win ? [{ ...w, win, fp: d.fantasy?.fpPerGame[w.kind] ?? null }] : []
  })
  if (rows.length === 0) return null
  const reasons = rateReasons(rows.map((r) => r.win))
  const season = d.windows.SEASON
  const per36 = season?.per36 ?? null
  const dates = season && season.firstGameDate && season.lastGameDate
    ? `${longDate(season.firstGameDate)} to ${longDate(season.lastGameDate)}`
    : null

  const pg = (c: PlayerCounting | null, k: keyof PlayerCounting) => num(c?.[k] ?? null)

  return (
    <section className="pp-card" aria-label="Season line">
      <h3 className="pp-h">{`${seasonLabel(d.season)} season line`}</h3>
      {dates && <p className="muted small pp-line">{`${dates}. Per game, unless a column says otherwise.`}</p>}
      <div className="pp-wrap">
        <table className="pp-table">
          <thead>
            <tr className="pp-group">
              <th scope="col" rowSpan={2}>
                Window
              </th>
              <th scope="colgroup" colSpan={10} className="pp-real">
                {REAL_STAT_LABEL}
              </th>
              <th scope="col" className="pp-fant">
                {FANTASY_LABEL}
              </th>
            </tr>
            <tr>
              <th scope="col">GP</th>
              <th scope="col">MIN</th>
              <th scope="col">PTS</th>
              <th scope="col">REB</th>
              <th scope="col">AST</th>
              <th scope="col">STL</th>
              <th scope="col">BLK</th>
              <th scope="col">FG%</th>
              <th scope="col">3P%</th>
              <th scope="col">FT%</th>
              <th scope="col" className="pp-fant">
                Fantasy pts / game
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({ kind, label, win, fp }) => (
              <tr key={kind}>
                <th scope="row">
                  {label}
                  {win.smallSample && <span className="pp-small">small sample</span>}
                </th>
                <td>{win.games}</td>
                <td>{win.games > 0 ? num(win.minutesPerGame) : DASH}</td>
                <td>{pg(win.perGame, 'pts')}</td>
                <td>{pg(win.perGame, 'reb')}</td>
                <td>{pg(win.perGame, 'ast')}</td>
                <td>{pg(win.perGame, 'stl')}</td>
                <td>{pg(win.perGame, 'blk')}</td>
                <td className="pp-shot">
                  <RateValue r={win.shooting.fgPct} made={win.totals.fgm} att={win.totals.fga} />
                </td>
                <td className="pp-shot">
                  <RateValue r={win.shooting.tpPct} made={win.totals.tpm} att={win.totals.tpa} />
                </td>
                <td className="pp-shot">
                  <RateValue r={win.shooting.ftPct} made={win.totals.ftm} att={win.totals.fta} />
                </td>
                <td className="pp-fant pp-strong">{num(fp, 2)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {reasons.length > 0 && (
        <ul className="muted small pp-notes">
          {reasons.map((r) => (
            <li key={r}>{`${reasonShort(r)}: ${reasonSentence(r)}`}</li>
          ))}
        </ul>
      )}
      {season && (
        <dl className="pp-facts">
          <div>
            <dt>{`${REAL_STAT_LABEL} · per 36 minutes`}</dt>
            <dd>
              {per36
                ? `${per36.pts.toFixed(1)} pts · ${per36.reb.toFixed(1)} reb · ${per36.ast.toFixed(1)} ast`
                : reasonSentence('NO_MINUTES')}
            </dd>
          </div>
          <div>
            <dt>{`${REAL_STAT_LABEL} · game score`}</dt>
            <dd>{num(season.gameScorePerGame)} per game</dd>
          </div>
          <div>
            <dt>{`${REAL_STAT_LABEL} · plus-minus`}</dt>
            <dd>{season.plusMinusPerGame == null ? DASH : `${signed(season.plusMinusPerGame)} per game`}</dd>
          </div>
        </dl>
      )}
    </section>
  )
}

/** A thin bar with its exact value printed beside it. */
function Meter({ fraction, tone }: { fraction: number; tone?: 'down' }) {
  const pct = Math.max(0, Math.min(1, fraction)) * 100
  return (
    <span className="pp-meter" aria-hidden="true">
      <span className={`pp-meter-fill${tone ? ` ${tone}` : ''}`} style={{ width: `${pct}%` }} />
    </span>
  )
}

function RankRow({ label, rank, of }: { label: string; rank: number; of: number | null }) {
  return (
    <div className="pp-rank">
      <span className="pp-rank-label">{label}</span>
      <span className="pp-rank-num">{`#${rank}`}</span>
      {of != null && of > 0 && (
        <>
          <Meter fraction={(of - rank + 1) / of} />
          <span className="muted small pp-rank-of">{`of ${of}`}</span>
        </>
      )}
    </div>
  )
}

function RanksCard({ ranks }: { ranks: PlayerRanks }) {
  return (
    <section className="pp-card" aria-label="Ranks">
      <h3 className="pp-h">{`${FANTASY_LABEL} · ranks`}</h3>
      {ranks.reason || ranks.leagueRank == null ? (
        <p className="muted small">{reasonSentence(ranks.reason ?? 'NOT_QUALIFIED')}</p>
      ) : (
        <>
          <p className="muted small pp-line">
            {`Per game this season, among ${ranks.groupSize ?? 'the'} players with enough games and minutes to be ranked.`}
          </p>
          <RankRow label="All players" rank={ranks.leagueRank} of={ranks.groupSize} />
          {ranks.positionRank != null && ranks.position && (
            <RankRow label={ranks.position} rank={ranks.positionRank} of={ranks.positionGroupSize} />
          )}
          {ranks.pointsRank != null && (
            <p className="pp-move">
              {ranks.rankMove != null && ranks.rankMove !== 0 ? (
                <span className={`pt-pill ${ranks.rankMove > 0 ? 'pt-up' : 'pt-down'}`}>
                  {`${ranks.rankMove > 0 ? '▲' : '▼'} ${Math.abs(ranks.rankMove)}`}
                </span>
              ) : null}
              <span className="muted small">
                {rankMoveSentence({ pointsRank: ranks.pointsRank, leagueRank: ranks.leagueRank, groupSize: ranks.groupSize, rankMove: ranks.rankMove })}
              </span>
            </p>
          )}
        </>
      )}
    </section>
  )
}

function BreakdownCard({ d }: { d: PlayerStatsPage }) {
  const f = d.fantasy
  if (!f || f.breakdown.length === 0) return null
  const max = Math.max(...f.breakdown.map((b) => Math.abs(b.points)), 1)
  return (
    <section className="pp-card" aria-label="Scoring breakdown">
      <h3 className="pp-h">{`${FANTASY_LABEL} · where the points come from`}</h3>
      <ol className="pp-break">
        {f.breakdown.map((b) => (
          <li key={b.key}>
            <span className="pp-break-label">{scoringKeyLabel(b.key)}</span>
            <Meter fraction={Math.abs(b.points) / max} tone={b.points < 0 ? 'down' : undefined} />
            <span className={`pp-break-pts${b.points < 0 ? ' neg' : ''}`}>{signed(b.points, 1)}</span>
            <span className="muted small pp-break-share">{b.share == null ? DASH : `${fixed(b.share * 100, 1)}%`}</span>
          </li>
        ))}
      </ol>
      <p className="muted small pp-line">{`Season total ${f.seasonTotal.toFixed(1)} fantasy points. Share is of that total; a category that costs points shows a minus.`}</p>
    </section>
  )
}

function oppText(g: PlayerGameLogRow) {
  return g.isHome == null ? g.opponent : g.isHome ? `vs ${g.opponent}` : `@ ${g.opponent}`
}

function GameLog({ d }: { d: PlayerStatsPage }) {
  if (d.gameLog.length === 0) return null
  return (
    <section className="pp-card" aria-label="Game log">
      <h3 className="pp-h">{`${seasonLabel(d.season)} game log`}</h3>
      <p className="muted small pp-line">{`Newest first. ${REAL_STAT_LABEL} columns, then ${FANTASY_LABEL} points; Team is who he played for that night.`}</p>
      <div className="pp-wrap">
        <table className="pp-table pp-log">
          <thead>
            <tr>
              <th scope="col">Date</th>
              <th scope="col">Team</th>
              <th scope="col">Opp</th>
              <th scope="col">MIN</th>
              <th scope="col">PTS</th>
              <th scope="col">REB</th>
              <th scope="col">AST</th>
              <th scope="col">STL</th>
              <th scope="col">BLK</th>
              <th scope="col">TO</th>
              <th scope="col">FG</th>
              <th scope="col">3P</th>
              <th scope="col">FT</th>
              <th scope="col">+/−</th>
              <th scope="col" className="pp-fant">
                Fantasy pts
              </th>
            </tr>
          </thead>
          <tbody>
            {d.gameLog.map((g) => (
              <tr key={g.gameId}>
                <th scope="row">{shortDay(g.date)}</th>
                <td>{g.team ?? DASH}</td>
                <td>{oppText(g)}</td>
                <td>{minutes(g.minutes)}</td>
                <td className="pp-strong">{g.line.pts}</td>
                <td>{g.line.reb}</td>
                <td>{g.line.ast}</td>
                <td>{g.line.stl}</td>
                <td>{g.line.blk}</td>
                <td>{g.line.tov}</td>
                <td>{`${g.line.fgm}-${g.line.fga}`}</td>
                <td>{`${g.line.tpm}-${g.line.tpa}`}</td>
                <td>{`${g.line.ftm}-${g.line.fta}`}</td>
                <td>{signed(g.plusMinus, 0)}</td>
                <td className="pp-fant pp-strong">{g.fantasyPoints.toFixed(2)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

export default function PlayerPage() {
  const { sleeperLeagueId, sleeperPlayerId } = useParams<{ sleeperLeagueId: string; sleeperPlayerId: string }>()
  const leagueId = sleeperLeagueId ?? ''
  const playerId = sleeperPlayerId ?? ''
  const version = useLeagueDataVersion(sleeperLeagueId)
  const block = useBlock(leagueId && playerId ? () => getPlayerStats(leagueId, playerId) : null, [leagueId, playerId, version])

  if (block.status === 'error' && block.notFound) return <NotFound what="page" />

  const d = block.status === 'ok' ? block.data : null
  const title = d ? (d.player.name ?? 'Unknown player') : 'Player'

  return (
    <div className="content">
      <PageHeader
        eyebrow={d ? `League · ${seasonLabel(d.season)}` : 'League'}
        title={title}
        sub="Real stats and this league’s fantasy points, side by side."
      />

      {(block.status === 'loading' || block.status === 'idle') && (
        <p className="muted small" role="status">
          Loading…
        </p>
      )}
      {block.status === 'error' && <p className="muted small">Couldn’t load this player.</p>}

      {d && !d.available && (
        <section className="section pp-reason">
          <p className="muted small">{d.reason ? reasonSentence(d.reason) : 'Player pages aren’t available.'}</p>
        </section>
      )}

      {d && d.available && (
        <>
          <SeasonPicker d={d} leagueId={leagueId} playerId={playerId} />
          {d.requestedSeason != null && (
            <p className="small pt-note" role="note">
              {fallbackNote(seasonLabel(d.requestedSeason), seasonLabel(d.season))}
            </p>
          )}
          <Header d={d} />

          {d.reason ? (
            <section className="section pp-reason">
              <p className="muted small">{reasonSentence(d.reason)}</p>
            </section>
          ) : (
            <>
              <SeasonLine d={d} />
              <div className="pp-cols">
                {d.fantasy && <RanksCard ranks={d.fantasy.ranks} />}
                <BreakdownCard d={d} />
              </div>
              <GameLog d={d} />
              {d.dataAsOf && <p className="muted small">{`Data refreshed ${longDate(d.dataAsOf)}.`}</p>}
            </>
          )}
        </>
      )}
    </div>
  )
}
