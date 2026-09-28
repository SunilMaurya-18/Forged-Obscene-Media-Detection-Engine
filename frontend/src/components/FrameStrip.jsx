import { frameBars } from '../lib/media'
import { cn } from '../lib/format'

const BAND = {
  flag: { bar: 'bg-rose-400', label: 'auto-flag band' },
  review: { bar: 'bg-amber-400', label: 'review band' },
  ok: { bar: 'bg-emerald-400', label: 'below review' },
}

/**
 * Phase 23: per-frame forgery scores (bars) with the explicit score as dots. Scores only; the frames
 * themselves never leave the ML service.
 */
export default function FrameStrip({ video, thresholds, compact = false, className }) {
  const bars = frameBars(video, thresholds)
  if (!bars.length) return null
  const review = thresholds?.forgeryReview ?? 0.5
  const flag = thresholds?.forgeryAutoFlag ?? 0.85
  const height = compact ? 'h-10' : 'h-24'
  return (
    <figure className={className}>
      <div
        role="img"
        aria-label={`Per-frame forgery scores for ${bars.length} sampled frames; peak at ${bars.find((b) => b.peak)?.t ?? '?'} seconds`}
        className={cn('relative flex items-end gap-px rounded-lg border border-line bg-white/[0.02] px-1 pt-1', height)}
      >
        {[review, flag].map((t, i) => (
          <span
            key={t}
            aria-hidden
            className={cn('absolute inset-x-0 border-t border-dashed', i ? 'border-rose-300/50' : 'border-amber-300/50')}
            style={{ bottom: `${t * 100}%` }}
          />
        ))}
        {bars.map((b) => (
          <span
            key={b.index}
            title={`#${b.index} at ${b.t}s: forgery ${b.forgery.toFixed(2)} (${BAND[b.band].label}), explicit ${b.explicit.toFixed(2)}${b.peak ? ', peak' : ''}`}
            className="relative flex-1 h-full flex items-end"
          >
            <span
              aria-hidden
              className={cn('w-full rounded-t-sm', BAND[b.band].bar, b.peak ? 'opacity-100 ring-2 ring-fg/80' : 'opacity-75')}
              style={{ height: `${Math.max(b.forgery * 100, 2)}%` }}
            />
            <span
              aria-hidden
              className="absolute left-1/2 -translate-x-1/2 size-1 rounded-full bg-indigo-300"
              style={{ bottom: `calc(${b.explicit * 100}% - 2px)` }}
            />
          </span>
        ))}
      </div>
      {!compact && (
        <figcaption className="flex flex-wrap justify-between gap-2 text-[11px] text-subtle mt-1.5">
          <span>
            {bars[0].t}s → {bars[bars.length - 1].t}s · bars: forgery · dots: explicit · outlined: peak frame
          </span>
          <span>dashed: review {review} / flag {flag}</span>
        </figcaption>
      )}
    </figure>
  )
}
