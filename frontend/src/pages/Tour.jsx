import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  ArrowRight,
  BadgeCheck,
  ExternalLink,
  FileLock2,
  FlaskConical,
  Gavel,
  Inbox,
  LogIn,
  Radar,
  RotateCcw,
  ScanSearch,
  ShieldAlert,
  Siren,
  Sparkles,
  TimerReset,
} from 'lucide-react'
import { resetDemo, startDemoStory, verifyReport } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { ErrorState, LoadingState } from '../components/Status'
import { Button, Card, CopyButton, DecisionBadge, EmptyState, PageHeader, buttonClass } from '../components/ui'
import { useDemo } from '../context/DemoContext'
import { formatWhen } from '../lib/cases'
import { canReset, secondsUntil, spacedPin, storyComplete } from '../lib/demo'
import { cn, pct } from '../lib/format'
import { SLA_STATUS, humanDuration } from '../lib/sla'

const TICK_MS = 15000
const BUSY_RETRIES = 10
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function useNow() {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), TICK_MS)
    return () => clearInterval(timer)
  }, [])
  return now
}

function SlaChip({ status, deadline, now }) {
  const { t } = useTranslation()
  const meta = SLA_STATUS[status]
  if (!meta) return null
  const Icon = meta.icon
  const left = secondsUntil(deadline, now)
  return (
    <span className={cn('inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium', meta.ring, meta.text)}>
      <Icon aria-hidden className="size-3.5" />
      {t(`demo.sla.${status}`)}
      {left != null && (
        <span className="font-normal opacity-80">
          · {left > 0 ? t('demo.sla.dueIn', { time: humanDuration(left) }) : t('demo.sla.overdue', { time: humanDuration(left) })}
        </span>
      )}
    </span>
  )
}

function Field({ label, children, mono }) {
  return (
    <div className="min-w-0">
      <dt className="text-[11px] uppercase tracking-[0.12em] text-subtle">{label}</dt>
      <dd className={cn('mt-0.5 text-sm flex items-center gap-1.5 min-w-0', mono && 'font-mono tracking-wide')}>{children}</dd>
    </div>
  )
}

function Step({ n, icon: Icon, accent, title, body, children, actions }) {
  return (
    <li className="relative pl-14">
      <span className={cn('absolute left-0 top-0 grid place-items-center size-10 rounded-2xl border', accent)}>
        <Icon aria-hidden className="size-5" />
        <span className="absolute -top-2 -right-2 grid place-items-center size-5 rounded-full bg-brand-gradient text-[10px] font-bold text-[#fff]">
          {n}
        </span>
      </span>
      <Card className="p-5 md:p-6">
        <h2 className="text-[17px] font-semibold tracking-tight">{title}</h2>
        <p className="mt-1 text-sm text-muted leading-relaxed">{body}</p>
        {children && <dl className="mt-4 grid grid-cols-2 md:grid-cols-4 gap-4">{children}</dl>}
        {actions && <div className="mt-5 flex flex-wrap items-center gap-2">{actions}</div>}
      </Card>
    </li>
  )
}

function ReportCheck({ reportId }) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const check = async () => {
    setBusy(true)
    setError(null)
    try {
      setResult(await verifyReport(reportId))
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }
  return (
    <>
      <Button variant="secondary" size="sm" icon={BadgeCheck} loading={busy} onClick={check}>
        {busy ? t('demo.steps.report.checking') : t('demo.steps.report.check')}
      </Button>
      {result && (
        <p role="status" className={cn('w-full text-sm', result.status === 'VALID' ? 'text-emerald-300' : 'text-amber-200')}>
          {t(`demo.steps.report.result.${result.status}`, { defaultValue: result.message })}
          {result.keyId && <span className="text-subtle"> · {t('demo.steps.report.key', { key: result.keyId })}</span>}
        </p>
      )}
      {error && <ErrorState error={error} className="w-full" />}
    </>
  )
}

