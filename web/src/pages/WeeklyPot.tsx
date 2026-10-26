import { useEffect, useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import Avatar from '../components/Avatar'
import NotFound from '../components/NotFound'
import CommissionerPotPanel from '../components/CommissionerPotPanel'
import {
  getWeeklyPot,
  type WeeklyPot as WeeklyPotData,
  type WeeklyPotBalance,
  type WeeklyPotDebt,
  type WeeklyPotOn,
  type WeeklyPotWeek,
} from '../api'
import { useFailure } from '../useFailure'

/**
 * Spec 048: the weekly high-score pot. Each decided regular-season week's top scorer collects the stake from
 * every other roster; everything here is derived server-side, in integer cents. Money is formatted here only.
 */

const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' })
const money = (cents: number): string => usd.format(cents / 100)
const signed = (cents: number): string => (cents > 0 ? '+' : cents < 0 ? '−' : '') + money(Math.abs(cents))

const STATUS_WORDS: Record<WeeklyPotWeek['status'], string> = {
  DECIDED: '',
  NO_SCORES: 'No scores recorded',
  NOT_FINAL: 'Not final yet',
  MISSING: 'Not loaded yet',
}

export default function WeeklyPot() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const [data, setData] = useState<WeeklyPotData | null>(null)
  const { error, notFound, setError, fail } = useFailure()

  useEffect(() => {
    if (!sleeperLeagueId) return
    let cancelled = false
    setData(null)
    setError(null)
    getWeeklyPot(sleeperLeagueId)
      .then((d) => {
        if (!cancelled) setData(d)
      })
      .catch((e) => {
        if (!cancelled) fail(e)
      })
    return () => {
      cancelled = true
    }
  }, [sleeperLeagueId])

  if (notFound) return <NotFound what="league" />

  return (
    <div className="content wp-page">
      <PageHeader
        eyebrow={data ? `League · ${data.season}` : 'League'}
        title="Weekly pot"
        sub={
          data?.enabled
            ? `${money(data.stakeCents)} a week from everyone to the top scorer.`
            : 'A side bet on the week’s highest score.'
        }
      />
      {error && <p className="wp-error" role="alert">{error}</p>}
      {!data && !error && <p className="muted">Loading…</p>}
      {data && !data.enabled && (
        <section className="panel wp-off">
          <p>
            The weekly pot is a side bet some leagues run: whoever scores the most in a regular-season week collects
            a small stake from every other team.
          </p>
          {!data.canEdit && (
            <p className="muted">
              {data.commissionerKnown
                ? 'This league’s commissioner hasn’t turned it on.'
                : 'No commissioner is recorded for this league yet.'}
            </p>
          )}
        </section>
      )}
      {data && data.canEdit && sleeperLeagueId && (
        <CommissionerPotPanel
          key={`${data.enabled}-${data.stakeCents}`}
          leagueSleeperId={sleeperLeagueId}
          enabled={data.enabled}
          stakeCents={data.stakeCents}
          onSaved={setData}
        />
      )}
      {data?.enabled && <PotBody d={data} />}
    </div>
  )
}

/** Header text: label the same weeks the money includes (latestDecidedWeek), and name the gaps below it. */
function rangeText(d: WeeklyPotOn): string {
  if (d.seasonFinal) return 'Final.'
  if (d.latestDecidedWeek === 0) return 'No week is decided yet.'
  if (d.latestDecidedWeek > d.throughWeek) {
    const gaps = d.weeks
      .filter((w) => w.week < d.latestDecidedWeek && (w.status === 'NOT_FINAL' || w.status === 'MISSING'))
      .map((w) => w.week)
    // a week the list doesn't carry at all (never stored) is still a gap below the latest decided one
    const listed = new Set(d.weeks.map((w) => w.week))
    for (let w = 1; w < d.latestDecidedWeek; w++) if (!listed.has(w)) gaps.push(w)
    gaps.sort((a, b) => a - b)
    return `Includes weeks through ${d.latestDecidedWeek}; ${gaps.length === 1 ? 'week' : 'weeks'} ${gaps.join(', ')} not settled yet.`
  }
  return `Through week ${d.throughWeek}.`
}

