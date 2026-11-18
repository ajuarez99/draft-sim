/**
 * The admin token for the unlisted owner pages (/admin/digest, /admin/usage), kept for the tab session only. One
 * definition, used by both pages. The real gate is the backend's admin token; this just spares retyping it.
 */
const TOKEN_KEY = 'bk.adminToken'

// sessionStorage can throw (private windows, blocked site data); the pages must work without it.
export function readToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function writeToken(token: string | null) {
  try {
    if (token) sessionStorage.setItem(TOKEN_KEY, token)
    else sessionStorage.removeItem(TOKEN_KEY)
  } catch {
    /* no storage: the token just lasts as long as the component does */
  }
}
