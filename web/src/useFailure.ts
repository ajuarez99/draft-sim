import { useCallback, useState } from 'react'
import { isNotFound } from './apiError'

/**
 * Error state for a page that loads from the API. `setError` keeps the plain
 * string contract pages already had; `fail(e)` additionally records whether the
 * failure was a 404, so a page renders the shared not-found panel off the
 * status code instead of matching text in the message.
 */
export function useFailure() {
  const [failure, setFailure] = useState<{ message: string; notFound: boolean } | null>(null)
  const setError = useCallback(
    (message: string | null) => setFailure(message === null ? null : { message, notFound: false }),
    [],
  )
  const fail = useCallback(
    (e: unknown) =>
      setFailure({ message: e instanceof Error ? e.message : String(e), notFound: isNotFound(e) }),
    [],
  )
  return { error: failure?.message ?? null, notFound: failure?.notFound ?? false, setError, fail }
}
