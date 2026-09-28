import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { FlaskConical, RotateCcw } from 'lucide-react'
import { resetDemo } from '../api/client'
import { useDemo } from '../context/DemoContext'
import { secondsUntil } from '../lib/demo'
import { errorText } from '../lib/errors'
import { humanDuration } from '../lib/sla'

/** Phase 25: on a hosted public demo, every page says so and offers a reset. */
export default function DemoBanner() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { demo, publicMode, setDemo } = useDemo()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  if (!publicMode) return null
  const left = secondsUntil(demo.nextResetAt)

  const reset = async () => {
    setBusy(true)
    setError(null)
    try {
      setDemo(await resetDemo())
      navigate('/tour')
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div role="region" aria-label={t('demo.banner.label')} className="border-b border-amber-400/20 bg-amber-400/[0.06] print:hidden">
      <div className="max-w-6xl mx-auto px-4 md:px-8 py-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-amber-100">
        <p className="inline-flex items-center gap-1.5">
          <FlaskConical aria-hidden className="size-3.5 shrink-0" />
          {t('demo.banner.text')}
          {left > 0 && <span className="opacity-80">{t('demo.banner.next', { time: humanDuration(left) })}</span>}
        </p>
        <div className="flex items-center gap-3 ml-auto">
          <Link to="/tour" className="font-medium underline underline-offset-4 hover:text-fg">
            {t('demo.banner.tour')}
          </Link>
          <button
            type="button"
            onClick={reset}
            disabled={busy}
            className="inline-flex items-center gap-1 font-medium underline underline-offset-4 hover:text-fg disabled:opacity-50"
          >
            <RotateCcw aria-hidden className={busy ? 'size-3 animate-spin' : 'size-3'} />
            {t('demo.banner.reset')}
          </button>
        </div>
        {error && <p className="w-full text-rose-200">{errorText(t, error)}</p>}
      </div>
    </div>
  )
}
