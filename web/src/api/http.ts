import { ApiError } from '../apiError'
import { currentUserId } from '../user'

export async function apiError(res: Response): Promise<ApiError> {
  // Several controllers answer 404 with an empty body, so a parse failure is normal.
  const body: { error?: string; message?: string } = await res.json().catch(() => ({}))
  const retryAfter = Number(res.headers?.get?.('Retry-After'))
  return new ApiError(
    res.status,
    body.error ?? body.message ?? undefined,
    Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined,
  )
}

export async function json<T>(res: Response): Promise<T> {
  if (!res.ok) throw await apiError(res)
  return res.json() as Promise<T>
}

// Blank (local default) means every call below stays a same-origin relative
// path, exactly as it worked before this existed -- Vite's dev proxy and a
// same-origin production deploy both need nothing set. Only a split-origin
// deploy (DEPLOY.md: Vercel frontend + Fly/Railway backend) sets this.
const API_BASE = (import.meta.env.VITE_API_BASE ?? '').replace(/\/$/, '')

// Also blank locally -- API_TOKEN is unset by default, so there is no header
// to send and every route stays open, matching the backend's own default. See
// DEPLOY.md's warning: this ships to every browser that loads the page, which
// is fine for a private tool and not fine for one you hand out a link to.
const API_TOKEN = import.meta.env.VITE_API_TOKEN

/** `/api/...` -> the full request URL, honoring VITE_API_BASE. */
export const apiUrl = (path: string) => `${API_BASE}${path}`

/**
 * Every call in this file goes through here instead of bare `fetch` so a
 * split-origin deploy and a bearer token are both a config change, not a
 * per-call edit. Does NOT cover `useLiveDraft.ts`'s EventSource -- the
 * browser's native EventSource can't set a request header at all, so
 * live-mode against a token-protected backend needs its own answer (a
 * query-string token the backend also accepts, most likely) before it can be
 * deployed. Not needed for same-origin or auth-off deploys.
 */
export function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  if (API_TOKEN) headers.set('Authorization', `Bearer ${API_TOKEN}`)
  // Read synchronously off user.ts's module-level variable rather than a React
  // hook -- this function is plain, called from outside any component. Absent
  // when signed out, matching the backend's own no-header default.
  const userId = currentUserId()
  if (userId) headers.set('X-Sleeper-User', userId)
  return fetch(apiUrl(path), { ...init, headers })
}
