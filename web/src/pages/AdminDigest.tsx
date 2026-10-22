import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import PageHeader from '../components/PageHeader'
import {
  getAdminDigest,
  refreshDigestStats,
  type AdminDigest as Digest,
  type DigestKeyEntry,
  type LockInCard,
  type SportDigest,
} from '../api'
import { AdminTokenRequiredError } from '../apiError'
import { signedPct } from '../components/LockInPanel'
import {
  keyPlayersCard,
  positionLeadersCard,
  spotlightCard,
  topPerformersCard,
  trendingCard,
  TWEET_LIMIT,
  weightedLength,
  type PostCard,
} from '../digest/tweetText'

/**
 * Spec 043: the admin-only morning digest. One card per post idea, each an editable textarea with a
 * live length counter and a Copy button. No nav link points here and the route is unlisted on purpose;
 * the real gate is the backend's admin token (this page only keeps it for the tab session).
 *
 * Edits live in component state only (FR-013). A card is keyed by its generated text, so a reload that
 * produces the same text keeps the edits and one that changes the text drops them (Reset also restores it).
 */

const TOKEN_KEY = 'bk.adminToken'

// sessionStorage can throw (private windows, blocked site data); the page must work without it.
function readToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

function writeToken(token: string | null) {
  try {
    if (token) sessionStorage.setItem(TOKEN_KEY, token)
    else sessionStorage.removeItem(TOKEN_KEY)
  } catch {
    /* no storage: the token just lasts as long as this component does */
  }
}

async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    /* fall through to the textarea route */
  }
  const ta = document.createElement('textarea')
  ta.value = text
  ta.setAttribute('readonly', '')
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  document.body.removeChild(ta)
  return ok
}

const friendlyDate = (iso: string): string =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })

const friendlyTime = (iso: string | null): string => (iso ? new Date(iso).toLocaleString() : 'never')

const SPORT_NAME: Record<SportDigest['sport'], string> = { nba: 'Basketball', nfl: 'Football' }

function PostCardBox({ title, card, children }: { title: string; card: PostCard; children?: ReactNode }) {
  const [text, setText] = useState(card.text)
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'failed'>('idle')
  const len = weightedLength(text)
  const over = len > TWEET_LIMIT

  async function copy() {
    const ok = await copyText(text)
    setCopyState(ok ? 'copied' : 'failed')
    // Only the "Copied" tick fades; the blocked hint stays until the next action (L7).
    if (ok) window.setTimeout(() => setCopyState('idle'), 2000)
  }

  return (
    <div className="dg-card">
      <h3>{title}</h3>
      <textarea
        aria-label={`${title} text`}
        value={text}
        onChange={(e) => {
          setText(e.target.value)
          setCopyState('idle')
        }}
        rows={Math.max(4, text.split('\n').length + 1)}
      />
      <div className="dg-card-foot">
        <span className={`dg-count${over ? ' over' : ''}`} aria-live="polite">
          {`${len} / ${TWEET_LIMIT}`}
        </span>
        {card.shown < card.total && <span className="muted small">{`top ${card.shown} of ${card.total} shown`}</span>}
        <span className="dg-card-actions">
          <button type="button" className="action-button" onClick={() => setText(card.text)} disabled={text === card.text}>
            Reset
          </button>
          <button type="button" className="action-button" onClick={copy}>
            {copyState === 'copied' ? 'Copied' : 'Copy'}
          </button>
        </span>
      </div>
      {copyState === 'failed' && <p className="muted small">Copy was blocked; select the text and copy it by hand.</p>}
      {children}
    </div>
  )
}

