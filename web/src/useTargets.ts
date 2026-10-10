import { useCallback, useEffect, useRef, useState } from 'react'
import { getTargets, putTargets, type PlayerRef, type TargetScope } from './api'
import { isNotFound } from './apiError'
import { itemsFromServer, type TargetItem } from './targets'

/**
 * loading: first fetch in the air. ready: editable. unavailable: the server predates the
 * endpoint (404). loadFailed: the first fetch failed for another reason; editing is held
 * back because saving from an empty list would overwrite whatever the server has.
 */
export type TargetsStatus = 'loading' | 'ready' | 'unavailable' | 'loadFailed'

export type UseTargets = {
  items: TargetItem[]
  status: TargetsStatus
  /** The last save failed; the local list is still what the user left. */
  error: boolean
  add: (player: PlayerRef) => void
  remove: (sleeperId: string) => void
  /** Moves one place: -1 up, +1 down. */
  move: (sleeperId: string, delta: -1 | 1) => void
  /** Retries the failed save, or the failed first load. */
  retry: () => void
}

const scopeKey = (s: TargetScope | null) =>
  s == null ? '' : 'sleeperDraftId' in s ? `d:${s.sleeperDraftId}` : `m:${s.mockSessionId}`

/**
 * The save loop currently running for each scope, across every mounted (or just unmounted)
 * hook. Each room mounts its own hook, so a user who edits in one room and moves to the
 * other while the PUT is in the air would otherwise GET the pre-edit list from the new
 * hook (code review R6). Entries never reject and are removed when the loop settles.
 */
const pendingSaves = new Map<string, Promise<void>>()

/**
 * A user's target list for one draft or mock (spec 024 US3, research A8).
 *
 * Edits show immediately and are saved by whole-list PUTs, with exactly one in flight:
 * edits made meanwhile only update the local list, and when the request returns one more
 * PUT of the latest list follows. A response is never applied over local state that moved
 * on, a failure keeps the list the user left (FR-014a), and the window-focus refetch is
 * skipped while anything is unsaved, saving or failed. A 404 means `unavailable`.
 */
export function useTargets(scope: TargetScope | null): UseTargets {
  const key = scopeKey(scope)
  const [items, setItemsState] = useState<TargetItem[]>([])
  const [status, setStatus] = useState<TargetsStatus>('loading')
  const [error, setErrorState] = useState(false)

  // Refs hold what the async paths must read fresh; state is only for rendering.
  const itemsRef = useRef<TargetItem[]>([])
  const scopeRef = useRef(scope)
  scopeRef.current = scope
  const inFlight = useRef(false)
  const dirty = useRef(false) // local edits not yet confirmed saved
  const errored = useRef(false)
  const epoch = useRef(0) // bumped on scope change so late responses are dropped
  const editSeq = useRef(0) // bumped on every local edit: a fetch that started before one is stale

  const setItems = useCallback((next: TargetItem[]) => {
    itemsRef.current = next
    setItemsState(next)
  }, [])
  const setErr = useCallback((v: boolean) => {
    errored.current = v
    setErrorState(v)
  }, [])

  const flush = useCallback(async () => {
    const s = scopeRef.current
    if (inFlight.current || !s) return
    const myEpoch = epoch.current
    inFlight.current = true
    const saveKey = scopeKey(s)
    let settle!: () => void
    const pending = new Promise<void>((res) => {
      settle = res
    })
    pendingSaves.set(saveKey, pending)
    try {
      for (;;) {
        const sent = itemsRef.current
        await putTargets(s, sent.map((t) => t.sleeperId))
        if (myEpoch !== epoch.current) return
        // The response is never applied: it only restates what was sent. If the local
        // list moved on meanwhile, loop and send the latest.
        if (itemsRef.current === sent) {
          dirty.current = false
          setErr(false)
          return
        }
      }
    } catch (e) {
      if (myEpoch !== epoch.current) return
      if (isNotFound(e)) {
        dirty.current = false
        setStatus('unavailable')
      } else {
        setErr(true) // the local list stays on screen and stays dirty
      }
    } finally {
      if (myEpoch === epoch.current) inFlight.current = false
      if (pendingSaves.get(saveKey) === pending) pendingSaves.delete(saveKey)
      settle()
    }
  }, [setErr])

  const edit = useCallback(
    (next: TargetItem[]) => {
      setItems(next)
      editSeq.current++
      dirty.current = true
      setErr(false)
      void flush()
    },
    [flush, setItems, setErr],
  )

  const load = useCallback(() => {
    const s = scopeRef.current
    if (!s) return
    const myEpoch = epoch.current
    const myEdit = editSeq.current
    // Wait out a save for this scope that another (possibly unmounted) hook has in the air.
    const saving = pendingSaves.get(scopeKey(s))
    const fetched = saving ? saving.then(() => getTargets(s)) : getTargets(s)
    fetched.then(
      (t) => {
        if (myEpoch !== epoch.current || myEdit !== editSeq.current || dirty.current || inFlight.current || errored.current) return
        setItems(itemsFromServer(t))
        setStatus('ready')
      },
      (e) => {
        if (myEpoch !== epoch.current) return
        if (isNotFound(e)) setStatus('unavailable')
        else setStatus((cur) => (cur === 'loading' ? 'loadFailed' : cur)) // a failed refetch keeps what we have
      },
    )
  }, [setItems])

  useEffect(() => {
    epoch.current++
    inFlight.current = false
    dirty.current = false
    errored.current = false
    setErrorState(false)
    setItems([])
    setStatus('loading')
    load()
  }, [key, load, setItems])

  // Refetch on focus so an edit made on another device shows up (FR-014).
  useEffect(() => {
    const onFocus = () => {
      if (dirty.current || inFlight.current || errored.current) return
      load()
    }
    window.addEventListener('focus', onFocus)
    return () => window.removeEventListener('focus', onFocus)
  }, [load])

  const add = useCallback(
    (player: PlayerRef) => {
      if (itemsRef.current.some((t) => t.sleeperId === player.sleeperId)) return
      edit([...itemsRef.current, { sleeperId: player.sleeperId, name: player.name, player }])
    },
    [edit],
  )
  const remove = useCallback(
    (sleeperId: string) => edit(itemsRef.current.filter((t) => t.sleeperId !== sleeperId)),
    [edit],
  )
  const move = useCallback(
    (sleeperId: string, delta: -1 | 1) => {
      const cur = itemsRef.current
      const i = cur.findIndex((t) => t.sleeperId === sleeperId)
      const j = i + delta
      if (i < 0 || j < 0 || j >= cur.length) return
      const next = [...cur]
      ;[next[i], next[j]] = [next[j], next[i]]
      edit(next)
    },
    [edit],
  )
  const retry = useCallback(() => {
    if (status === 'loadFailed') {
      setStatus('loading')
      load()
      return
    }
    setErr(false)
    void flush()
  }, [status, load, flush, setErr])

  return { items, status, error, add, remove, move, retry }
}
