import { ArrowRight, LockKeyhole } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { cn } from '../lib/format'

/** Shown on upload screens before any file is chosen (Phase 21), pointing to the on-device check (Phase 18). */
export default function PrivacyNote({ compact = false, className }) {
  const { t } = useTranslation()
  return (
    <aside
      aria-label={t('privacy.title')}
      className={cn('flex gap-3 rounded-xl border border-emerald-400/20 bg-emerald-400/[0.05] px-4 py-3', className)}
    >
      <LockKeyhole aria-hidden className="size-4 mt-0.5 shrink-0 text-emerald-300" />
      <div className="min-w-0 text-[13px] leading-relaxed">
        <p className="font-medium text-emerald-100">{t('privacy.title')}</p>
        <p className="text-muted mt-0.5">{t('privacy.body')}</p>
        {!compact && <p className="text-muted mt-1.5">{t('privacy.onDevice')}</p>}
        <Link to="/analyze#prescreen" className="inline-flex items-center gap-1 mt-1.5 text-xs font-medium text-emerald-200 hover:text-emerald-100">
          {t('privacy.onDeviceLink')}
          <ArrowRight aria-hidden className="size-3.5" />
        </Link>
      </div>
    </aside>
  )
}