function Story({ story, now, isAuthenticated }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const { flagged, appeal, report, atRisk, breached, investigation, relatedInvestigation } = story
  const staffLink = (to) => (isAuthenticated ? { to } : { to: '/login', state: { from: to } })

  return (
    <ol className="space-y-5">
      <Step
        n={1}
        icon={ShieldAlert}
        accent="border-rose-400/30 bg-rose-400/10 text-rose-300"
        title={t('demo.steps.flagged.title')}
        body={t('demo.steps.flagged.body', { moderator: flagged.decidedBy })}
        actions={
          <>
            <Link to={`/verify/${flagged.provenanceId}`} className={buttonClass('secondary', 'sm')}>
              {t('demo.steps.flagged.trail')}
              <ArrowRight aria-hidden className="size-3.5" />
            </Link>
            <Link {...staffLink('/queue')} className={buttonClass('ghost', 'sm')}>
              <Inbox aria-hidden className="size-3.5" />
              {t('demo.steps.flagged.queue')}
            </Link>
          </>
        }
      >
        <Field label={t('demo.steps.flagged.model')}>
          {flagged.modelDecision ? <DecisionBadge decision={flagged.modelDecision} size="sm" /> : '—'}
        </Field>
        <Field label={t('demo.steps.flagged.final')}>
          <DecisionBadge decision={flagged.decision} size="sm" />
        </Field>
        <Field label={t('demo.steps.flagged.forgery')}>{pct(flagged.forgeryScore)}</Field>
        <Field label={t('demo.steps.flagged.explicit')}>{pct(flagged.explicitScore)}</Field>
        <div className="col-span-2 md:col-span-4">
          <Field label={t('demo.steps.flagged.reason')}>
            <span className="text-muted">{flagged.reason}</span>
          </Field>
        </div>
      </Step>

      {appeal && (
        <Step
          n={2}
          icon={Gavel}
          accent="border-amber-400/30 bg-amber-400/10 text-amber-300"
          title={t('demo.steps.appeal.title')}
          body={t('demo.steps.appeal.body')}
          actions={
            <>
              <Link to={`/appeal?n=${encodeURIComponent(appeal.caseNumber)}`} state={{ pin: appeal.pin }} className={buttonClass('secondary', 'sm')}>
                {t('demo.steps.appeal.open')}
                <ArrowRight aria-hidden className="size-3.5" />
              </Link>
              <Link {...staffLink('/queue')} className={buttonClass('ghost', 'sm')}>
                <Gavel aria-hidden className="size-3.5" />
                {t('demo.steps.appeal.decide')}
              </Link>
            </>
          }
        >
          <Field label={t('demo.steps.appeal.notice')} mono>
            {appeal.caseNumber}
            <CopyButton value={appeal.caseNumber} />
          </Field>
          <Field label={t('demo.steps.appeal.pin')} mono>
            {spacedPin(appeal.pin)}
            <CopyButton value={appeal.pin} />
          </Field>
          <Field label={t('demo.steps.appeal.status')}>{t(`demo.appealStatus.${appeal.status}`, { defaultValue: appeal.status })}</Field>
          <Field label={t('demo.steps.appeal.due')}>{formatWhen(appeal.slaDeadline, lang)}</Field>
        </Step>
      )}

      {report && (
        <Step
          n={3}
          icon={FileLock2}
          accent="border-emerald-400/30 bg-emerald-400/10 text-emerald-300"
          title={t('demo.steps.report.title')}
          body={t('demo.steps.report.body')}
          actions={
            <>
              <a href={report.pdfUrl} target="_blank" rel="noreferrer" className={buttonClass('secondary', 'sm')}>
                {t('demo.steps.report.open')}
                <ExternalLink aria-hidden className="size-3.5" />
              </a>
              <ReportCheck reportId={report.reportId} />
            </>
          }
        >
          <div className="col-span-2">
            <Field label={t('demo.steps.report.id')} mono>
              <span className="truncate">{report.reportId}</span>
              <CopyButton value={report.reportId} />
            </Field>
          </div>
          <div className="col-span-2">
            <Field label="SHA-256" mono>
              <span className="truncate" title={report.sha256}>
                {report.sha256.slice(0, 16)}…
              </span>
            </Field>
          </div>
        </Step>
      )}

      {atRisk && (
        <Step
          n={4}
          icon={TimerReset}
          accent="border-amber-400/30 bg-amber-400/10 text-amber-300"
          title={t('demo.steps.atRisk.title')}
          body={t('demo.steps.atRisk.body')}
          actions={
            <>
              <Link to={`/status/${atRisk.caseNumber}`} state={{ pin: atRisk.pin }} className={buttonClass('secondary', 'sm')}>
                {t('demo.steps.atRisk.track')}
                <ArrowRight aria-hidden className="size-3.5" />
              </Link>
              <Link {...staffLink('/compliance')} className={buttonClass('ghost', 'sm')}>
                {t('demo.steps.breached.console')}
              </Link>
            </>
          }
        >
          <Field label={t('demo.steps.atRisk.case')} mono>
            {atRisk.caseNumber}
            <CopyButton value={atRisk.caseNumber} />
          </Field>
          <Field label={t('demo.steps.appeal.pin')} mono>
            {spacedPin(atRisk.pin)}
            <CopyButton value={atRisk.pin} />
          </Field>
          <div className="col-span-2">
            <Field label={t('demo.steps.atRisk.clock')}>
              <SlaChip status={atRisk.slaStatus} deadline={atRisk.resolvedAt ? null : atRisk.slaDeadline} now={now} />
            </Field>
          </div>
        </Step>
      )}

      {breached && (
        <Step
          n={5}
          icon={Siren}
          accent="border-rose-400/30 bg-rose-400/10 text-rose-300"
          title={t('demo.steps.breached.title')}
          body={t('demo.steps.breached.body')}
          actions={
            <>
              <Link {...staffLink('/compliance')} className={buttonClass('secondary', 'sm')}>
                {t('demo.steps.breached.console')}
                <ArrowRight aria-hidden className="size-3.5" />
              </Link>
              <Link to={`/verify/${breached.provenanceId}`} className={buttonClass('ghost', 'sm')}>
                {t('demo.steps.flagged.trail')}
              </Link>
            </>
          }
        >
          <div className="col-span-2">
            <Field label={t('demo.steps.breached.reference')} mono>
              {breached.reference}
            </Field>
          </div>
          <div className="col-span-2">
            <Field label={t('demo.steps.atRisk.clock')}>
              <SlaChip status={breached.slaStatus} deadline={breached.resolvedAt ? null : breached.slaDeadline} now={now} />
            </Field>
          </div>
        </Step>
      )}

      {investigation && (
        <Step
          n={6}
          icon={Radar}
          accent="border-sky-400/30 bg-sky-400/10 text-sky-300"
          title={t('demo.steps.investigation.title')}
          body={t('demo.steps.investigation.body', { sightings: investigation.sightings, platforms: investigation.platforms })}
          actions={
            <Link {...staffLink(`/investigations/${investigation.caseNumber}`)} className={buttonClass('secondary', 'sm')}>
              {t('demo.steps.investigation.open')}
              <ArrowRight aria-hidden className="size-3.5" />
            </Link>
          }
        >
          <div className="col-span-2">
            <Field label={t('demo.steps.investigation.case')} mono>
              {investigation.caseNumber}
              <CopyButton value={investigation.caseNumber} />
            </Field>
          </div>
          {relatedInvestigation && (
            <div className="col-span-2">
              <Field label={t('demo.steps.investigation.related')} mono>
                {relatedInvestigation.caseNumber}
              </Field>
            </div>
          )}
          {investigation.preservations > 0 && (
            <div className="col-span-2">
              <Field label={t('demo.steps.investigation.custody')}>
                {t('demo.steps.investigation.custodyValue', { preservations: investigation.preservations, holds: investigation.activeLegalHolds })}
              </Field>
            </div>
          )}
        </Step>
      )}
    </ol>
  )
}

