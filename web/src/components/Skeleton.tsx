/**
 * Loading placeholders.
 *
 * The app had none anywhere: `DraftPicker` gated both its branches on
 * `drafts &&`, so a fetch in flight rendered a heading with nothing under it
 * and then popped rows in; `MockSetup`'s seat list was simply absent until
 * managers loaded, so the form changed height under the cursor; and
 * `MockDraftView` returned a literally empty `<div className="content" />`.
 *
 * `SkeletonRows` matches `.draft-list`'s row geometry to the pixel (49px,
 * measured against a real row -- see styles.css `.skeleton-chip`), so nothing
 * shifts when the real content replaces it. That is the point, and the first
 * version of this got it wrong by 15px a row: a skeleton of the wrong height
 * is worse than none, because it moves the page twice instead of once.
 *
 * It is therefore only for lists built out of `.draft-row`. Somewhere with a
 * different row shape should say "Loading…" rather than draw a placeholder
 * that is a lie about what is coming.
 */

type RowsProps = {
  /** How many rows the real list is likely to have. Height, not precision. */
  count?: number
  /** Screen-reader label for what is loading. */
  label?: string
}

export function SkeletonRows({ count = 3, label = 'Loading' }: RowsProps) {
  return (
    <div className="draft-list" role="status" aria-label={label} aria-busy="true">
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="skeleton-row">
          {/* Two shapes, not one: a `.draft-row` is a strong left-hand label
              and a status chip on the right, and a single full-width bar reads
              as a different layout arriving. The chip placeholder is also what
              sets the row's height -- see styles.css, it is measured against
              the real chip so the list doesn't jump when content lands. */}
          <span className="skeleton-bar" style={{ width: `${38 - i * 6}%` }} />
          <span className="skeleton-chip" />
        </div>
      ))}
    </div>
  )
}

type WaitProps = {
  /** What is being fetched, in the reader's words. */
  label: string
}

/**
 * A whole-screen wait, for a route with nothing to show until its fetch lands.
 *
 * Deliberately just a line, by the same rule as above: what arrives here is a
 * draft board and its header strip, nothing like a list of rows, so drawing
 * rows would be a placeholder that lies about what is coming. It borrows the
 * `.start-overlay-status` treatment the board's own "Simulating your draft…"
 * already uses, so waiting looks the same wherever it happens.
 */
export function LoadingScreen({ label }: WaitProps) {
  return (
    <div className="content">
      <div className="loading-screen" role="status" aria-busy="true">
        <span className="cond">{label}</span>
      </div>
    </div>
  )
}
