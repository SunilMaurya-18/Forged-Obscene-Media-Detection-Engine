import { useId, useState } from 'react'
import { ChevronDown } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { cn } from '../lib/format'
import { VERDICTS } from '../lib/verdict'
import { Card } from './ui'

/**
 * Plain-language verdict pill: "Likely forged" / "Needs a closer look" / "Looks genuine".
 * Icon + text + colour, so it reads the same without colour vision.
 */
export default function VerdictBadge({ verdict, size = 'md', className }) {
  const { t } = useTranslation()
  const meta = VERDICTS[verdict] || VERDICTS.NEEDS_REVIEW
  const Icon = meta.icon
  return (
    <span
      className={cn(
        'inline-flex items-center gap-2 rounded-full border font-semibold tracking-[0.02em]',
        meta.ring,
        meta.text,
        size === 'sm' && 'px-2.5 py-1 text-[11px]',
        size === 'md' && 'px-3 py-1.5 text-xs',
        size === 'lg' && 'px-3.5 py-2 text-sm',
        className,
      )}
    >
      <Icon aria-hidden className={size === 'lg' ? 'size-4' : 'size-3.5'} />
      {t(`verdict.${verdict}.label`)}
    </span>
  )
}

/**
 * The verdict first, in words; scores and heatmap (children) only behind "See details".
 * {@code next} is the routing decision (ALLOW / REVIEW / AUTO_FLAG), phrased as what happens next.
 */
export function VerdictPanel({ verdict, next, meta: footer, children, className }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const detailsId = useId()
  const meta = VERDICTS[verdict] || VERDICTS.NEEDS_REVIEW
  const Icon = meta.icon

  return (
    <Card className={cn('p-6 overflow-hidden animate-fade-up', meta.ring, className)}>
      <div aria-hidden className={cn('absolute inset-x-0 top-0 h-32 bg-gradient-to-b to-transparent pointer-events-none', meta.glow)} />
      <div className="relative flex items-start gap-4">
        <span className={cn('grid place-items-center size-12 shrink-0 rounded-2xl border', meta.ring)}>
          <Icon aria-hidden className={cn('size-6', meta.text)} />
        </span>
        <div className="flex-1 min-w-0">
          <p className="label-caps">{t('verdict.automatedCheck')}</p>
          <p className={cn('font-serif text-[28px] leading-[1.25] font-medium tracking-[-0.01em] mt-2', meta.text)}>{t(`verdict.${verdict}.label`)}</p>
          <p className="text-[15px] text-muted mt-2.5 leading-[1.7] max-w-prose">{t(`verdict.${verdict}.description`)}</p>
          {next && (
            <p className="text-[15px] mt-4 leading-[1.65]">
              <span className="text-subtle">{t('verdict.whatHappensNext')}: </span>
              {t(`verdict.next.${next}`)}
            </p>
          )}
          {footer && <div className="text-xs text-subtle mt-4 font-mono">{footer}</div>}
        </div>
      </div>

      {children && (
        <div className="relative mt-5">
          <button
            type="button"
            aria-expanded={open}
            aria-controls={detailsId}
            onClick={() => setOpen((o) => !o)}
            className="w-full flex items-center justify-between gap-3 rounded-xl border border-line bg-white/[0.02] px-4 py-3 text-left text-sm font-medium hover:bg-white/[0.05] transition"
          >
            <span>
              {open ? t('verdict.hideDetails') : t('verdict.seeDetails')}
              <span className="block text-xs font-normal text-subtle mt-0.5">{t('verdict.detailsHint')}</span>
            </span>
            <ChevronDown aria-hidden className={cn('size-4 shrink-0 transition-transform', open && 'rotate-180')} />
          </button>
          <div id={detailsId} hidden={!open} className="mt-4 space-y-5">
            {open && children}
          </div>
        </div>
      )}
    </Card>
  )
}