function Accounts({ demo }) {
  const { t } = useTranslation()
  const { login } = useAuth()
  const navigate = useNavigate()
  const [busy, setBusy] = useState(null)
  const [error, setError] = useState(null)
  const accounts = (demo.accounts || []).filter((a) => a.role === 'MODERATOR' || a.role === 'SENIOR_MODERATOR')
  const withPasswords = accounts.filter((a) => a.password)

  const signIn = async (account) => {
    setBusy(account.username)
    setError(null)
    try {
      await login(account.username, account.password)
      navigate('/queue')
    } catch (err) {
      setError(err)
      setBusy(null)
    }
  }

  return (
    <Card className="p-5 md:p-6">
      <div className="flex items-center gap-2">
        <LogIn aria-hidden className="size-5 text-brand" />
        <h2 className="text-[17px] font-semibold tracking-tight">{t('demo.moderator.title')}</h2>
      </div>
      <p className="mt-1 text-sm text-muted leading-relaxed">{t('demo.moderator.body')}</p>
      {withPasswords.length > 0 ? (
        <>
          <p className="mt-2 text-xs text-subtle">{t('demo.moderator.publicBody')}</p>
          <div className="mt-4 flex flex-wrap gap-2">
            {withPasswords.map((a) => (
              <Button
                key={a.username}
                variant={a.role === 'SENIOR_MODERATOR' ? 'secondary' : 'primary'}
                size="sm"
                loading={busy === a.username}
                disabled={Boolean(busy)}
                onClick={() => signIn(a)}
              >
                {t('demo.moderator.signInAs', { user: a.username })}
                <span className="opacity-70 font-normal">· {t(`demo.moderator.roles.${a.role}`)}</span>
              </Button>
            ))}
          </div>
        </>
      ) : (
        <>
          <ul className="mt-3 flex flex-wrap gap-2 text-xs">
            {accounts.map((a) => (
              <li key={a.username} className="rounded-lg border border-line px-2.5 py-1 font-mono">
                {a.username} <span className="font-sans text-subtle">· {t(`demo.moderator.roles.${a.role}`)}</span>
              </li>
            ))}
          </ul>
          <p className="mt-3 text-xs text-subtle">{t('demo.moderator.passwordHint')}</p>
          <Link to="/login" state={{ from: '/queue' }} className={buttonClass('primary', 'sm', 'mt-4')}>
            <LogIn aria-hidden className="size-3.5" />
            {t('demo.moderator.signIn')}
          </Link>
        </>
      )}
      {error && <ErrorState error={error} className="mt-4" />}
    </Card>
  )
}

