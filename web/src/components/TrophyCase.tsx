import { Fragment, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import Avatar from './Avatar'
import AwardWinners from './AwardWinners'
import {
  fetchSuperlatives,
  getTrophyCase,
  type SuperlativesResponse,
  type HofManagerRef,
  type TrophyCase as TrophyCaseData,
  type TrophyLastPlaceReason,
  type TrophySeason,
} from '../api'
import { useBlock } from '../useBlock'
import { useLeagueLinkState } from '../railLeague'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { finalDisagreeSentence } from '../rivalryText'
import { hueForIndex } from '../hue'
import { TITLES } from '../awardTitles'

/**
 * specs/047-hall-of-fame US1: the trophy case. One row per COMPLETED season of this league's chain, newest first:
 * the champion, the runner-up, the regular-season points leader(s) and last place. Every cell either names a
 * manager or says why it can't (the reason text is on the cell, never an empty box), because a cell that draws
 * nothing and says nothing reads as broken.
 *
 * The champion is the one History's trophy marks; the runner-up is the bracket's loser; last place is named only
 * when the server's rule could read the consolation bracket's direction (see LastPlaceRule). A table, because
 * the content is seasons x roles: it scrolls inside its own container with a sticky season column, so the page
 * never scrolls sideways at 375px.
 */

const RUNNER_UP_TEXT: Record<string, string> = {
  NO_BRACKET: 'Not available — the playoff bracket isn’t stored.',
  NO_LOSER: 'Not available — the bracket has no decided final.',
  SOURCES_DISAGREE:
    'Sleeper’s bracket and its season result name different champions; the season result is shown.',
}

/** Every last-place reason says "not reported": we would rather name nobody than the wrong person. */
export const LAST_PLACE_TEXT: Record<TrophyLastPlaceReason, string> = {
  NO_LOSERS_BRACKET: 'Not reported — no consolation bracket is stored for this season.',
  NO_WINNERS_BRACKET: 'Not reported — no playoff bracket is stored, so the non-playoff teams can’t be told.',
  INCOMPLETE: 'Not reported — the consolation bracket isn’t fully played.',
  UNCOVERED_ROSTERS: 'Not reported — the consolation bracket doesn’t cover exactly the teams that missed the playoffs.',
  MIXED_DIRECTION:
    'Not reported — Sleeper doesn’t say which end of the consolation bracket is last, and the stored scores disagree on it.',
  NO_SCORES: 'Not reported — there are no stored scores to tell which way the consolation bracket ran.',
  UNSUPPORTED_SHAPE: 'Not reported — this consolation bracket’s shape isn’t one we can read.',
}

const labelOf = (m: { name: string; teamName: string | null }) => m.teamName?.trim() || m.name

/** A named manager: avatar and team name, linked to their history page unless they have no manager id. */
function Who({ m }: { m: HofManagerRef }) {
  const linkState = useLeagueLinkState()
  const inner = (
    <>
      <Avatar avatarId={m.avatarId} seed={String(m.managerId ?? `r${m.rosterId}`)} label={labelOf(m)} className="hof-avatar" />
      <span className="hof-name">{labelOf(m)}</span>
    </>
  )
  if (m.managerId == null) return <span className="hof-who">{inner}</span>
  return (
    <Link to={`/managers/${m.managerId}/history`} state={linkState} className="hof-who hof-link">
      {inner}
    </Link>
  )
}

function Note({ children }: { children: React.ReactNode }) {
  return <span className="hof-note">{children}</span>
}

function ChampionCell({ s }: { s: TrophySeason }) {
  if (!s.champion) return <Note>No champion reported.</Note>
  return (
    <>
      <span className="hof-who-row">
        <span className="hof-cup" role="img" aria-label="Champion">🏆</span>
        <Who m={s.champion} />
      </span>
      {s.finalDisputed && <Note>{finalDisagreeSentence(s.season, labelOf(s.champion))}</Note>}
    </>
  )
}

function RunnerUpCell({ s }: { s: TrophySeason }) {
  if (s.runnerUp) return <Who m={s.runnerUp} />
  if (!s.champion) return <span className="hof-dash" aria-label="none">—</span>
  return <Note>{RUNNER_UP_TEXT[s.runnerUpReason ?? 'NO_BRACKET'] ?? RUNNER_UP_TEXT.NO_BRACKET}</Note>
}

function PointsCell({ s }: { s: TrophySeason }) {
  if (s.pointsLeaders.length === 0) return <Note>Not available — no standings are stored.</Note>
  return (
    <>
      {s.pointsLeaders.map((p) => (
        <span key={p.rosterId} className="hof-who-row">
          <Who m={p} />
          <span className="hof-pts mono">{p.pointsFor.toFixed(2)}</span>
        </span>
      ))}
      {s.pointsLeaders.length > 1 && <Note>Tied for the most points.</Note>}
    </>
  )
}

function LastPlaceCell({ s }: { s: TrophySeason }) {
  if (s.lastPlace) return <Who m={s.lastPlace} />
  return <Note>{LAST_PLACE_TEXT[s.lastPlaceReason ?? 'NO_LOSERS_BRACKET'] ?? 'Not reported.'}</Note>
}

/**
 * US4: one past season's award winners, from the SAME endpoint and rendering the Superlatives page uses
 * (`fetchSuperlatives` + `AwardWinners`), so the winners can't differ from that page's (FR-012).
 *
 * Guard (plan A9): the endpoint resolves a league id to the newest PLAYED season, which can be an earlier one than
 * the row's. When it answers for another season (`requestedSeason` set, or a different `leagueSleeperId`) the row
 * says awards aren't available and renders none of the returned ones, rather than showing another year's winners
 * under this year's name.
 */
function SeasonAwards({ s, load }: { s: TrophySeason; load: Promise<SuperlativesResponse> }) {
  const [state, setState] = useState<
    { status: 'loading' } | { status: 'error' } | { status: 'ok'; data: SuperlativesResponse }
  >({ status: 'loading' })
  useEffect(() => {
    let cancelled = false
    load
      .then((data) => { if (!cancelled) setState({ status: 'ok', data }) })
      .catch(() => { if (!cancelled) setState({ status: 'error' }) })
    return () => { cancelled = true }
  }, [load])
  if (state.status === 'loading') return <p className="muted small" role="status">Loading awards…</p>
  if (state.status === 'error') return <p className="muted small">Couldn’t load this season’s awards.</p>
  const d = state.data
  if (d.requestedSeason != null || d.leagueSleeperId !== s.sleeperLeagueId) {
    return <p className="muted small">Awards aren’t available for this season.</p>
  }
  if (!d.available) {
    return <p className="muted small">{d.reason ?? 'No week of this season has been scored yet.'}</p>
  }
  return (
    <>
      <div className="hof-awards-list">
        {/* Spec 051 F8: an unavailable Trending Tony (no history for a past season) is hidden here; the Awards page still explains it. */}
        {d.superlatives.filter((a) => !(a.kind === 'TRENDING_TONY' && !a.available)).map((a, i, shown) => (
          <article key={a.kind} className="hof-award" style={{ ['--sl-hue' as string]: hueForIndex(i, shown.length) }}>
            <h5 className="hof-award-title">{TITLES[a.kind]?.title ?? a.kind}</h5>
            <AwardWinners s={a} hue={hueForIndex(i, shown.length)} sport={d.sport} leagueId={d.leagueSleeperId} throughWeek={d.throughWeek} />
          </article>
        ))}
      </div>
      <p className="small">
        <Link to={`/leagues/${s.sleeperLeagueId}/superlatives`}>See {s.season}’s awards page</Link>
      </p>
    </>
  )
}

/** The table itself, for a payload already in hand. Exported for the tests. */
export function TrophyCaseTable({ data }: { data: TrophyCaseData }) {
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set())
  // One request per row, however often the row is toggled (cached as a promise). Two things drop the cache
  // (code review N3): a failed fetch (so reopening the row retries) and a new payload (a league data refresh
  // hands this table a new `data`, and awards fetched before it are stale).
  const loads = useRef<{ data: TrophyCaseData; byId: Map<string, Promise<SuperlativesResponse>> }>({ data, byId: new Map() })
  if (loads.current.data !== data) loads.current = { data, byId: new Map() }
  const loadFor = (id: string) => {
    const cache = loads.current
    let p = cache.byId.get(id)
    if (!p) {
      const fresh = fetchSuperlatives(id)
      p = fresh
      cache.byId.set(id, fresh)
      fresh.catch(() => {
        if (cache.byId.get(id) === fresh) cache.byId.delete(id)
      })
    }
    return p
  }
  if (data.status === 'NO_COMPLETED_SEASON' || data.seasons.length === 0) {
    return <p className="muted small">No season has finished yet.</p>
  }
  const toggle = (id: string) =>
    setOpen((cur) => {
      const next = new Set(cur)
      if (!next.delete(id)) next.add(id)
      return next
    })
  return (
    <div className="hof-wrap">
      <table className="hof-table">
        <thead>
          <tr>
            <th scope="col" className="hof-first">Season</th>
            <th scope="col">Champion</th>
            <th scope="col">Runner-up</th>
            <th scope="col">Points leader</th>
            <th scope="col">Last place</th>
          </tr>
        </thead>
        <tbody>
          {data.seasons.map((s) => (
            <Fragment key={s.sleeperLeagueId}>
              <tr>
                <th scope="row" className="hof-first hof-season">
                  <span className="hof-year">{s.season}</span>
                  <button
                    type="button"
                    className="hof-awards-btn"
                    aria-expanded={open.has(s.sleeperLeagueId)}
                    aria-controls={`hof-awards-${s.sleeperLeagueId}`}
                    onClick={() => toggle(s.sleeperLeagueId)}>
                    Awards
                  </button>
                </th>
                <td className="hof-c hof-champ"><ChampionCell s={s} /></td>
                <td className="hof-c hof-ru"><RunnerUpCell s={s} /></td>
                <td className="hof-c hof-pl"><PointsCell s={s} /></td>
                <td className="hof-c hof-last"><LastPlaceCell s={s} /></td>
              </tr>
              {open.has(s.sleeperLeagueId) && (
                <tr className="hof-awards-row">
                  <td colSpan={5} id={`hof-awards-${s.sleeperLeagueId}`}>
                    <SeasonAwards s={s} load={loadFor(s.sleeperLeagueId)} />
                  </td>
                </tr>
              )}
            </Fragment>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * The History page's "Hall of fame" section. Hidden when the route answers 404 (an older backend, or a league out
 * of scope): a missing section is not an error. Any other failure says so in one line.
 */
export default function TrophyCase({ sleeperLeagueId }: { sleeperLeagueId: string }) {
  const version = useLeagueDataVersion(sleeperLeagueId)
  const block = useBlock(sleeperLeagueId ? () => getTrophyCase(sleeperLeagueId) : null, [sleeperLeagueId, version])
  if (block.status === 'idle' || block.status === 'loading') return null
  if (block.status === 'error') {
    return block.notFound ? null : (
      <section className="section hof-section" aria-labelledby="hof-h">
        <h3 id="hof-h" className="section-title">Hall of fame</h3>
        <p className="muted small">Couldn’t load the hall of fame.</p>
      </section>
    )
  }
  if (block.data == null) return null
  return (
    <section className="section hof-section" aria-labelledby="hof-h">
      <h3 id="hof-h" className="section-title">Hall of fame</h3>
      <p className="muted small">
        Who won each finished season. The champion is the one marked in the standings; the runner-up is the loser of
        the stored playoff final; the points leader is the most regular-season points. Last place is named only when
        the consolation bracket’s direction can be read from the stored scores.
      </p>
      <TrophyCaseTable data={block.data} />
    </section>
  )
}