function statusMessage(sd: SportDigest, target: string): string | null {
  const day = friendlyDate(target)
  switch (sd.status) {
    case 'READY':
      return null
    case 'NOT_FETCHED':
      return `Games were scheduled on ${day}, but their stats haven't been fetched yet. Use “Fetch latest stats”.`
    case 'NIGHT_INCOMPLETE':
      return `Stats for ${day} aren't final yet (last fetched ${friendlyTime(sd.statsFetchedAt)}). Fetch again once the night is over.`
    case 'NO_GAMES_LAST_NIGHT': {
      const bits = ['No games last night.']
      if (sd.lastSlateDate) bits.push(`Last games: ${friendlyDate(sd.lastSlateDate)}.`)
      if (sd.nextGameDate) bits.push(`Next games: ${friendlyDate(sd.nextGameDate)}.`)
      return bits.join(' ')
    }
    case 'NO_GAMES_ON_DATE':
      return `No games on ${day}.${sd.nextGameDate ? ` Next games: ${friendlyDate(sd.nextGameDate)}.` : ''}`
    case 'NO_SEASON':
      return 'No current season is known for this sport.'
    case 'DATE_IN_FUTURE':
      return `${day} hasn't happened yet, so there is nothing to fetch.`
  }
}

const BASIS_TEXT: Record<DigestKeyEntry['expectationBasis'], string> = {
  THIS_SEASON: 'vs his average this season',
  LAST_SEASON: 'vs his average last season',
  NONE: 'no baseline',
}

/** A card that came back null with data behind it: say so instead of offering a header-only post (L5). */
function NothingToPost({ what }: { what: string }) {
  return <p className="muted small">{`Nothing to post for “${what}”: even its first line is over ${TWEET_LIMIT}.`}</p>
}

function KeyNotes({ keys }: { keys: DigestKeyEntry[] }) {
  return (
    <ul className="dg-notes muted small">
      {keys.map((k) => (
        <li key={k.entry.playerId}>
          {k.played && k.entry.points != null
            ? `${k.entry.name}: ${k.entry.points.toFixed(1)}${k.expectation != null ? ` ${BASIS_TEXT[k.expectationBasis]} (${k.expectation.toFixed(1)})` : ' (no baseline)'}`
            : `${k.entry.name}: did not play`}
        </li>
      ))}
    </ul>
  )
}

const lockInReason = (c: LockInCard, when: string): string => {
  switch (c.status) {
    case 'NONE_TONIGHT':
      return `No starter in this league cleared his lock threshold in ${when === 'last night' ? "last night's games" : `the games ${when}`}, with games still left this week.`
    case 'NO_HISTORY':
      return 'This league has no completed lock-in season to learn thresholds from, so no calls were made.'
    case 'ROSTERS_FAILED':
      return "The current lineups couldn't be read from Sleeper, so no calls were made. Try again in a minute."
    default:
      return 'No lock-in calls for this league.'
  }
}

/** One lock-in league: the server-built tweet when there are calls, otherwise the reason there are none (US5). */
function LockInCardBox({ c, when }: { c: LockInCard; when: string }) {
  const title = `Lock-in calls · ${c.leagueName}`
  if (c.status !== 'OK' || c.tweet === '') {
    return (
      <div className="dg-card">
        <h3>{title}</h3>
        <p className="muted small">{lockInReason(c, when)}</p>
      </div>
    )
  }
  const card: PostCard = {
    text: c.tweet,
    weightedLength: weightedLength(c.tweet),
    shown: c.tweet.split('\n').length - 1,
    total: c.entries.length,
  }
  return (
    <PostCardBox key={`li-${c.sleeperLeagueId}-${c.tweet}`} title={title} card={card}>
      <ul className="dg-notes muted small">
        {c.entries.map((e) => (
          <li key={`${e.rosterId}-${e.playerId}`}>
            {`${e.player ?? e.playerId} (${e.manager ?? 'unowned'}): ${e.latestGame.points.toFixed(1)} pts, ${e.margin != null ? `${signedPct(e.margin)} vs ${e.baselineKind === 'LAST_SEASON' ? "last season's avg" : 'season avg'}, ` : ''}${e.gamesLeft ?? 0} left, bar ${signedPct(e.threshold ?? 0)}`}
          </li>
        ))}
      </ul>
    </PostCardBox>
  )
}

