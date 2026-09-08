/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL prepended to every `/api/...` path. Blank (local default) means same-origin. */
  readonly VITE_API_BASE?: string
  /** Sent as `Authorization: Bearer <token>` on every request when set. See DEPLOY.md. */
  readonly VITE_API_TOKEN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
