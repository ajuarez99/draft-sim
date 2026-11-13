import { useCallback, useEffect, useRef, useState } from 'react'

export type AchievementToast = {
  /** Unique and stable per thing shown ("badge:CODE", "moment:CODE:pick", "level:4"): the queue is keyed by it. */
  key: string
  kind: 'badge' | 'moment' | 'level'
  title: string
  text?: string
  /** Badges only. */
  xp?: number
}

const MAX_VISIBLE = 3
const AUTO_DISMISS_MS = 6000

const KIND_LABEL: Record<AchievementToast['kind'], string> = {
  badge: 'Badge unlocked',
  moment: 'Moment',
  level: 'Level up',
}

/** False while the tab is in the background: nobody can see a toast there, so no clock runs and nothing is marked seen. */
function usePageVisible(): boolean {
  const [visible, setVisible] = useState(() => typeof document === 'undefined' || !document.hidden)
  useEffect(() => {
    const on = () => setVisible(!document.hidden)
    document.addEventListener('visibilitychange', on)
    on()
    return () => document.removeEventListener('visibilitychange', on)
  }, [])
  return visible
}

function ToastCard({
  toast,
  onDismiss,
  pageVisible,
}: {
  toast: AchievementToast
  onDismiss: (key: string) => void
  pageVisible: boolean
}) {
  const [paused, setPaused] = useState(false)
  useEffect(() => {
    if (paused || !pageVisible) return
    const t = window.setTimeout(() => onDismiss(toast.key), AUTO_DISMISS_MS)
    return () => window.clearTimeout(t)
  }, [paused, pageVisible, toast.key, onDismiss])

  return (
    <div
      className={`ach-toast ach-toast-${toast.kind}`}
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={() => setPaused(false)}
    >
      <div className="ach-toast-body">
        <span className="ach-toast-kind">{KIND_LABEL[toast.kind]}</span>
        <strong className="ach-toast-title">{toast.title}</strong>
        {toast.text && <span className="ach-toast-text">{toast.text}</span>}
      </div>
      {toast.kind === 'badge' && toast.xp != null && <span className="ach-toast-xp mono">+{toast.xp} XP</span>}
      <button type="button" className="ach-toast-close" aria-label={`Dismiss: ${toast.title}`} onClick={() => onDismiss(toast.key)}>
        <span aria-hidden="true">×</span>
      </button>
    </div>
  )
}

/**
 * Spec 064: a fixed stack of achievement toasts. At most three show; the rest wait their turn. Each is dismissible
 * and goes away on its own after about six seconds (the clock stops while the pointer or focus is on it). There is
 * no focus trap: nothing here moves focus.
 *
 * The caller hands over the whole list; `onShown` fires once per key, the first time it becomes visible (a ref keeps
 * the set, so React StrictMode's second effect run cannot repeat it).
 */
export default function AchievementToasts({
  toasts,
  onShown,
}: {
  toasts: AchievementToast[]
  onShown?: (toast: AchievementToast) => void
}) {
  const [dismissed, setDismissed] = useState<ReadonlySet<string>>(new Set())
  const shown = useRef(new Set<string>())
  const listed = new Set<string>()
  const queue = toasts.filter((t) => {
    if (listed.has(t.key) || dismissed.has(t.key)) return false
    listed.add(t.key)
    return true
  })
  const visible = queue.slice(0, MAX_VISIBLE)
  const visibleKeys = visible.map((t) => t.key).join('|')
  const pageVisible = usePageVisible()

  useEffect(() => {
    if (!pageVisible) return
    for (const t of visible) {
      if (shown.current.has(t.key)) continue
      shown.current.add(t.key)
      onShown?.(t)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visibleKeys, pageVisible])

  const dismiss = useCallback((key: string) => setDismissed((d) => new Set(d).add(key)), [])

  return (
    <div className="ach-toasts" role="status" aria-live="polite">
      {visible.map((t) => (
        <ToastCard key={t.key} toast={t} onDismiss={dismiss} pageVisible={pageVisible} />
      ))}
    </div>
  )
}
