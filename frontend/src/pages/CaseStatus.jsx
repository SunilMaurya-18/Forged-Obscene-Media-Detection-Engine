import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Bell, BellOff, CircleCheck, CircleSlash, Eye, EyeOff, FilePlus2, Hourglass, RefreshCw, Search, Timer, TriangleAlert } from 'lucide-react'
import { fetchCaseStatus } from '../api/client'
import { ErrorState, LoadingState } from '../components/Status'
import VerdictBadge from '../components/VerdictBadge'
import { Button, Card, INPUT, PageHeader, buttonClass } from '../components/ui'
import { formatWhen, isValidPin, normalizeCaseNumber } from '../lib/cases'
import { cn } from '../lib/format'

/** Icon + title + text per stage; colour is only a secondary cue. */
const STAGES = {
  IN_REVIEW: { icon: Hourglass, tone: 'border-amber-400/30 bg-amber-400/[0.08] text-amber-300' },
  REMOVED: { icon: CircleCheck, tone: 'border-emerald-400/30 bg-emerald-400/[0.08] text-emerald-300' },
  NO_ACTION: { icon: CircleSlash, tone: 'border-sky-400/30 bg-sky-400/[0.08] text-sky-300' },
}

function LookupForm({ initialCase, initialPin, onFound }) {
  const { t } = useTranslation()
  const [caseNumber, setCaseNumber] = useState(initialCase || '')
  const [pin, setPin] = useState(initialPin || '')
  const [showPin, setShowPin] = useState(false)
  const [fieldErrors, setFieldErrors] = useState({})
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const pinRef = useRef(null)

  useEffect(() => {
    if (initialCase) pinRef.current?.focus()
  }, [initialCase])

  const submit = async (e) => {
    e.preventDefault()
    const normalized = normalizeCaseNumber(caseNumber)
    const errs = {}
    if (!normalized) errs.caseNumber = t('status.caseNumberHint')
    if (!isValidPin(pin)) errs.pin = t('status.pinHint')
    setFieldErrors(errs)
    if (Object.keys(errs).length) return
    setBusy(true)
    setError(null)
    try {
      const view = await fetchCaseStatus(normalized, pin)
      onFound(view, pin)
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card className="p-5 md:p-7 animate-fade-up">
      <form noValidate onSubmit={submit} className="space-y-5">
        <div>
          <label htmlFor="cs-case" className="block text-sm font-medium">
            {t('status.caseNumber')}
          </label>
          <input
            id="cs-case"
            value={caseNumber}
            onChange={(e) => setCaseNumber(e.target.value)}
            onBlur={() => {
              const n = normalizeCaseNumber(caseNumber)
              if (n) setCaseNumber(n)
            }}
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            placeholder="FMD-XXXX-XXXX"
            aria-invalid={Boolean(fieldErrors.caseNumber)}
            aria-describedby="cs-case-hint"
            className={cn(INPUT, 'mt-2 font-mono tracking-wider uppercase')}
          />
          <p id="cs-case-hint" className={cn('text-xs mt-1.5', fieldErrors.caseNumber ? 'text-rose-300' : 'text-subtle')}>
            {fieldErrors.caseNumber && <span aria-hidden className="font-bold mr-1">!</span>}
            {t('status.caseNumberHint')}
          </p>
        </div>
        <div>
          <label htmlFor="cs-pin" className="block text-sm font-medium">
            {t('status.pin')}
          </label>
          <div className="relative mt-2">
            <input
              id="cs-pin"
              ref={pinRef}
              type={showPin ? 'text' : 'password'}
              inputMode="numeric"
              autoComplete="off"
              maxLength={6}
              value={pin}
              onChange={(e) => setPin(e.target.value.replace(/\D/g, '').slice(0, 6))}
              aria-invalid={Boolean(fieldErrors.pin)}
              aria-describedby="cs-pin-hint"
              className={cn(INPUT, 'pr-12 font-mono tracking-[0.4em]')}
            />
            <button
              type="button"
              onClick={() => setShowPin((s) => !s)}
              aria-pressed={showPin}
              aria-label={showPin ? t('status.hidePin') : t('status.showPin')}
              className="absolute right-1.5 top-1/2 -translate-y-1/2 grid place-items-center size-8 rounded-lg text-muted hover:text-fg hover:bg-white/[0.06]"
            >
              {showPin ? <EyeOff aria-hidden className="size-4" /> : <Eye aria-hidden className="size-4" />}
            </button>
          </div>
          <p id="cs-pin-hint" className={cn('text-xs mt-1.5', fieldErrors.pin ? 'text-rose-300' : 'text-subtle')}>
            {fieldErrors.pin && <span aria-hidden className="font-bold mr-1">!</span>}
            {t('status.pinHint')}
          </p>
        </div>

        {error && <ErrorState error={error} />}
        {busy ? (
          <LoadingState label={t('loading.lookingUp')} className="py-4" />
        ) : (
          <button type="submit" className={buttonClass('primary', 'lg', 'w-full')}>
            <Search aria-hidden className="size-4" />
            {t('status.submit')}
          </button>
        )}
        <p className="text-xs text-subtle leading-relaxed">{t('status.lostPin')}</p>
      </form>
    </Card>
  )
}

function Timeline({ view }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.resolvedLanguage
  const entries = view.timeline || []
  const resolved = entries.length > 1
  return (
    <div>
      <h3 className="text-xs uppercase tracking-[0.16em] text-muted mb-3">{t('status.timeline.label')}</h3>
      <ol className="relative space-y-4 border-l border-line pl-5 ml-2">
        {entries.map((e) => (
          <li key={e.stage} className="relative">
            <span aria-hidden className="absolute -left-[27px] top-0.5 grid place-items-center size-3.5 rounded-full bg-brand ring-4 ring-canvas" />
            <p className="text-sm font-medium">
              <CircleCheck aria-hidden className="inline size-3.5 mr-1.5 -mt-0.5 text-emerald-300" />
              {t(`status.timeline.${e.stage}`)}
            </p>
            <p className="text-xs text-subtle mt-0.5">
              <time dateTime={e.at}>{formatWhen(e.at, lang)}</time>
            </p>
          </li>
        ))}
        {!resolved && (
          <li className="relative">
            <span aria-hidden className="absolute -left-[27px] top-0.5 size-3.5 rounded-full border-2 border-dashed border-line-strong bg-canvas ring-4 ring-canvas" />
            <p className="text-sm text-muted">
              <Hourglass aria-hidden className="inline size-3.5 mr-1.5 -mt-0.5" />
              {t('status.timeline.pending')}
            </p>
          </li>
        )}
      </ol>
    </div>
  )
}

function CaseView({ view, onRefresh, refreshing, refreshError, onAnother }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.resolvedLanguage
  const meta = STAGES[view.stage] || STAGES.IN_REVIEW
  const Icon = meta.icon
  const headingRef = useRef(null)
  const open = view.stage === 'IN_REVIEW'
  const overdue = open && view.deadline && new Date(view.deadline) < new Date()

  useEffect(() => headingRef.current?.focus(), [])

  return (
    <Card className="p-5 md:p-7 animate-fade-up">
      <div className="flex flex-wrap items-center justify-between gap-3 mb-5">
        <p className="font-mono text-sm tracking-wider text-muted">{view.caseNumber}</p>
        <div className="flex items-center gap-2">
          <span className="text-xs text-subtle">{t('verdict.automatedCheck')}:</span>
          <VerdictBadge verdict={view.verdict} size="sm" />
        </div>
      </div>

      <div className={cn('flex items-start gap-4 rounded-2xl border p-5', meta.tone)} aria-live="polite">
        <Icon aria-hidden className="size-7 shrink-0" />
        <div className="min-w-0">
          <h2 ref={headingRef} tabIndex={-1} className="text-xl font-semibold tracking-tight text-fg focus:outline-none">
            {t(`status.stages.${view.stage}.title`)}
          </h2>
          <p className="text-sm text-muted mt-1 leading-relaxed">{t(`status.stages.${view.stage}.body`)}</p>
        </div>
      </div>

      <div className="mt-5 space-y-2.5 text-sm">
        {view.deadline && open && (
          <p className="flex items-start gap-2 text-muted">
            <Timer aria-hidden className="size-4 mt-0.5 shrink-0" />
            {t('status.dueBy', { rule: view.rule, hours: view.deadlineHours, time: formatWhen(view.deadline, lang) })}
          </p>
        )}
        {overdue && (
          <p className="flex items-start gap-2 text-amber-200">
            <TriangleAlert aria-hidden className="size-4 mt-0.5 shrink-0" />
            {t('status.overdue')}
          </p>
        )}
        {!open && view.withinDeadline != null && (
          <p className="flex items-start gap-2 text-muted">
            {view.withinDeadline ? <CircleCheck aria-hidden className="size-4 mt-0.5 shrink-0" /> : <TriangleAlert aria-hidden className="size-4 mt-0.5 shrink-0" />}
            {view.withinDeadline ? t('status.onTime') : t('status.late')}
          </p>
        )}
        <p className="flex items-start gap-2 text-muted">
          {view.notifyChannel ? <Bell aria-hidden className="size-4 mt-0.5 shrink-0" /> : <BellOff aria-hidden className="size-4 mt-0.5 shrink-0" />}
          {view.notifyChannel ? t('status.updatesTo', { channel: t(`wizard.done.channel.${view.notifyChannel}`) }) : t('status.noUpdates')}
        </p>
      </div>

      <div className="mt-6 pt-6 border-t border-line">
        <Timeline view={view} />
      </div>

      {refreshError && <ErrorState error={refreshError} className="mt-5" />}

      <div className="mt-6 flex flex-wrap gap-2">
        <Button variant="secondary" icon={RefreshCw} onClick={onRefresh} loading={refreshing}>
          {t('status.refresh')}
        </Button>
        <Button variant="ghost" onClick={onAnother}>
          {t('status.another')}
        </Button>
        {view.stage === 'NO_ACTION' && (
          <Link to="/report" className={buttonClass('ghost')}>
            <FilePlus2 aria-hidden className="size-4" />
            {t('common.nav.report')}
          </Link>
        )}
      </div>
    </Card>
  )
}

export default function CaseStatus() {
  const { t } = useTranslation()
  const { caseNumber: param } = useParams()
  const navigate = useNavigate()
  // The demo tour passes the PIN in router state, never in the URL
  const location = useLocation()
  const [found, setFound] = useState(null)
  const [refreshing, setRefreshing] = useState(false)
  const [refreshError, setRefreshError] = useState(null)

  const onFound = (view, pin) => {
    setFound({ view, pin })
    setRefreshError(null)
    if (param !== view.caseNumber) navigate(`/status/${view.caseNumber}`, { replace: true })
  }

  const refresh = async () => {
    setRefreshing(true)
    setRefreshError(null)
    try {
      const view = await fetchCaseStatus(found.view.caseNumber, found.pin)
      setFound((f) => ({ ...f, view }))
    } catch (err) {
      setRefreshError(err)
    } finally {
      setRefreshing(false)
    }
  }

  const another = () => {
    setFound(null)
    navigate('/status', { replace: true })
  }

  return (
    <>
      <PageHeader
        eyebrow={t('status.eyebrow')}
        title={t('status.title')}
        description={t('status.description')}
      />
      <div className="max-w-2xl">
        {found ? (
          <CaseView view={found.view} onRefresh={refresh} refreshing={refreshing} refreshError={refreshError} onAnother={another} />
        ) : (
          <LookupForm key={param || ''} initialCase={normalizeCaseNumber(param) || ''} initialPin={location.state?.pin} onFound={onFound} />
        )}
      </div>
    </>
  )
}
