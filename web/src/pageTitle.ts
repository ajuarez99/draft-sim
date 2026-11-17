import { useEffect } from 'react'
import { currentSite } from './siteEnv'

/**
 * Spec 074 PR-08: each Redraft page sets its own `document.title` from the same copy function the static file's
 * `<title>` is written from (redraftPages.ts), so the title a crawler reads and the one a tab shows agree.
 *
 * Only on the Redraft site (CR-04): the pages that call this are also mounted by the combined build, whose App sets the
 * league titles itself; acting there would leave the Redraft title on every league page until a reload.
 */
export function usePageTitle(title: string): void {
  useEffect(() => {
    if (currentSite().site !== 'timemachine') return
    document.title = title
  }, [title])
}