function PotBody({ d }: { d: WeeklyPotOn }) {
  const byRoster = useMemo(() => new Map(d.balances.map((b) => [b.rosterId, b])), [d.balances])
  const nameOf = (id: number) => byRoster.get(id)?.name ?? `Roster ${id}`
  const anyTie = d.weeks.some((w) => w.status === 'DECIDED' && w.winners.length > 1)

  return (
    <>
      <p className="wp-range muted">
        {rangeText(d)}{' '}
        {d.regularSeasonKnown && d.regularSeasonThroughWeek != null
          ? `Regular season: weeks 1–${d.regularSeasonThroughWeek}.`
          : 'This league’s playoff start isn’t recorded, so every scored week counts.'}
      </p>
      <LiveLine d={d} nameOf={nameOf} />
      <Standings balances={d.balances} />
      <WeekList weeks={d.weeks} nameOf={nameOf} />
      <SettleUp d={d} nameOf={nameOf} />
      {anyTie && <p className="wp-foot muted">{d.tieCentRule}</p>}
    </>
  )
}

function LiveLine({ d, nameOf }: { d: WeeklyPotOn; nameOf: (id: number) => string }) {
  if (d.liveUnavailable) return <p className="wp-live muted">Couldn{'’'}t check the live week.</p>
  if (!d.liveLeader) return null
  const l = d.liveLeader
  return (
    <p className="wp-live">
      <span className="wp-live-dot" aria-hidden="true" />
      <span>
        Leading week {l.week} {'—'} not final: <strong>{l.rosterIds.map(nameOf).join(' & ')}</strong>,{' '}
        {l.points.toFixed(1)}
      </span>
    </p>
  )
}

function Net({ cents }: { cents: number }) {
  const cls = cents > 0 ? 'wp-net wp-up' : cents < 0 ? 'wp-net wp-down' : 'wp-net'
  return <span className={cls}>{signed(cents)}</span>
}

