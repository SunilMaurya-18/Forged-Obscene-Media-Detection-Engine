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
      className={cn('items-center h-8 shrink-0 rounded-lg border border-line bg-surface-2 p-0.5', className)}
    >
      <Languages aria-hidden className={cn('size-3.5 text-subtle mx-1.5', iconClassName)} />
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
              'h-6 min-w-8 px-1.5 rounded-md text-[11px] font-semibold transition',
              active ? 'bg-surface-3 text-fg shadow-[0_1px_2px_rgb(0_0_0/0.3)]' : 'text-subtle hover:text-fg',
            )}
          >
            {short}
          </button>
        )
      })}
    </div>
  )
}
