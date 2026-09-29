/**
 * An HTTP failure carrying its status, so a page can branch on `status === 404`
 * instead of string-matching a message that changes with the transport
 * (`statusText` is "" over HTTP/2). The message is never empty.
 */
export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message?: string) {
    super(message || `HTTP ${status}`)
    this.name = 'ApiError'
    this.status = status
  }
}

export const isNotFound = (e: unknown): boolean => e instanceof ApiError && e.status === 404
