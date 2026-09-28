import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Film, FlaskConical, ImageIcon, Loader2 } from 'lucide-react'
import { fetchSampleFile } from '../api/client'
import { useDemo } from '../context/DemoContext'
import { errorText } from '../lib/errors'
import { cn } from '../lib/format'

/**
 * Phase 25: "Use a sample" chips for any upload area, shown while the demo is on. On the public demo
 * they are the only files the server accepts.
 */
export default function SamplePicker({ onPick, kinds, disabled, className }) {
  const { t } = useTranslation()
  const { samples, publicMode } = useDemo()
  const [loading, setLoading] = useState(null)
  const [error, setError] = useState(null)
  const shown = samples.filter((s) => !kinds || kinds.includes(s.kind))
  if (!shown.length) return null

  const pick = async (sample) => {
    setLoading(sample.name)
    setError(null)
    try {
      onPick(await fetchSampleFile(sample))
    } catch (err) {
      setError(err)
    } finally {
      setLoading(null)
    }
  }

  return (
    <div className={cn('rounded-xl border border-dashed border-line px-3.5 py-3', className)}>
      <p className="flex items-center gap-1.5 text-xs text-muted">
        <FlaskConical aria-hidden className="size-3.5 text-brand" />
        {publicMode ? t('demo.samples.publicHint') : t('demo.samples.hint')}
      </p>
      <div className="mt-2 flex flex-wrap gap-2">
        {shown.map((s) => {
          const Icon = loading === s.name ? Loader2 : s.kind === 'video' ? Film : ImageIcon
          return (
            <button
              key={s.name}
              type="button"
              disabled={disabled || Boolean(loading)}
              onClick={() => pick(s)}
              title={t(`demo.samples.kinds.${s.kind}.body`)}
              className="inline-flex items-center gap-1.5 rounded-lg border border-line bg-white/[0.03] px-2.5 py-1.5 text-xs font-medium hover:border-line-strong hover:bg-white/[0.06] disabled:opacity-50 transition"
            >
              <Icon aria-hidden className={cn('size-3.5', loading === s.name && 'animate-spin')} />
              {t(`demo.samples.kinds.${s.kind}.label`)}
            </button>
          )
        })}
      </div>
      {error && <p className="mt-2 text-xs text-rose-300">{errorText(t, error)}</p>}
    </div>
  )
}
