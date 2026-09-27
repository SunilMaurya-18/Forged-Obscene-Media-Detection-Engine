import { Moon, Sun } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useTheme } from '../context/ThemeContext'
import { cn } from '../lib/format'

/** `className` must include the display utility (`grid`, `hidden sm:grid`, ...); cn() doesn't dedupe. */
export default function ThemeToggle({ className = 'grid' }) {
  const { t } = useTranslation()
  const { theme, toggleTheme } = useTheme()
  const label = theme === 'dark' ? t('common.theme.toLight') : t('common.theme.toDark')
  const Icon = theme === 'dark' ? Sun : Moon
  return (
    <button
      type="button"
      onClick={toggleTheme}
      aria-label={label}
      title={label}
      className={cn(
        'place-items-center size-9 shrink-0 rounded-xl border border-line text-muted hover:text-fg hover:bg-white/[0.05] transition',
        className,
      )}
    >
      <Icon aria-hidden className="size-4" />
    </button>
  )
}
