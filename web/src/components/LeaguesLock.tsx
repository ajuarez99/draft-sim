import { useState, type FormEvent } from 'react'
import { unlockLeagueSite } from '../api'
import { lockedAfterIdleMinutes } from '../leaguesLock'

type Status = 'idle' | 'checking' | 'wrong' | 'unreachable'

/**
 * The league site's lock screen (spec 073 follow-up). Shown in place of everything else on the `leagues`
 * build until the admin token has been traded for a league session (api.unlockLeagueSite), and again when
 * the session ends. After an idle lock it says so; on a first visit and after a sign-out it does not.
 * Deliberately bare: no links, no league wording, no mention of any provider, so a stranger who finds the
 * address learns nothing.
 */
export default function LeaguesLock() {
  const [token, setToken] = useState('')
  const [status, setStatus] = useState<Status>('idle')
  // Read once, when the screen appears: a lock that happens later mounts a fresh screen.
  const [idleMinutes] = useState(lockedAfterIdleMinutes)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const value = token.trim()
    if (!value || status === 'checking') return
    setStatus('checking')
    const result = await unlockLeagueSite(value)
    // 'ok': the session is stored and this screen unmounts.
    if (result === 'ok') return
    setStatus(result === 'wrong' ? 'wrong' : 'unreachable')
  }

  return (
    <div className="app">
      <div className="content lock-door">
        <main className="lock-card">
          <h1 className="lock-title">Ball Knowers</h1>
          <p className="lock-line">This site is private.</p>
          {idleMinutes !== null && (
            <p className="lock-line lock-idle">
              Locked after {idleMinutes} {idleMinutes === 1 ? 'minute' : 'minutes'} without activity.
            </p>
          )}
          <form className="signin-form lock-form" onSubmit={submit}>
            <label className="signin-label">
              Admin token
              <input
                className="signin-input"
                type="password"
                autoComplete="off"
                value={token}
                onChange={(e) => setToken(e.target.value)}
                autoFocus
              />
            </label>
            <button className="signin-continue" type="submit" disabled={status === 'checking' || !token.trim()}>
              {status === 'checking' ? 'Checking…' : 'Unlock'}
            </button>
          </form>
          <div role="alert" className="lock-alert">
            {status === 'wrong' && <p className="error">That token wasn't accepted.</p>}
            {status === 'unreachable' && <p className="error">Couldn't reach the server. Try again.</p>}
          </div>
        </main>
      </div>
    </div>
  )
}
