import { useEffect, useRef, useSyncExternalStore } from 'react'
import { lastClaimNote, onClaimNote } from '../guest'
import * as copy from '../redraftCopy'
import { useUser } from '../user'

/**
 * Spec 074 A1 / C5: the one line that says what signing in did to a browser's games ("2 games from this browser are
 * now in your history."). Always mounted, empty until there is something to say, and a polite live region, so the
 * words are announced when they appear rather than only when a node is inserted. It is also where focus goes when a
 * guest signs in on this page: the sign-in form they were typing in unmounts, and focus would otherwise fall to the
 * top of the document. `data-dynamic` because the static page carries an empty copy of it.
 */
export default function ClaimLine() {
  const claim = useSyncExternalStore(onClaimNote, lastClaimNote, lastClaimNote)
  const user = useUser()
  const ref = useRef<HTMLDivElement>(null)
  const wasGuest = useRef(user == null)
  useEffect(() => {
    if (user && wasGuest.current) ref.current?.focus({ preventScroll: true })
    wasGuest.current = user == null
  }, [user])
  return (
    <div className="tm-claim" role="status" tabIndex={-1} ref={ref} data-dynamic="claim">
      {claim && claim.moved > 0 && (
        <p className="muted small">
          {copy.claimLine(claim.moved)}
          {claim.leftBehind > 0 ? ` ${copy.claimLeftBehind(claim.leftBehind)}` : ''}
        </p>
      )}
      {claim && claim.moved === 0 && claim.leftBehind > 0 && <p className="muted small">{copy.claimLeftBehind(claim.leftBehind)}</p>}
    </div>
  )
}
