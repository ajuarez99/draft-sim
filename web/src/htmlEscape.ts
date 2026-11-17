// Spec 074: split out of landingHtml.ts so seasonPageHtml.ts can use it without a circular import
// (landingHtml imports the page bodies, the bodies need esc). Pure.
export function esc(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** JSON for a `<script type="application/json">` block: a literal "<" could close the element early (PR-26). */
export function jsonForScript(value: unknown): string {
  return JSON.stringify(value).replace(/</g, '\\u003c')
}
