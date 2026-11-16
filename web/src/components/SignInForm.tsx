import { useState } from 'react'
import { getSleeperUser } from '../api'
import { setUser } from '../user'
import { trackAction } from '../analytics'

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

type Props = {
  /** Paragraphs shown under the field, before any error. Each site passes its own words (siteCopy.ts). */
  notes: string[]
}

/**
 * The Sleeper-username form, shared by the league landing (pages/SignIn.tsx) and the Time Machine front
 * door (pages/TimeMachineSignIn.tsx) so "Sleeper recognises the name" exists once. One input, three
 * distinct failure states rather than one generic error. Not auth: there is no password, and success
 * just means Sleeper recognizes the name. setUser() is enough to move on -- App renders the signed-in
 * screen the instant a user exists, no navigation call needed.
 */
export default function SignInForm({ notes }: Props) {
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
    } catch {
      // Sleeper unreachable, or this app's own backend down -- not the
      // visitor's fault, unlike a typo'd username. Offer retry rather than
      // a dead end.
      setStatus('unreachable')
    }
  }

  return (
    <>
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

      {notes.map((n) => (
        <p key={n} className="signin-note muted small">{n}</p>
      ))}

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
    </>
  )
}
