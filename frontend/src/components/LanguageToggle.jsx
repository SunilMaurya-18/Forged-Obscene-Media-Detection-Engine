import { Languages } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { LANGUAGES } from '../i18n'
import { cn } from '../lib/format'

/** `className` must include the display utility (`inline-flex`, `hidden sm:inline-flex`, ...); cn() doesn't dedupe. */
export default function LanguageToggle({ className = 'inline-flex', iconClassName }) {
  const { t, i18n } = useTranslation()
  return (
    <div
      role="group"
      aria-label={t('common.language')}
      title={t('common.language')}
      className={cn('items-center h-9 shrink-0 rounded-xl border border-line-strong bg-white/[0.03] p-0.5', className)}
    >
      <Languages aria-hidden className={cn('size-4 text-fg mx-1.5', iconClassName)} />
      {LANGUAGES.map(({ code, label, short }) => {
        const active = i18n.resolvedLanguage === code
        return (
          <button
            key={code}
            type="button"
            lang={code}
            aria-pressed={active}
            aria-label={label}
            title={label}
            onClick={() => i18n.changeLanguage(code)}
            className={cn(
              'h-7 min-w-9 px-2 rounded-lg text-xs font-semibold transition',
              active ? 'bg-brand text-[#fff] shadow-sm' : 'text-muted hover:text-fg hover:bg-white/[0.06]',
            )}
          >
            {short}
          </button>
        )
      })}
    </div>
  )
}
