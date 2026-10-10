/**
 * An HTTP failure carrying its status, so a page can branch on `status === 404`
 * instead of string-matching a message that changes with the transport
 * (`statusText` is "" over HTTP/2). The message is never empty.
 */
export class ApiError extends Error {
  readonly status: number
  /** Seconds from a `Retry-After` header, when the server sent a numeric one. */
  readonly retryAfterSeconds?: number
  constructor(status: number, message?: string, retryAfterSeconds?: number) {
    super(message || `HTTP ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.retryAfterSeconds = retryAfterSeconds
  }
}

export const isUnauthorized = (e: unknown): boolean => e instanceof ApiError && e.status === 401

export const isNotFound = (e: unknown): boolean => e instanceof ApiError && e.status === 404
