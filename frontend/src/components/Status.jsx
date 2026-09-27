import { Loader2, RotateCcw, TriangleAlert } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { errorText } from '../lib/errors'
import { cn } from '../lib/format'
import { buttonClass } from './ui'

/** Spinner plus one plain-language line; announced politely to screen readers. */
export function LoadingState({ label, className, children }) {
  const { t } = useTranslation()
  return (
    <div role="status" aria-live="polite" className={cn('flex flex-col items-center justify-center gap-3 py-10 text-center', className)}>
      <Loader2 aria-hidden className="size-6 animate-spin text-brand" />
      <p className="text-sm text-muted">{label || t('common.loading')}</p>
      {children}
    </div>
  )
}

/** Inline one-liner for buttons and small areas. */
export function LoadingLine({ label, className }) {
  return (
    <span role="status" aria-live="polite" className={cn('inline-flex items-center gap-2 text-sm text-muted', className)}>
      <Loader2 aria-hidden className="size-4 animate-spin text-brand" />
      {label}
    </span>
  )
}

/** Plain-language error with an optional retry; never shows raw status codes. */
export function ErrorState({ error, title, onRetry, className }) {
  const { t } = useTranslation()
  if (!error) return null
  return (
    <div role="alert" className={cn('flex gap-3 rounded-xl border border-rose-400/25 bg-rose-400/[0.07] px-4 py-3 text-sm text-rose-100 animate-fade-up', className)}>
      <TriangleAlert aria-hidden className="size-4 mt-0.5 shrink-0 text-rose-300" />
      <div className="flex-1 min-w-0">
        {title && <p className="font-medium mb-0.5">{title}</p>}
        <p className="leading-relaxed opacity-90">{errorText(t, error)}</p>
      </div>
      {onRetry && (
        <button type="button" onClick={onRetry} className={buttonClass('ghost', 'sm', 'shrink-0 text-rose-100')}>
          <RotateCcw aria-hidden className="size-3.5" />
          {t('common.tryAgain')}
        </button>
      )}
    </div>
  )
}