function SportSection({ sd, yesterday, target }: { sd: SportDigest; yesterday: string; target: string }) {
  const ready = sd.status === 'READY'
  const when = sd.date && sd.date !== yesterday ? `on ${friendlyDate(sd.date)}` : 'last night'
  const message = statusMessage(sd, target)

  const meta = [
    ready && sd.date ? friendlyDate(sd.date) : null,
    sd.week != null ? `Week ${sd.week}` : null,
    sd.gamesCount != null ? `${sd.gamesCount} games` : null,
    sd.pointsBasis,
    `stats fetched ${friendlyTime(sd.statsFetchedAt)}`,
  ].filter(Boolean)

  const t = sd.trending
  const trending = ready || t?.status === 'OK' ? trendingCard(sd, when) : null
  const top = ready ? topPerformersCard(sd, when) : null
  const keys = ready ? keyPlayersCard(sd, when) : null
  const spot = ready ? spotlightCard(sd, when) : null
  const leaders = ready ? positionLeadersCard(sd) : null

  return (
    <section className="panel dg-sport" aria-label={SPORT_NAME[sd.sport]}>
      <div className="panel-head">
        <h2>{SPORT_NAME[sd.sport]}</h2>
        <span className="muted small">{meta.join(' · ')}</span>
      </div>
      {message && <p className="dg-status">{message}</p>}

      <div className="dg-cards">
        {t?.status === 'NEVER_FETCHED' && <p className="muted">Trending adds haven't been fetched yet.</p>}
        {t?.status === 'TRENDING_NOT_HISTORICAL' && (
          <p className="muted">Trending adds only exist for last night, so a chosen date has no trending card.</p>
        )}
        {t?.status === 'OK' && trending && (
          <PostCardBox key={`t-${trending.text}`} title="Most added, and how they did" card={trending}>
            <p className="muted small">
              {`Sleeper's most-added over the last ${t.lookbackHours} hours, fetched ${friendlyTime(t.fetchedAt)}${t.stale ? ' (out of date)' : ''}.`}
              {trending.noGame > 0 && ` ${trending.noGame} of the top ${t.entries.length} had no game and are left out.`}
            </p>
          </PostCardBox>
        )}
        {t?.status === 'OK' && !trending && t.entries.length > 0 && (
          <div className="dg-card">
            <h3>Most added (no results yet)</h3>
            <p className="muted small">
              {!ready
                ? "The night isn't ready, so only the adds are known:"
                : t.entries.some((i) => i.outcome === 'PLAYED' || i.outcome === 'DID_NOT_PLAY')
                  ? `Nothing to post: even the first line is over ${TWEET_LIMIT}.`
                  : 'None of them had a game on this night.'}
            </p>
            {!ready && (
              <ol className="dg-notes muted small">
                {t.entries.map((i) => (
                  <li key={i.entry.playerId}>{`${i.entry.name} (+${i.addCount.toLocaleString('en-US')})`}</li>
                ))}
              </ol>
            )}
          </div>
        )}

        {ready && !top && sd.topPerformers.length > 0 && <NothingToPost what="Top fantasy scores" />}
        {top && <PostCardBox key={`p-${top.text}`} title="Top fantasy scores" card={top} />}
        {ready && !keys && sd.keyPlayers.length > 0 && <NothingToPost what="How the stars did" />}
        {keys && (
          <PostCardBox key={`k-${keys.text}`} title="How the stars did" card={keys}>
            <KeyNotes keys={sd.keyPlayers} />
          </PostCardBox>
        )}
        {spot && <PostCardBox key={`s-${spot.text}`} title="Best single line" card={spot} />}
        {ready && !leaders && (sd.positionLeaders?.length ?? 0) > 0 && <NothingToPost what="Position leaders" />}
        {leaders && <PostCardBox key={`l-${leaders.text}`} title="Position leaders" card={leaders} />}
        {sd.lockInCalls.map((c) => (
          <LockInCardBox key={c.sleeperLeagueId} c={c} when={when} />
        ))}
      </div>
    </section>
  )
}

export default function AdminDigest() {
  const [token, setToken] = useState<string | null>(() => readToken())
  const [draftToken, setDraftToken] = useState('')
  const [refused, setRefused] = useState(false)
  const [date, setDate] = useState('')
  // The digest on screen and the date it was asked for, set together so the status text can never
  // name a different date than the data it sits over (S2).
  const [shown, setShown] = useState<{ digest: Digest; date: string } | null>(null)
  const latestLoad = useRef(0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fetching, setFetching] = useState(false)
  const [fetchNote, setFetchNote] = useState<string | null>(null)

  const handleFailure = useCallback((e: unknown) => {
    if (e instanceof AdminTokenRequiredError) {
      writeToken(null)
      setToken(null)
      setShown(null)
      setRefused(true)
      return
    }
    setError(e instanceof Error ? e.message : 'Something went wrong.')
  }, [])

  const load = useCallback(
    async (t: string, d: string) => {
      const id = ++latestLoad.current
      setLoading(true)
      setError(null)
      setShown(null) // never leave the previous date's cards (and Copy) up while the new one loads
      try {
        const result = await getAdminDigest(t, d || undefined)
        if (id === latestLoad.current) setShown({ digest: result, date: d })
      } catch (e) {
        if (id === latestLoad.current) handleFailure(e) // a superseded request's failure is moot
      } finally {
        if (id === latestLoad.current) setLoading(false)
      }
    },
    [handleFailure],
  )

  useEffect(() => {
    if (token) void load(token, date)
  }, [token, date, load])

  function submitToken(e: FormEvent) {
    e.preventDefault()
    const t = draftToken.trim()
    if (!t) return
    writeToken(t)
    setRefused(false)
    setDraftToken('')
    setToken(t)
  }

  async function fetchStats() {
    if (!token) return
    setFetching(true)
    setFetchNote(null)
    try {
      const r = await refreshDigestStats(token)
      setFetchNote(
        r.results
          .map((x) => {
            const name = x.sport.toUpperCase()
            if (x.error) return `${name}: ${x.error}`
            // A failed week must not read as "nothing new" (S4).
            const bits = [`${x.weeksFetched} week${x.weeksFetched === 1 ? '' : 's'} fetched, ${x.gamesStored} games`]
            if (x.weeksFailed > 0) bits.push(`${x.weeksFailed} week${x.weeksFailed === 1 ? '' : 's'} FAILED`)
            if (x.scheduleStoreFailed) bits.push('schedule not saved')
            return `${name}: ${bits.join(', ')}`
          })
          .join(' · '),
      )
      await load(token, date)
    } catch (e) {
      handleFailure(e)
    } finally {
      setFetching(false)
    }
  }

  const header = <PageHeader eyebrow="Admin" title="Morning digest" sub="Tweet-sized recaps of last night’s games. Copy, tweak, post." />

  if (!token) {
    return (
      <div className="content">
        {header}
        <section className="panel dg-token">
          <form onSubmit={submitToken}>
            <label htmlFor="dg-token-input">Admin token</label>
            <input
              id="dg-token-input"
              type="password"
              autoComplete="off"
              value={draftToken}
              onChange={(e) => setDraftToken(e.target.value)}
            />
            <button type="submit" className="action-button">
              Continue
            </button>
          </form>
          {refused && <p className="dg-status">That token was refused. Enter it again.</p>}
        </section>
      </div>
    )
  }

  return (
    <div className="content">
      {header}
      <section className="panel dg-toolbar" aria-label="Controls">
        <label>
          Date
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </label>
        {date && (
          <button type="button" className="action-button" onClick={() => setDate('')}>
            Last night
          </button>
        )}
        <button type="button" className="action-button" onClick={fetchStats} disabled={fetching}>
          {fetching ? 'Fetching…' : 'Fetch latest stats'}
        </button>
        {fetchNote && <span className="muted small">{fetchNote}</span>}
      </section>

      {error && <div className="error">{error}</div>}
      {loading && !shown && <p className="muted">Loading…</p>}

      {shown &&
        shown.digest.sports.map((sd) => (
          <SportSection
            key={sd.sport}
            sd={sd}
            yesterday={shown.digest.yesterday}
            target={shown.date || shown.digest.yesterday}
          />
        ))}
    </div>
  )
}
