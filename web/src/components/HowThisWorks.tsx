import type { ReactNode } from 'react'

/**
 * Methodology, folded away. A page's explanation of how a number is made goes
 * here, at the end of the section it belongs to, instead of in the subtitle or
 * above the table. Closed by default; the text inside keeps its original
 * wording (edited for clarity, never for claims). A caveat that qualifies ONE
 * number does not belong here -- it stays beside that number as a badge.
 * See specs/013-fan-first-redesign/contracts/ui-rules.md, "Words".
 */
export default function HowThisWorks({ children }: { children: ReactNode }) {
  return (
    <details className="how">
      <summary>How this works</summary>
      {children}
    </details>
  )
}
