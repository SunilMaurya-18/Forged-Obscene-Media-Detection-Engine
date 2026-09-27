import { FAMILY } from '../lib/family'
import { cn } from '../lib/format'

/** Best-effort generation-family attribution. Renders nothing for rows analysed before Phase 17. */
export default function FamilyChip({ family, confidence, className }) {
  if (!family) return null
  const meta = FAMILY[family] || FAMILY.unknown
  const Icon = meta.icon
  return (
    <span
      title={meta.description}
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-[11px] font-medium whitespace-nowrap',
        meta.tone,
        className,
      )}
    >
      <Icon className="size-3" />
      {meta.label}
      {confidence != null && family !== 'unknown' && (
        <span className="font-mono opacity-70">{Math.round(confidence * 100)}%</span>
      )}
    </span>
  )
}
