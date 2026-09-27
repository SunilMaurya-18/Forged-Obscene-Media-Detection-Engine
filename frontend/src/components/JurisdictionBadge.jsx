import { Scale } from 'lucide-react'
import { cn } from '../lib/format'

/** "IN · India IT Rules 2026 — 2h". The label is the rule frozen on the item when its complaint was filed. */
export default function JurisdictionBadge({ jurisdiction, badge, className }) {
  if (!badge && !jurisdiction) return null
  return (
    <span
      title="Takedown rule applied to this complaint"
      className={cn(
        'inline-flex items-center gap-1.5 rounded-lg border border-sky-400/25 bg-sky-400/[0.07] px-2 py-1 text-xs text-sky-200 whitespace-nowrap',
        className,
      )}
    >
      <Scale className="size-3.5" />
      {jurisdiction && <span className="font-mono font-semibold">{jurisdiction}</span>}
      {badge && <span className="text-sky-100/90">{badge}</span>}
    </span>
  )
}
