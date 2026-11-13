import { useCallback, useEffect, useState } from 'react'

/**
 * The fan's personal draft queue for one Time Machine game (spec 063 FR-031..FR-034). Browser only: an ordered
 * list of player ids under `bk.tm.queue.<gameId>`. Every storage call is in try/catch; when storage is blocked the
 * queue still works in memory for this page load.
 *
 * `status` and `available` come from the reveal's VIEW, never the raw server state, so a queued player leaves at
 * the moment his pick is revealed (FR-032), not when the server made it. Pruning happens only while the loaded
 * game is DRAFTING and the pool is known; before the first load nothing is dropped. Once the game is seen COMPLETE
 * or ABANDONED the key is deleted.
 */
export type QueueGameStatus = 'DRAFTING' | 'COMPLETE' | 'ABANDONED'

const keyOf = (gameId: number) => `bk.tm.queue.${gameId}`

function read(gameId: number): number[] {
  try {
    const raw = localStorage.getItem(keyOf(gameId))
    if (raw == null) return []
    const v: unknown = JSON.parse(raw)
    if (!Array.isArray(v)) return []
    const seen = new Set<number>()
    const out: number[] = []
    for (const x of v) {
      if (typeof x === 'number' && Number.isFinite(x) && !seen.has(x)) {
        seen.add(x)
        out.push(x)
      }
    }
    return out
  } catch {
    return []
  }
}

function write(gameId: number, ids: number[]) {
  try {
    if (ids.length === 0) localStorage.removeItem(keyOf(gameId))
    else localStorage.setItem(keyOf(gameId), JSON.stringify(ids))
  } catch {
    // Memory only for this page load.
  }
}

/** Forget a game's queue; used when a game is abandoned from the list page, where no room sees it end (CR-07). */
export function clearDraftQueue(gameId: number) {
  clear(gameId)
}

function clear(gameId: number) {
  try {
    localStorage.removeItem(keyOf(gameId))
  } catch {
    // nothing to clear
  }
}

export type DraftQueue = {
  ids: number[]
  has: (id: number) => boolean
  add: (id: number) => void
  remove: (id: number) => void
  toggle: (id: number) => void
  moveUp: (id: number) => void
  moveDown: (id: number) => void
}

export function useDraftQueue(
  gameId: number,
  status: QueueGameStatus | null,
  available: { id: number }[] | null,
): DraftQueue {
  const [ids, setIds] = useState<number[]>(() => read(gameId))

  // A different game gets its own queue.
  useEffect(() => {
    setIds(read(gameId))
  }, [gameId])

  const commit = useCallback(
    (update: (cur: number[]) => number[]) => {
      setIds((cur) => {
        const next = update(cur)
        if (next === cur) return cur
        write(gameId, next)
        return next
      })
    },
    [gameId],
  )

  // Prune against the view's pool, only for a loaded DRAFTING game.
  useEffect(() => {
    if (status !== 'DRAFTING' || available == null) return
    const pool = new Set(available.map((c) => c.id))
    commit((cur) => (cur.every((id) => pool.has(id)) ? cur : cur.filter((id) => pool.has(id))))
  }, [status, available, commit])

  // A finished or abandoned game has no use for its queue.
  useEffect(() => {
    if (status === 'COMPLETE' || status === 'ABANDONED') {
      clear(gameId)
      setIds((cur) => (cur.length === 0 ? cur : []))
    }
  }, [status, gameId])

  const has = useCallback((id: number) => ids.includes(id), [ids])
  const add = useCallback((id: number) => commit((cur) => (cur.includes(id) ? cur : [...cur, id])), [commit])
  const remove = useCallback((id: number) => commit((cur) => (cur.includes(id) ? cur.filter((x) => x !== id) : cur)), [commit])
  const toggle = useCallback(
    (id: number) => commit((cur) => (cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id])),
    [commit],
  )
  const move = useCallback(
    (id: number, by: -1 | 1) =>
      commit((cur) => {
        const i = cur.indexOf(id)
        const j = i + by
        if (i < 0 || j < 0 || j >= cur.length) return cur
        const next = [...cur]
        const tmp = next[i]
        next[i] = next[j]
        next[j] = tmp
        return next
      }),
    [commit],
  )
  const moveUp = useCallback((id: number) => move(id, -1), [move])
  const moveDown = useCallback((id: number) => move(id, 1), [move])

  return { ids, has, add, remove, toggle, moveUp, moveDown }
}
