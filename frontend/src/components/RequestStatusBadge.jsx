import { REQUEST_STATUS } from '../lib/handover'
import { cn } from '../lib/format'

const TONES = {
  amber: 'border-amber-400/30 text-amber-200',
  emerald: 'border-emerald-400/30 text-emerald-200',
  rose: 'border-rose-400/30 text-rose-200',
  slate: 'border-line-strong text-muted',
}

/** Phase 28: a legal request's status, with what it means on hover. */
export default function RequestStatusBadge({ status }) {
  const s = REQUEST_STATUS[status]
  return (
    <span className={cn('rounded-full border px-2 py-0.5 text-[11px] font-semibold whitespace-nowrap', TONES[s?.tone] || TONES.slate)} title={s?.hint}>
      {s?.label || status}
    </span>
  )
}