export default function Tour() {
  const { t, i18n } = useTranslation()
  const { demo, setDemo } = useDemo()
  const { user, isAuthenticated } = useAuth()
  const now = useNow()
  const [seeding, setSeeding] = useState(false)
  const [error, setError] = useState(null)
  const [resetting, setResetting] = useState(false)
  const [resetError, setResetError] = useState(null)
  const started = useRef(false)
  const story = demo?.story

  // One click: arriving here is enough; the first visitor's request seeds the story for everyone
  useEffect(() => {
    if (!demo?.enabled || storyComplete(demo.story) || started.current) return
    started.current = true
    let cancelled = false
    const seed = async () => {
      setSeeding(true)
      setError(null)
      for (let attempt = 1; attempt <= BUSY_RETRIES; attempt++) {
        try {
          const next = await startDemoStory()
          if (!cancelled) setDemo(next)
          break
        } catch (err) {
          if (err.code === 'demo_busy' && attempt < BUSY_RETRIES) {
            await sleep(3000)
            continue
          }
          if (!cancelled) setError(err)
          break
        }
      }
      if (!cancelled) setSeeding(false)
    }
    seed()
    return () => {
      cancelled = true
    }
  }, [demo, setDemo])

  const retry = () => {
    started.current = false
    setError(null)
    setDemo((d) => ({ ...d }))
  }

  const reset = async () => {
    setResetting(true)
    setResetError(null)
    try {
      setDemo(await resetDemo())
    } catch (err) {
      setResetError(err)
    } finally {
      setResetting(false)
    }
  }

  let content
  if (!demo) {
    content = <LoadingState />
  } else if (!demo.enabled) {
    content = (
      <EmptyState icon={FlaskConical} title={t('demo.disabled.title')} description={t('demo.disabled.body')}>
        <Link to="/analyze" className={buttonClass('primary', 'md')}>
          <ScanSearch aria-hidden className="size-4" />
          {t('demo.disabled.cta')}
        </Link>
      </EmptyState>
    )
  } else if (error) {
    content = <ErrorState error={error} title={t('demo.failed')} onRetry={retry} />
  } else if (!story?.flagged) {
    content = (
      <Card>
        <LoadingState label={seeding ? t('demo.seeding') : undefined} className="py-16" />
      </Card>
    )
  } else {
    content = (
      <div className="grid lg:grid-cols-[minmax(0,1fr)_320px] gap-6 items-start">
        <Story story={story} now={now} isAuthenticated={isAuthenticated} />
        <aside className="space-y-5 lg:sticky lg:top-24">
          <Accounts demo={demo} />
          <Card className="p-5 md:p-6">
            <div className="flex items-center gap-2">
              <Sparkles aria-hidden className="size-5 text-brand" />
              <h2 className="text-[17px] font-semibold tracking-tight">{t('demo.tryIt.title')}</h2>
            </div>
            <p className="mt-1 text-sm text-muted leading-relaxed">{t('demo.tryIt.body')}</p>
            <Link to="/analyze" className={buttonClass('secondary', 'sm', 'mt-4')}>
              <ScanSearch aria-hidden className="size-3.5" />
              {t('demo.tryIt.cta')}
            </Link>
          </Card>
          {canReset(demo, user) && (
            <Card className="p-5 md:p-6">
              <p className="text-xs text-subtle">{t('demo.seededAt', { time: formatWhen(story.createdAt, i18n.language) })}</p>
              <Button variant="ghost" size="sm" icon={RotateCcw} loading={resetting} onClick={reset} className="mt-3">
                {resetting ? t('demo.resetting') : t('demo.reset')}
              </Button>
              {resetError && <ErrorState error={resetError} className="mt-3" />}
            </Card>
          )}
        </aside>
      </div>
    )
  }

  return (
    <>
      <PageHeader eyebrow={t('demo.eyebrow')} title={t('demo.title')} description={t('demo.subtitle')} />
      {demo?.enabled && (
        <p className="-mt-4 mb-6 flex items-start gap-2 text-xs text-subtle max-w-3xl">
          <FlaskConical aria-hidden className="size-3.5 mt-0.5 shrink-0 text-brand" />
          {t('demo.synthetic')}
        </p>
      )}
      {content}
    </>
  )
}
