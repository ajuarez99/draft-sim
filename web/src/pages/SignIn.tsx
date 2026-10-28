import { useState } from 'react'
import { getSleeperUser } from '../api'
import { setUser } from '../user'
import { trackAction } from '../analytics'
import { analyticsLine, featureSports, footerLine, groups, headingOf, headline, platformsNote, sports, subheadline } from '../landingCopy'

// Accepts a pasted profile URL the same way DraftPicker.leagueIdFrom accepts
// a league URL -- people paste links, not bare ids/usernames. Sleeper profile
// URLs are `sleeper.com/user/<username-or-id>` with no query string that
// would survive after the last segment, so "last non-empty path segment" is
// enough; a bare username/id with no slash passes through untouched.
function usernameFrom(input: string): string {
  const trimmed = input.trim()
  if (!trimmed.includes('/')) return trimmed
  const segments = trimmed.split(/[?#]/)[0].split('/').filter(Boolean)
  return segments[segments.length - 1] || trimmed
}

type Status = 'idle' | 'resolving' | 'not-found' | 'unreachable'

/**
 * The hard gate at `/` (claude/user-identity-and-onboarding.md §5b) -- one
 * input, three distinct failure states rather than one generic error. Not
 * auth: there is no password, and "success" just means Sleeper recognizes
 * the name. On success, setUser() is enough to move on -- App renders
 * DraftPicker the instant a user exists, no navigation call needed.
 */
export default function SignIn() {
  const [input, setInput] = useState('')
  const [status, setStatus] = useState<Status>('idle')
  const [lastTried, setLastTried] = useState('')

  async function submit() {
    const usernameOrId = usernameFrom(input)
    if (!usernameOrId) return
    setLastTried(usernameOrId)
    setStatus('resolving')
    try {
      const found = await getSleeperUser(usernameOrId)
      if (found == null) {
        setStatus('not-found')
        return
      }
      setUser(found)
      // Spec 052: counted once, only after Sleeper recognised the name.
      trackAction('sign-in')
      // No further action -- App.tsx renders the picker as soon as a user exists.
    } catch {
      // Sleeper unreachable, or this app's own backend down -- not the
      // visitor's fault, unlike a typo'd username. Offer retry rather than
      // a dead end.
      setStatus('unreachable')
    }
  }

  return (
    // Spec 050: the signed-out root is the landing page -- a hero that holds the
    // unchanged sign-in form (spec 013 US9: one field, one --volt action), then the
    // rail's own feature groups. Its words come from landingCopy.ts, which
    // landingHtml.ts also writes into the delivered HTML, so keep the element tree and
    // class names here in step with staticBody() there (landingHtml.test.ts compares
    // the text). `.landing` is its own scroll container: `.app` is a fixed 100vh.
    <div className="content landing">
      <header className="landing-hero">
        <p className="landing-kicker cond">Ball Knowers</p>
        <h1 className="landing-headline">{headline}</h1>
        <p className="landing-sub">{subheadline}</p>
        <p className="landing-sports">
          {sports.map((s) => (
            <span key={s} className="landing-sport">{s}</span>
          ))}
        </p>

        <form
          className="signin-form"
          onSubmit={(e) => {
            e.preventDefault()
            submit()
          }}
        >
          <label className="signin-label">
            Your Sleeper username
            <input
              className="signin-input"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="Username or profile link"
              autoFocus
            />
          </label>
          <button className="signin-continue" type="submit" disabled={status === 'resolving' || !input.trim()}>
            {status === 'resolving' ? 'Checking…' : 'Continue'}
          </button>
        </form>

        <p className="signin-note muted small">
          Enter your Sleeper username (or paste your Sleeper profile link) to see your own leagues and
          get your seat highlighted at the table. No password -- this app never asks Sleeper for
          anything private.
        </p>
        <p className="signin-note muted small">{analyticsLine}</p>

        {status === 'not-found' && (
          <div className="error">
            No Sleeper user called “{lastTried}”. Check the spelling and try again.
          </div>
        )}
        {status === 'unreachable' && (
          <div className="error">
            Couldn’t reach Sleeper just now -- that’s not on you.{' '}
            <button className="link-button" onClick={submit}>Try again</button>
          </div>
        )}

        <p className="landing-platforms muted small">{platformsNote}</p>
      </header>

      {groups.map((g) => (
        <section key={g.key} className="landing-group" data-group={g.key}>
          <h2 className="landing-group-title cond">{headingOf(g.key)}</h2>
          <ul className="landing-tiles">
            {g.features.map((f) => {
              const only = featureSports(f)
              return (
                <li key={f.name} className="landing-tile">
                  <div className="landing-tile-head">
                    <h3 className="landing-tile-name">{f.name}</h3>
                    {only.length === 1 && <span className="landing-tag">{only[0].toUpperCase()}</span>}
                  </div>
                  <p className="landing-tile-line">{f.line}</p>
                </li>
              )
            })}
          </ul>
        </section>
      ))}

      <p className="landing-footer muted small">{footerLine}</p>
    </div>
  )
}
