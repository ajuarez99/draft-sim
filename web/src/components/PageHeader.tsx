import type { ReactNode } from 'react'

type Props = {
  /** Small uppercase line above the title: where you are, or what this is. */
  eyebrow?: ReactNode
  title: ReactNode
  /** One line of explanation. This is where a page's "what this screen is"
   *  paragraph belongs -- several pages had theirs buried inside their first
   *  panel, under a panel heading, which is a caption for a box rather than a
   *  description of a page. */
  sub?: ReactNode
  /** The page's own primary action, right-aligned on the same baseline as the
   *  title (Home's "Start a mock draft"). Distinct from `.app-main-head`,
   *  AppShell's portal row for page-scoped *utilities* -- DraftView's
   *  settings gear -- which no page owns a header slot for. */
  actions?: ReactNode
}

/**
 * The app's page header: eyebrow, title, one line of explanation.
 *
 * Home and Power rankings each invented this independently
 * (`.home-eyebrow`/`.home-title`/`.home-header-sub` and
 * `.pr-eyebrow`/`.pr-headline`/`.pr-deck`), and every other page had no page
 * title at all -- just a `.panel h2`, 15px uppercase muted, INSIDE the first
 * box. So a page's own name read as a caption for its first panel.
 * claude/site-wide-shell-propagation.md Phase 4.
 *
 * Power rankings keeps its own layout (the headline sits beside a "Number
 * one" card) and uses the `.page-title.hero` / `.page-eyebrow.accent`
 * variants for its type. That is deliberate rather than a holdout: a story
 * headline that changes with the week is a different thing from a page title,
 * and flattening 38px editorial down to a 28px label would erase a real
 * distinction. One vocabulary, two scales -- not two vocabularies.
 */
export default function PageHeader({ eyebrow, title, sub, actions }: Props) {
  return (
    <header className="page-head">
      <div className="page-head-text">
        {eyebrow && <p className="page-eyebrow">{eyebrow}</p>}
        <h1 className="page-title cond">{title}</h1>
        {sub && <p className="page-sub">{sub}</p>}
      </div>
      {actions}
    </header>
  )
}
