import { useEffect, useRef, useState } from 'react'
import type { LiveState } from './api'

// Past this, the freshness pill flips from "live" to "stale". Heartbeats come
// every 15s, so 25 is one missed heartbeat plus slack -- long enough that a
// slow tick doesn't cry wolf, short enough that a dead backend is obvious
// inside half a minute.
export const STALE_AFTER_SECONDS = 25

// EventSource retries a *dropped* connection itself, but it does NOT retry a
// connection the browser considers failed outright -- an HTTP 404, or a
// response whose Content-Type isn't text/event-stream, closes the source
// permanently (readyState CLOSED) after one error event. That is exactly the
// case on draft night if the backend is up but running bytecode without the
// endpoint: without this manual retry the page would stay dead even after the
// backend came back. 5s is slower than EventSource's own ~3s so a genuinely
// missing endpoint isn't hammered.
const RETRY_MS = 5000

export type LiveDraft = {
  live: LiveState | null
  connected: boolean
  /** null until the first byte ever arrives. Frozen once the draft completes. */
  secondsSinceContact: number | null
  error: string | null
}

/**
 * Subscribes to GET /api/drafts/{id}/live-stream and reports what the backend
 * knows about a draft in progress, plus how long ago it last said anything.
 *
 * The freshness reading is the point: everything else on the live screen is a
 * number the server sent, and a number the server sent ten minutes ago looks
 * exactly like one it sent a second ago. `secondsSinceContact` is the only
 * thing on the page that can tell you the backend died.
 */
export function useLiveDraft(draftId: string): LiveDraft {
  const [live, setLive] = useState<LiveState | null>(null)
  const [connected, setConnected] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [secondsSinceContact, setSecondsSinceContact] = useState<number | null>(null)

  // A ref, not state: this is written on every heartbeat (every 15s) and on
  // every state frame, and none of those writes should re-render anything by
  // themselves. The 1s ticker below is the only thing that turns it into a
  // rendered value, so the render cadence is the display's cadence, not the
  // stream's.
  const lastContactRef = useRef<number | null>(null)
  // Set once the server reports `complete`. Freezes the ticker: a finished
  // draft's "last contact" counting upward forever would paint a red `stale`
  // pill on a stream that ended exactly as it was supposed to.
  const finishedRef = useRef(false)

  useEffect(() => {
    if (!draftId) return
    let cancelled = false
    let source: EventSource | null = null
    let retryTimer: number | undefined

    function markContact() {
      lastContactRef.current = Date.now()
      setSecondsSinceContact(0)
    }

    function connect() {
      if (cancelled) return
      const es = new EventSource(`/api/drafts/${draftId}/live-stream`)
      source = es

      es.onopen = () => {
        if (cancelled) return
        setConnected(true)
        setError(null)
        markContact()
      }

      es.addEventListener('state', (ev) => {
        if (cancelled) return
        markContact()
        setConnected(true)
        let next: LiveState
        try {
          next = JSON.parse((ev as MessageEvent).data) as LiveState
        } catch {
          setError('live stream sent a state frame that was not JSON')
          return
        }
        setLive(next)
        // A state frame proves the stream is working, so a stale message from
        // an earlier `event: error` shouldn't keep sitting on screen.
        setError(null)

        // CRITICAL: on `complete` the server sends this final frame and closes.
        // EventSource treats a server-side close as a dropped connection and
        // reconnects (~3s), the reconnected request sees `complete`, sends one
        // frame and closes again -- a reconnect loop that never ends. Nothing
        // on the server can stop it; it has to be closed from here.
        if (next.status === 'complete') {
          finishedRef.current = true
          es.close()
          setConnected(false)
        }
      })

      es.addEventListener('heartbeat', () => {
        if (cancelled) return
        markContact()
        setConnected(true)
      })

      // One listener for two different things, because the spec gives them the
      // same event type: a server-sent `event: error` frame arrives as a
      // MessageEvent carrying `data`, while a transport failure arrives as a
      // bare Event with none. Registering es.onerror *as well* would double-
      // handle both, so this branch is the whole error path.
      es.addEventListener('error', (ev) => {
        if (cancelled) return
        const data = (ev as MessageEvent).data
        if (typeof data === 'string') {
          markContact()
          try {
            const parsed = JSON.parse(data) as { message?: string }
            setError(parsed.message ?? data)
          } catch {
            setError(data)
          }
          return
        }
        // Transport error. Do NOT tear the source down -- for an ordinary drop
        // EventSource is already reconnecting on its own and will re-open.
        setConnected(false)
        if (es.readyState === EventSource.CLOSED) {
          // ...except here, where the browser has given up for good. See
          // RETRY_MS.
          retryTimer = window.setTimeout(connect, RETRY_MS)
        }
      })
    }

    finishedRef.current = false
    connect()

    return () => {
      cancelled = true
      if (retryTimer !== undefined) window.clearTimeout(retryTimer)
      source?.close()
      setConnected(false)
    }
  }, [draftId])

  useEffect(() => {
    const id = window.setInterval(() => {
      if (finishedRef.current) return
      const last = lastContactRef.current
      if (last == null) return
      setSecondsSinceContact(Math.floor((Date.now() - last) / 1000))
    }, 1000)
    return () => window.clearInterval(id)
  }, [draftId])

  return { live, connected, secondsSinceContact, error }
}
