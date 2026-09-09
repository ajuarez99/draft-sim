import { useState } from 'react'
import { getSleeperUser } from '../api'
import { setUser } from '../user'

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
      // No further action -- App.tsx renders the picker as soon as a user exists.
    } catch {
      // Sleeper unreachable, or this app's own backend down -- not the
      // visitor's fault, unlike a typo'd username. Offer retry rather than
      // a dead end.
      setStatus('unreachable')
    }
  }

  return (
    <div className="content signin-content">
      <section className="panel home-hero signin-hero">
        <div className="home-hero-body">
          <p className="home-hero-kicker cond">Welcome to Ball Knowers</p>
          <h2 className="home-hero-title">Who are you?</h2>
          <p className="home-hero-sub">
            Enter your Sleeper username (or paste your Sleeper profile link) to see your own
            leagues and get your seat highlighted at the table. No password -- this app never
            asks Sleeper for anything private.
          </p>

          <div className="controls signin-controls">
            <label>
              Your Sleeper username
              <input
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && submit()}
                placeholder="e.g. popsharky"
                autoFocus
                size={24}
              />
            </label>
            <button onClick={submit} disabled={status === 'resolving' || !input.trim()}>
              {status === 'resolving' ? 'Checking…' : 'Continue'}
            </button>
          </div>

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
        </div>
      </section>
    </div>
  )
}