function Standings({ balances }: { balances: WeeklyPotBalance[] }) {
  // standard competition ranking off net alone: 1, 2, 2, 4
  const ranks = balances.map((b, i) => (i > 0 && balances[i - 1].netCents === b.netCents ? 0 : i + 1))
  for (let i = 1; i < ranks.length; i++) if (ranks[i] === 0) ranks[i] = ranks[i - 1]
  return (
    <section className="wp-section" aria-labelledby="wp-standings-h">
      <h2 className="wp-h" id="wp-standings-h">Standings</h2>
      <div className="wp-wrap">
        <table className="wp-table">
          <thead>
            <tr>
              <th className="wp-rank" scope="col">#</th>
              <th className="wp-who" scope="col">Team</th>
              <th className="wp-num wp-hide-phone" scope="col">Weeks won</th>
              <th className="wp-num wp-hide-phone" scope="col">Collected</th>
              <th className="wp-num wp-hide-phone" scope="col">Paid</th>
              <th className="wp-num" scope="col">Net</th>
            </tr>
          </thead>
          <tbody>
            {balances.map((b, i) => (
              <tr key={b.rosterId} className={b.isMe ? 'wp-me' : undefined} data-me={b.isMe ? 'true' : undefined}>
                <td className="wp-rank">{ranks[i]}</td>
                <td className="wp-who">
                  <span className="wp-name">
                    <Avatar avatarId={b.avatarId} seed={String(b.managerId ?? `r${b.rosterId}`)} label={b.name} isMe={b.isMe} />
                    <span className="wp-name-text">
                      {b.name}
                      {b.noManager && <span className="wp-note muted"> had no manager this season</span>}
                    </span>
                  </span>
                  <span className="wp-won-inline muted">{b.weeksWon} {b.weeksWon === 1 ? 'win' : 'wins'}</span>
                </td>
                <td className="wp-num wp-hide-phone">{b.weeksWon}</td>
                <td className="wp-num wp-hide-phone">{money(b.collectedCents)}</td>
                <td className="wp-num wp-hide-phone">{money(b.paidCents)}</td>
                <td className="wp-num"><Net cents={b.netCents} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

function WeekList({ weeks, nameOf }: { weeks: WeeklyPotWeek[]; nameOf: (id: number) => string }) {
  const newestFirst = [...weeks].sort((a, b) => b.week - a.week)
  return (
    <section className="wp-section" aria-labelledby="wp-weeks-h">
      <h2 className="wp-h" id="wp-weeks-h">Week by week</h2>
      {newestFirst.length === 0 ? (
        <p className="muted">No weeks to show yet.</p>
      ) : (
        <ul className="wp-weeks">
          {newestFirst.map((w) => {
            if (w.status !== 'DECIDED') {
              return (
                <li key={w.week} className="wp-week wp-week-muted">
                  <span className="wp-week-n">Wk {w.week}</span>
                  <span className="wp-week-status">{STATUS_WORDS[w.status]}</span>
                </li>
              )
            }
            const isTie = w.winners.length > 1
            const margin =
              w.winningPoints != null && w.runnerUpPoints != null ? (w.winningPoints - w.runnerUpPoints).toFixed(2) : null
            return (
              <li key={w.week} className="wp-week">
                <span className="wp-week-n">Wk {w.week}</span>
                <span className="wp-week-win">
                  {w.winners.map(nameOf).join(' & ')}
                  {w.winners.length > 1 && <span className="wp-tie">Tie</span>}
                </span>
                <span className="wp-week-pts">{w.winningPoints?.toFixed(2)}</span>
                <span className="wp-week-margin muted">{isTie ? 'tied' : margin != null ? `+${margin} over 2nd` : ''}</span>
                <span className="wp-week-pot">{money(w.potCents)}</span>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}

function SettleUp({ d, nameOf }: { d: WeeklyPotOn; nameOf: (id: number) => string }) {
  const [tab, setTab] = useState<'shortcut' | 'pairwise'>('shortcut')
  const list = tab === 'shortcut' ? d.shortcut : d.pairwise

  const byPayer = useMemo(() => {
    const m = new Map<number, WeeklyPotDebt[]>()
    for (const x of d.pairwise) m.set(x.fromRosterId, [...(m.get(x.fromRosterId) ?? []), x])
    return [...m.entries()]
  }, [d.pairwise])

  const line = (x: WeeklyPotDebt) => `${nameOf(x.fromRosterId)} pays ${nameOf(x.toRosterId)} ${money(x.cents)}`

  return (
    <section className="wp-section" aria-labelledby="wp-settle-h">
      <h2 className="wp-h" id="wp-settle-h">
        Settle up{!d.seasonFinal && d.latestDecidedWeek > 0 ? ` (as of week ${d.latestDecidedWeek})` : ''}
      </h2>
      <p className="muted wp-settle-explain">
        Both lists clear the same balances. The shortcut never needs more payments and can send money between people who never
        beat each other. In {'“'}Every pair{'”'}, someone who{'’'}s up overall can still owe one person.
      </p>
      <div className="wp-tabs" role="tablist" aria-label="Settle-up view">
        {(['shortcut', 'pairwise'] as const).map((t) => (
          <button
            key={t}
            type="button"
            role="tab"
            aria-selected={tab === t}
            className={`wp-tab${tab === t ? ' wp-tab-on' : ''}`}
            onClick={() => setTab(t)}
          >
            {t === 'shortcut' ? 'Shortcut' : 'Every pair'}
          </button>
        ))}
      </div>
      {list.length === 0 ? (
        <p className="muted">Nobody owes anybody yet.</p>
      ) : tab === 'shortcut' ? (
        <ul className="wp-debts">
          {d.shortcut.map((x) => (
            <li key={`${x.fromRosterId}-${x.toRosterId}`} className="wp-debt">{line(x)}</li>
          ))}
        </ul>
      ) : (
        <div className="wp-groups">
          {byPayer.map(([payer, debts]) => (
            <details key={payer} className="wp-group" open={byPayer.length <= 4}>
              <summary>
                {nameOf(payer)} <span className="muted">{'·'} {debts.length} {debts.length === 1 ? 'payment' : 'payments'}</span>
              </summary>
              <ul className="wp-debts">
                {debts.map((x) => (
                  <li key={`${x.fromRosterId}-${x.toRosterId}`} className="wp-debt">{line(x)}</li>
                ))}
              </ul>
            </details>
          ))}
        </div>
      )}
    </section>
  )
}
