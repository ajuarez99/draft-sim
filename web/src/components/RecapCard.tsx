import { useEffect, useState } from 'react'
import { fetchRecap, type RecapFailureReason, type RecapResponse } from '../api'

/*
 * specs/020-ai-weekly-recap: the AI-written recap above a week's matchups.
 *
 * The server never blocks on the model. A GET that needs a recap answers GENERATING and the card
 * re-asks every 3 s (up to 40 times) until it is READY. For the same reason the first request waits
 * until the week has been on screen for 1 s, so stepping through weeks does not spend the daily cap
 * on weeks nobody read.
 *
 * FEATURE_OFF and NOT_ENTITLED render nothing at all, so a league without the feature sees a page
 * whose DOM is exactly what it was before this card existed.
 */

const FIRST_FETCH_DELAY_MS = 1000
const POLL_MS = 3000
const MAX_POLLS = 40

const REASON_COPY: Record<RecapFailureReason, string> = {
  UNGROUNDED: "the draft didn't check out against the numbers",
  REFUSED: 'the model declined to write it',
  TRUNCATED: 'the draft ran out of room',
  MALFORMED: 'the draft came back in the wrong shape',
  API_ERROR: 'the writing service had a problem; it will try again',
  RATE_LIMITED_UPSTREAM: 'the writing service is busy; it will try again',
}

export default function RecapCard({ sleeperLeagueId, week }: { sleeperLeagueId: string; week: number }) {
  const [res, setRes] = useState<RecapResponse | null>(null)
  const [gaveUp, setGaveUp] = useState(false)

  useEffect(() => {
    // A new week starts clean: the previous week's recap must never sit under the new week's header.
    setRes(null)
    setGaveUp(false)
    const ctl = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    let polls = 0

    const load = async () => {
      try {
        const r = await fetchRecap(sleeperLeagueId, week, ctl.signal)
        if (ctl.signal.aborted) return
        setRes(r)
        if (r.state !== 'GENERATING') return
        if (polls >= MAX_POLLS) {
          setGaveUp(true)
          return
        }
        polls += 1
        timer = setTimeout(load, POLL_MS)
      } catch {
        // The recap is a bonus on the page; a failed request leaves it out rather than showing an error.
        if (!ctl.signal.aborted) setRes(null)
      }
    }

    timer = setTimeout(load, FIRST_FETCH_DELAY_MS)
    return () => {
      ctl.abort()
      if (timer) clearTimeout(timer)
    }
  }, [sleeperLeagueId, week])

  if (!res || res.state === 'FEATURE_OFF' || res.state === 'NOT_ENTITLED') return null

  const sections = res.sections != null && res.sections.length > 0 ? res.sections : null
  const hasBody = res.headline != null || sections != null
  // A READY or stale response with nothing to show is a bug upstream; show nothing rather than an empty card.
  if (!hasBody && (res.state === 'READY' || res.stale)) return null
  const reason = res.failureReason ? REASON_COPY[res.failureReason] : null

  let notice: string | null = null
  if (res.state === 'WEEK_NOT_FINAL') notice = 'The recap is written once this week is final.'
  else if (res.state === 'GENERATING') {
    notice = gaveUp ? 'Still writing, check back shortly' : hasBody ? 'Updating this recap…' : "Writing this week's recap…"
  } else if (res.state === 'RATE_LIMITED') {
    notice = hasBody ? 'Recap paused for today; showing the last version.' : 'Recap paused for today'
  } else if (res.state === 'FAILED') {
    if (hasBody) notice = reason ? `The latest rewrite failed (${reason}).` : 'The latest rewrite failed.'
    else notice = reason ? `Recap unavailable this week (${reason})` : 'Recap unavailable this week'
  }
  const writtenOn = res.generatedAt
    ? new Date(res.generatedAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
    : null

  return (
    <section className="section recap-card" aria-label="Weekly recap">
      <header className="recap-head">
        <h3 className="section-title">Week {res.week} recap</h3>
        {res.season != null && <span className="recap-season muted small">Season {res.season}</span>}
      </header>

      {notice && (
        <p className={`recap-notice small${res.state === 'FAILED' ? ' recap-notice-fail' : ''}`} role="status">
          {notice}
        </p>
      )}

      {hasBody && (
        <article className={`recap-body${res.stale ? ' recap-stale' : ''}`}>
          {res.headline != null && <h4 className="recap-headline">{res.headline}</h4>}
          {sections && (
            <div className="recap-sections">
              {sections.map((s, i) => (
                <div key={i} className="recap-section">
                  <h5>{s.title}</h5>
                  <p>{s.body}</p>
                </div>
              ))}
            </div>
          )}
          {res.revision != null && res.revision > 1 && res.revisionReason === 'NUMBERS_CHANGED' && (
            <p className="recap-revised muted small">Revised after a scoring correction.</p>
          )}
          {res.revision != null && res.revision > 1 && res.revisionReason === 'NAMES_CHANGED' && (
            <p className="recap-revised muted small">Rewritten after a team name change.</p>
          )}
          {res.revision != null && res.revision > 1 && res.revisionReason === 'REPORT_CHANGED' && (
            <p className="recap-revised muted small">Rewritten after this week's report changed.</p>
          )}
          <p className="recap-foot muted small">
            <span>Written by AI ({res.model})</span>
            <span aria-hidden="true"> · </span>
            {res.stale ? (
              <span>
                Older version · numbers were checked against the report as it was on {writtenOn ?? 'an earlier date'}
              </span>
            ) : (
              <span>numbers checked against this report</span>
            )}
          </p>
        </article>
      )}
    </section>
  )
}
