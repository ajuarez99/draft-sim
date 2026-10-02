import { useEffect, useState } from 'react'
import { isNotFound } from './apiError'

/** specs/015 research R8: moved verbatim from pages/LeagueHome.tsx so the home page's spotlight can share it. */
export type Block<T> = { status: 'idle' } | { status: 'loading' } | { status: 'error'; notFound: boolean } | { status: 'ok'; data: T }

/** One endpoint, one block. `load` null means "this block does not apply"
 *  (idle): rendered as nothing, never as an empty state. */
export function useBlock<T>(load: (() => Promise<T>) | null, deps: readonly unknown[]): Block<T> {
  const [state, setState] = useState<Block<T>>(load ? { status: 'loading' } : { status: 'idle' })
  useEffect(() => {
    if (!load) {
      setState({ status: 'idle' })
      return
    }
    let cancelled = false
    setState({ status: 'loading' })
    load()
      .then((data) => {
        if (!cancelled) setState({ status: 'ok', data })
      })
      .catch((e) => {
        if (!cancelled) setState({ status: 'error', notFound: isNotFound(e) })
      })
    return () => {
      cancelled = true
    }
    // `load` is a fresh closure every render; `deps` is the real identity of the request.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)
  return state
}
