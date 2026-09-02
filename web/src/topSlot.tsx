import { createContext, useContext } from 'react'

// A DOM node inside App's persistent header (`.top`) -- the one region that
// survives across every page and isn't part of any single draft's own
// content. DraftView portals its settings-popover trigger in here instead
// of growing a page-level toolbar of its own; see
// claude/board-first-layout-and-pick-latency.md §A, "the header is the one
// region that isn't part of the draft room proper." null until App's own
// ref callback has mounted the target div (and always null on pages, like
// DraftPicker, that never portal anything into it).
export const TopSlotContext = createContext<HTMLDivElement | null>(null)

export function useTopSlot() {
  return useContext(TopSlotContext)
}
