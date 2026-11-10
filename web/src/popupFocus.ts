import { useEffect, type RefObject } from 'react'

const FOCUSABLE = 'a[href], button:not([disabled]), [role="menuitem"], input, select, textarea, [tabindex]:not([tabindex="-1"])'

/**
 * The keyboard contract every popup in the new chrome shares (the account menu, the season
 * dropdown, the league switcher; the nav sheet adds its own trap on top): focus moves INTO the
 * popup when it opens, Escape closes it and puts focus back on the control that opened it, and a
 * press outside both closes it. One hook so the four of them cannot drift into four behaviours
 * (claude/site-redesign.md, the audit's accessibility findings).
 *
 * The menus here are links and buttons, not a roving-tabindex listbox: Tab walks them, which is
 * what a menu of links needs, so no arrow-key handling is claimed.
 */
export function usePopup(
  open: boolean,
  close: () => void,
  container: RefObject<HTMLElement | null>,
  trigger: RefObject<HTMLElement | null>,
) {
  useEffect(() => {
    if (!open) return
    container.current?.querySelector<HTMLElement>(FOCUSABLE)?.focus()

    function onKey(e: KeyboardEvent) {
      if (e.key !== 'Escape') return
      e.stopPropagation()
      close()
      trigger.current?.focus()
    }
    function onDown(e: MouseEvent) {
      const t = e.target as Node
      if (container.current?.contains(t) || trigger.current?.contains(t)) return
      close()
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onDown)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onDown)
    }
    // close/container/trigger are stable per render of the owner; re-binding on `open` is the point.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])
}
