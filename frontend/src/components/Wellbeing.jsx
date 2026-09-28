import { useEffect, useRef } from 'react'
import { Link } from 'react-router-dom'
import { Coffee, HeartHandshake, LifeBuoy } from 'lucide-react'
import { useNow } from '../hooks/useNow'
import { exposureMeter } from '../lib/appeals'
import { cn } from '../lib/format'
import { Button, Card, buttonClass } from './ui'

function SupportLink({ url, className }) {
  const href = url || '/support'
  const content = (
    <>
      <LifeBuoy aria-hidden className="size-4" /> Support resources
    </>
  )
  return href.startsWith('/') ? (
    <Link to={href} className={className}>
      {content}
    </Link>
  ) : (
    <a href={href} target="_blank" rel="noreferrer" className={className}>
      {content}
    </a>
  )
}

/** Phase 23: items seen this session against the configured limit. */
export function WellbeingMeter({ wellbeing, onBreak, busy }) {
  if (!wellbeing) return null
  const { ratio, warn } = exposureMeter(wellbeing)
  return (
    <Card className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 mb-6 animate-fade-up">
      <HeartHandshake aria-hidden className={cn('size-4 shrink-0', warn ? 'text-amber-300' : 'text-brand')} />
      <div className="flex-1 min-w-48">
        <div className="flex items-baseline justify-between gap-3 text-xs">
          <span className="font-medium text-fg">Exposure this session</span>
          <span className="tabular-nums text-muted">
            {wellbeing.exposures} of {wellbeing.limit} items
            {warn && ` · ${wellbeing.remaining} before a break`}
          </span>
        </div>
        <div
          role="meter"
          aria-label="Items viewed this session"
          aria-valuemin={0}
          aria-valuemax={wellbeing.limit}
          aria-valuenow={wellbeing.exposures}
          className="mt-1.5 h-1.5 rounded-full bg-white/[0.06] overflow-hidden"
        >
          <div className={cn('h-full rounded-full transition-[width]', warn ? 'bg-amber-400' : 'bg-brand')} style={{ width: `${ratio * 100}%` }} />
        </div>
      </div>
      <div className="flex items-center gap-2">
        <Button size="sm" variant="secondary" icon={Coffee} onClick={onBreak} loading={busy}>
          Take a break
        </Button>
        <SupportLink url={wellbeing.supportUrl} className={buttonClass('ghost', 'sm')} />
      </div>
    </Card>
  )
}

function mmss(ms) {
  const s = Math.max(0, Math.ceil(ms / 1000))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/** Shown instead of the queue while a break is enforced; the server refuses decisions until it ends. */
export function BreakScreen({ wellbeing, onEnded }) {
  const now = useNow(1000)
  const ends = wellbeing?.breakEndsAt ? new Date(wellbeing.breakEndsAt).getTime() : now
  const left = ends - now
  const heading = useRef(null)
  const ended = left <= 0

  useEffect(() => heading.current?.focus(), [])
  useEffect(() => {
    if (ended) onEnded?.()
  }, [ended, onEnded])

  return (
    <Card className="px-6 py-14 text-center animate-fade-up" role="region" aria-labelledby="break-title">
      <div className="mx-auto mb-5 grid place-items-center size-14 rounded-2xl border border-line-strong bg-surface-2">
        <Coffee aria-hidden className="size-6 text-brand" />
      </div>
      <h2 id="break-title" ref={heading} tabIndex={-1} className="text-xl font-semibold tracking-tight focus:outline-none">
        Time for a break
      </h2>
      <p className="text-sm text-muted mt-2 max-w-md mx-auto">
        You've reviewed {wellbeing?.exposures ?? 'many'} items this session. Step away from the screen; the queue will be here when you're back.
      </p>
      <p className="mt-6 text-4xl font-semibold tabular-nums" aria-live="off">
        {mmss(left)}
      </p>
      <p className="sr-only" aria-live="polite">
        {ended ? 'Break over. The queue is available again.' : ''}
      </p>
      <p className="text-xs text-subtle mt-1">Decisions, reveals and shortcuts are paused until the timer ends.</p>
      <div className="mt-6 flex justify-center">
        <SupportLink url={wellbeing?.supportUrl} className={buttonClass('secondary')} />
      </div>
    </Card>
  )
}
