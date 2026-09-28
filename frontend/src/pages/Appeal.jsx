import { useEffect, useRef, useState } from 'react'
import { useLocation, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { CircleCheck, CircleSlash, Eye, EyeOff, FileText, Gavel, Hourglass, Lock, RefreshCw, Search, Send, Timer } from 'lucide-react'
import { fetchAppealStatus, fileAppeal } from '../api/client'
import { ErrorState, LoadingState } from '../components/Status'
import { Button, Card, PageHeader, buttonClass } from '../components/ui'
import { STATEMENT_MAX, appealStage, normalizeNoticeNumber } from '../lib/appeals'
import { formatWhen, isValidPin } from '../lib/cases'
import { cn } from '../lib/format'

const INPUT =
  'w-full rounded-xl border border-line bg-white/[0.03] px-3.5 py-2.5 text-sm placeholder:text-subtle focus:border-brand/60 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand/40'

const STAGES = {
  OPEN: { icon: Hourglass, tone: 'border-amber-400/30 bg-amber-400/[0.08] text-amber-300' },
  GRANTED_PENDING: { icon: Hourglass, tone: 'border-sky-400/30 bg-sky-400/[0.08] text-sky-300' },
  GRANTED: { icon: CircleCheck, tone: 'border-emerald-400/30 bg-emerald-400/[0.08] text-emerald-300' },
  UPHELD: { icon: CircleSlash, tone: 'border-line-strong bg-white/[0.03] text-muted' },
}

/** A 404 on this page always means "wrong notice number or PIN", so show that in the reader's language. */
function lookupError(err) {
  return err?.status === 404 ? Object.assign(new Error(err.message), { i18nKey: 'appeal.notFound' }) : err
}

function LookupForm({ initialNotice, initialPin, onFound }) {
  const { t } = useTranslation()
  const [notice, setNotice] = useState(initialNotice || '')
  const [pin, setPin] = useState(initialPin || '')
  const [showPin, setShowPin] = useState(false)
  const [fieldErrors, setFieldErrors] = useState({})
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const pinRef = useRef(null)

  useEffect(() => {
    if (initialNotice) pinRef.current?.focus()
  }, [initialNotice])

  const submit = async (e) => {
    e.preventDefault()
    const normalized = normalizeNoticeNumber(notice)
    const errs = {}
    if (!normalized) errs.notice = true
    if (!isValidPin(pin)) errs.pin = true
    setFieldErrors(errs)
    if (Object.keys(errs).length) return
    setBusy(true)
    setError(null)
    try {
      onFound(await fetchAppealStatus(normalized, pin), pin)
    } catch (err) {
      setError(lookupError(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card className="p-5 md:p-7 animate-fade-up">
      <form noValidate onSubmit={submit} className="space-y-5">
        <div>
          <label htmlFor="ap-notice" className="block text-sm font-medium">
            {t('appeal.noticeNumber')}
          </label>
          <input
            id="ap-notice"
            value={notice}
            onChange={(e) => setNotice(e.target.value)}
            onBlur={() => {
              const n = normalizeNoticeNumber(notice)
              if (n) setNotice(n)
            }}
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            placeholder="UPL-XXXX-XXXX"
            aria-invalid={Boolean(fieldErrors.notice)}
            aria-describedby="ap-notice-hint"
            className={cn(INPUT, 'mt-2 font-mono tracking-wider uppercase')}
          />
          <p id="ap-notice-hint" className={cn('text-xs mt-1.5', fieldErrors.notice ? 'text-rose-300' : 'text-subtle')}>
            {fieldErrors.notice && <span aria-hidden className="font-bold mr-1">!</span>}
            {t('appeal.noticeHint')}
          </p>
        </div>
        <div>
          <label htmlFor="ap-pin" className="block text-sm font-medium">
            {t('appeal.pin')}
          </label>
          <div className="relative mt-2">
            <input
              id="ap-pin"
              ref={pinRef}
              type={showPin ? 'text' : 'password'}
              inputMode="numeric"
              autoComplete="off"
              maxLength={6}
              value={pin}
              onChange={(e) => setPin(e.target.value.replace(/\D/g, '').slice(0, 6))}
              aria-invalid={Boolean(fieldErrors.pin)}
              aria-describedby="ap-pin-hint"
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
          <p id="ap-pin-hint" className={cn('text-xs mt-1.5', fieldErrors.pin ? 'text-rose-300' : 'text-subtle')}>
            {fieldErrors.pin && <span aria-hidden className="font-bold mr-1">!</span>}
            {t('appeal.pinHint')}
          </p>
        </div>

        {error && <ErrorState error={error} />}
        {busy ? (
          <LoadingState label={t('loading.lookingUp')} className="py-4" />
        ) : (
          <button type="submit" className={buttonClass('primary', 'lg', 'w-full')}>
            <Search aria-hidden className="size-4" />
            {t('appeal.lookUp')}
          </button>
        )}
        <p className="flex items-start gap-2 text-xs text-subtle leading-relaxed">
          <Lock aria-hidden className="size-3.5 mt-0.5 shrink-0" />
          {t('appeal.privacy')}
        </p>
      </form>
    </Card>
  )
}

function StatementForm({ view, pin, onFiled }) {
  const { t } = useTranslation()
  const [statement, setStatement] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await fileAppeal({ noticeNumber: view.noticeNumber, pin, statement })
      onFiled()
    } catch (err) {
      setError(lookupError(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form noValidate onSubmit={submit} className="space-y-3">
      <label htmlFor="ap-statement" className="block text-sm font-medium">
        {t('appeal.statement')}
      </label>
      <textarea
        id="ap-statement"
        rows={5}
        maxLength={STATEMENT_MAX}
        value={statement}
        onChange={(e) => setStatement(e.target.value)}
        aria-describedby="ap-statement-hint"
        className={cn(INPUT, 'resize-y')}
      />
      <p id="ap-statement-hint" className="flex justify-between gap-3 text-xs text-subtle">
        <span>{t('appeal.statementHint')}</span>
        <span className="tabular-nums shrink-0">{t('appeal.chars', { count: statement.length, max: STATEMENT_MAX })}</span>
      </p>
      {error && <ErrorState error={error} />}
      <Button type="submit" size="lg" icon={Send} loading={busy} disabled={!statement.trim()} className="w-full">
        {t('appeal.submit')}
      </Button>
    </form>
  )
}

/** DSA Art. 17 style statement of reasons; the backend sends codes and this words them. */
function StatementOfReasons({ sor }) {
  const { t } = useTranslation()
  const how = sor.decidedAutomatically ? 'howAutomatic' : sor.detectedAutomatically ? 'howConfirmed' : 'howModerator'
  const basis =
    sor.basis === 'LEGAL_COMPLAINT'
      ? sor.legalReference
        ? t('appeal.sor.basisLegalRef', { reference: sor.legalReference })
        : t('appeal.sor.basisLegal')
      : t('appeal.sor.basisPolicy')
  return (
    <section aria-labelledby="ap-sor" className="rounded-2xl border border-line bg-white/[0.02] p-4 md:p-5 mb-5">
      <h2 id="ap-sor" className="flex items-center gap-2 text-sm font-semibold">
        <FileText aria-hidden className="size-4 text-brand" />
        {t('appeal.sor.title')}
      </h2>
      <dl className="mt-3 grid gap-3 text-sm sm:grid-cols-[10rem_1fr]">
        <dt className="text-subtle">{t('appeal.sor.grounds')}</dt>
        <dd>
          <ul className="space-y-0.5">
            {(sor.grounds || []).map((g) => (
              <li key={g}>{t(`appeal.sor.ground.${g}`)}</li>
            ))}
          </ul>
        </dd>
        <dt className="text-subtle">{t('appeal.sor.basis')}</dt>
        <dd>{basis}</dd>
        <dt className="text-subtle">{t('appeal.sor.how')}</dt>
        <dd>{t(`appeal.sor.${how}`)}</dd>
        <dt className="text-subtle">{t('appeal.sor.redress')}</dt>
        <dd>
          <ul className="list-disc pl-4 space-y-0.5 text-muted">
            {(sor.redress || []).map((r) => (
              <li key={r}>{t(`appeal.sor.redressOption.${r}`)}</li>
            ))}
          </ul>
        </dd>
      </dl>
    </section>
  )
}

function AppealView({ view, pin, onRefresh, refreshing, refreshError, onAnother }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.resolvedLanguage
  const stage = appealStage(view.appeal)
  const meta = stage ? STAGES[stage] : null
  const Icon = meta?.icon
  const headingRef = useRef(null)

  useEffect(() => headingRef.current?.focus(), [stage])

  return (
    <Card className="p-5 md:p-7 animate-fade-up">
      <div className="flex flex-wrap items-center justify-between gap-3 mb-5">
        <p className="font-mono text-sm tracking-wider text-muted">{view.noticeNumber}</p>
        <p className="text-xs text-subtle">
          {t(`appeal.decision.${view.decision}`)} · {t('appeal.analyzedAt', { time: formatWhen(view.analyzedAt, lang) })}
        </p>
      </div>

      {view.statementOfReasons && <StatementOfReasons sor={view.statementOfReasons} />}

      {stage ? (
        <>
          <div className={cn('flex items-start gap-4 rounded-2xl border p-5', meta.tone)} aria-live="polite">
            <Icon aria-hidden className="size-7 shrink-0" />
            <div className="min-w-0">
              <h2 ref={headingRef} tabIndex={-1} className="text-xl font-semibold tracking-tight text-fg focus:outline-none">
                {t(`appeal.stages.${stage}.title`)}
              </h2>
              <p className="text-sm text-muted mt-1 leading-relaxed">{t(`appeal.stages.${stage}.body`)}</p>
            </div>
          </div>
          <div className="mt-5 space-y-2.5 text-sm text-muted">
            <p className="font-mono text-xs">{t('appeal.caseNumber', { number: view.appeal.caseNumber })}</p>
            {stage === 'OPEN' ? (
              <p className="flex items-start gap-2">
                <Timer aria-hidden className="size-4 mt-0.5 shrink-0" />
                {t('appeal.dueBy', { rule: view.appeal.rule, time: formatWhen(view.appeal.decisionDueBy, lang) })}
              </p>
            ) : (
              view.appeal.decidedAt && <p>{t('appeal.decidedAt', { time: formatWhen(view.appeal.decidedAt, lang) })}</p>
            )}
            {view.appeal.reason && (
              <div className="rounded-xl border border-line bg-white/[0.02] px-3.5 py-3">
                <p className="text-xs text-subtle">{t('appeal.reason')}</p>
                <p className="text-fg mt-1 whitespace-pre-line">{view.appeal.reason}</p>
              </div>
            )}
          </div>
        </>
      ) : view.appealable ? (
        <>
          <h2 ref={headingRef} tabIndex={-1} className="flex items-center gap-2 text-lg font-semibold tracking-tight mb-4 focus:outline-none">
            <Gavel aria-hidden className="size-5 text-brand" />
            {t('appeal.title')}
          </h2>
          <StatementForm view={view} pin={pin} onFiled={onRefresh} />
        </>
      ) : (
        <p ref={headingRef} tabIndex={-1} className="text-sm text-muted focus:outline-none">
          {t(view.changePending ? 'appeal.changePending' : 'appeal.notAppealable')}
        </p>
      )}

      {refreshError && <ErrorState error={refreshError} className="mt-5" />}

      <div className="mt-6 flex flex-wrap gap-2">
        {stage && (
          <Button variant="secondary" icon={RefreshCw} onClick={onRefresh} loading={refreshing}>
            {t('appeal.refresh')}
          </Button>
        )}
        <Button variant="ghost" onClick={onAnother}>
          {t('appeal.another')}
        </Button>
      </div>
    </Card>
  )
}

export default function Appeal() {
  const { t } = useTranslation()
  const [params, setParams] = useSearchParams()
  // The demo tour passes the PIN in router state, never in the URL
  const location = useLocation()
  const [found, setFound] = useState(null)
  const [refreshing, setRefreshing] = useState(false)
  const [refreshError, setRefreshError] = useState(null)
  const initialNotice = normalizeNoticeNumber(params.get('n')) || ''

  const refresh = async () => {
    setRefreshing(true)
    setRefreshError(null)
    try {
      const view = await fetchAppealStatus(found.view.noticeNumber, found.pin)
      setFound((f) => ({ ...f, view }))
    } catch (err) {
      setRefreshError(lookupError(err))
    } finally {
      setRefreshing(false)
    }
  }

  return (
    <>
      <PageHeader eyebrow={t('appeal.eyebrow')} title={t('appeal.title')} description={t('appeal.description')} />
      <div className="max-w-2xl">
        {found ? (
          <AppealView
            view={found.view}
            pin={found.pin}
            onRefresh={refresh}
            refreshing={refreshing}
            refreshError={refreshError}
            onAnother={() => {
              setFound(null)
              setParams({}, { replace: true })
            }}
          />
        ) : (
          <LookupForm
            key={initialNotice}
            initialNotice={initialNotice}
            initialPin={location.state?.pin}
            onFound={(view, pin) => {
              setFound({ view, pin })
              setRefreshError(null)
            }}
          />
        )}
      </div>
    </>
  )
}
