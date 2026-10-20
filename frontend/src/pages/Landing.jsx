import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  AlarmClock,
  ArrowRight,
  BadgeCheck,
  BookOpen,
  Check,
  CircleDashed,
  Code2,
  Cpu,
  EyeOff,
  FileLock2,
  FilePlus2,
  Fingerprint,
  Gauge,
  Gavel,
  ImageUp,
  Inbox,
  LockKeyhole,
  ScanSearch,
  Search,
  ShieldCheck,
  Stamp,
  Undo2,
  UserCheck,
  X,
} from 'lucide-react'
import { useAuth } from '../auth/AuthContext'
import VerdictBadge from '../components/VerdictBadge'
import { Card, buttonClass } from '../components/ui'
import { useStats } from '../context/StatsContext'
import { UNDO_MS } from '../context/DecisionContext'
import { cn } from '../lib/format'

const STEPS = [
  { key: 'in', icon: ImageUp },
  { key: 'check', icon: ScanSearch },
  { key: 'route', icon: Gavel },
  { key: 'act', icon: AlarmClock },
  { key: 'seal', icon: Stamp },
]

/** yes / partial / no per column: this platform, detection-only, hash-only, manual-only. */
const COMPARISON = [
  ['newFakes', 'yes', 'yes', 'no', 'partial'],
  ['known', 'yes', 'no', 'yes', 'partial'],
  ['routing', 'yes', 'no', 'no', 'no'],
  ['deadline', 'yes', 'no', 'no', 'partial'],
  ['evidence', 'yes', 'no', 'no', 'partial'],
  ['privacy', 'yes', 'partial', 'yes', 'no'],
  ['reporting', 'yes', 'no', 'partial', 'partial'],
]

const MARK = {
  yes: { icon: Check, cls: 'bg-emerald-400/15 text-emerald-300 border-emerald-400/30' },
  partial: { icon: CircleDashed, cls: 'bg-amber-400/10 text-amber-300 border-amber-400/25' },
  no: { icon: X, cls: 'bg-white/[0.03] text-subtle border-line' },
}

function useLatency() {
  const [p50, setP50] = useState(null)
  useEffect(() => {
    let cancelled = false
    fetch('/metrics.json')
      .then((r) => (r.ok ? r.json() : null))
      .then((m) => !cancelled && m?.latency?.p50_ms && setP50(Math.round(m.latency.p50_ms)))
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [])
  return p50
}

function SectionHeading({ eyebrow, title, subtitle, id }) {
  return (
    <div className="max-w-2xl mb-8">
      {eyebrow && (
        <p className="label-caps inline-flex items-center gap-2 text-brand mb-3">
          <span aria-hidden className="size-1.5 rounded-full bg-brand" />
          {eyebrow}
        </p>
      )}
      <h2 id={id} className="font-serif text-[30px] md:text-[40px] leading-[1.22] font-medium tracking-[-0.012em]">
        {title}
      </h2>
      {subtitle && <p className="text-muted mt-4 text-[16px] leading-[1.7] max-w-prose">{subtitle}</p>}
    </div>
  )
}

/** Decorative: how a reported item looks to a moderator. The live screens carry the real data. */
function HeroPreview() {
  const { t } = useTranslation()
  return (
    <div aria-hidden className="relative">
      <div className="absolute -inset-y-8 inset-x-0 sm:-inset-x-8 rounded-[40px] bg-white opacity-[0.04] blur-3xl" />
      <Card className="relative overflow-hidden p-0 shadow-[0_30px_80px_-40px_rgb(0_0_0/0.8)]">
        <div className="flex items-center gap-1.5 h-10 px-4 border-b border-line bg-surface-2">
          <span className="size-2.5 rounded-full bg-white/15" />
          <span className="size-2.5 rounded-full bg-white/15" />
          <span className="size-2.5 rounded-full bg-white/15" />
          <span className="mx-auto flex items-center gap-1.5 rounded-md border border-line bg-canvas px-3 py-0.5 font-mono text-[10.5px] text-subtle">
            <LockKeyhole className="size-3" /> console / queue
          </span>
          <span className="w-10" />
        </div>
        <div className="p-5">
          <p className="label-caps mb-4">{t('landing.preview.label')}</p>
          <div className="flex gap-4">
            <div className="relative size-24 shrink-0 rounded-xl overflow-hidden border border-line">
              <div className="absolute -inset-2 blur-lg bg-[radial-gradient(circle_at_30%_30%,#8a8a8a,transparent_60%),radial-gradient(circle_at_70%_70%,#4a4a4a,transparent_60%),#1c1c1c]" />
              <div className="absolute inset-0 grid place-items-center bg-black/25">
                <EyeOff className="size-5 text-[#fff]/70" />
              </div>
            </div>
            <div className="min-w-0 space-y-2">
              <p className="text-xs text-muted">{t('landing.preview.item')}</p>
              <VerdictBadge verdict="LIKELY_FORGED" />
              <div className="flex flex-wrap gap-1.5">
                <span className="inline-flex items-center gap-1.5 rounded-full border border-amber-400/35 bg-amber-400/[0.08] px-2 py-0.5 text-[11px] font-medium text-amber-200">
                  <AlarmClock className="size-3" />
                  {t('landing.preview.deadline')}
                </span>
                <span className="inline-flex items-center rounded-full border border-sky-400/30 bg-sky-400/[0.07] px-2 py-0.5 text-[11px] text-sky-200">
                  {t('landing.preview.rule')}
                </span>
              </div>
            </div>
          </div>
          <div className="mt-5 space-y-2">
            {[
              { icon: FileLock2, text: t('landing.preview.sealed') },
              { icon: UserCheck, text: t('landing.preview.human') },
            ].map(({ icon: Icon, text }) => (
              <div key={text} className="flex items-center gap-2.5 rounded-lg border border-line bg-surface-2 px-3 py-2 text-[13px]">
                <Icon className="size-4 text-emerald-300" />
                {text}
                <BadgeCheck className="size-4 ml-auto text-emerald-300" />
              </div>
            ))}
          </div>
        </div>
      </Card>
    </div>
  )
}

function PathCard({ icon: Icon, title, body, to, cta, secondary, accent }) {
  return (
    <Card className="group p-6 flex flex-col transition-colors hover:border-line-strong">
      <span className={cn('grid place-items-center size-11 rounded-xl border', accent)}>
        <Icon aria-hidden className="size-5" />
      </span>
      <h3 className="mt-5 font-serif text-[20px] leading-snug font-medium tracking-[-0.01em]">{title}</h3>
      <p className="mt-2.5 flex-1 text-[15px] text-muted leading-[1.7]">{body}</p>
      <div className="mt-5 flex flex-wrap items-center gap-x-4 gap-y-2">
        <Link to={to} className={buttonClass('secondary', 'sm')}>
          {cta}
          <ArrowRight aria-hidden className="size-3.5 transition-transform group-hover:translate-x-0.5" />
        </Link>
        {secondary && (
          <Link to={secondary.to} className="text-xs font-medium text-brand hover:underline underline-offset-4">
            {secondary.label}
          </Link>
        )}
      </div>
    </Card>
  )
}

function Mark({ value }) {
  const { t } = useTranslation()
  const m = MARK[value]
  const Icon = m.icon
  return (
    <span className="inline-flex items-center justify-center" title={t(`landing.different.${value}`)}>
      <span className={cn('grid place-items-center size-7 rounded-full border', m.cls)}>
        <Icon aria-hidden className="size-3.5" />
      </span>
      <span className="sr-only">{t(`landing.different.${value}`)}</span>
    </span>
  )
}

export default function Landing() {
  const { t } = useTranslation()
  const { isAuthenticated } = useAuth()
  const { stats } = useStats()
  const latency = useLatency()

  const paths = [
    {
      key: 'victim',
      icon: FilePlus2,
      to: '/report',
      accent: 'border-rose-400/30 bg-rose-400/10 text-rose-300',
      secondary: { to: '/status', label: t('landing.paths.victim.secondary') },
    },
    {
      key: 'checker',
      icon: ScanSearch,
      to: '/analyze',
      accent: 'border-brand/30 bg-brand/10 text-brand',
      secondary: { to: '/analyze#prescreen', label: t('landing.paths.checker.secondary') },
    },
    {
      key: 'moderator',
      icon: Inbox,
      to: isAuthenticated ? '/queue' : '/login',
      accent: 'border-amber-400/30 bg-amber-400/10 text-amber-300',
      cta: isAuthenticated ? t('landing.paths.moderator.ctaSignedIn') : undefined,
    },
    { key: 'platform', icon: Code2, to: '/api-access', accent: 'border-sky-400/30 bg-sky-400/10 text-sky-300' },
    { key: 'verifier', icon: Stamp, to: '/verify', accent: 'border-emerald-400/30 bg-emerald-400/10 text-emerald-300' },
  ]

  const numbers = [
    { value: latency ? `${latency} ms` : '—', label: t('landing.numbers.latency'), icon: Gauge },
    { value: '2', label: t('landing.numbers.models'), icon: ScanSearch },
    { value: '0', label: t('landing.numbers.stored'), icon: LockKeyhole },
    { value: String(UNDO_MS / 1000), label: t('landing.numbers.undo'), icon: Undo2 },
    { value: stats?.total != null ? String(stats.total) : '—', label: t('landing.numbers.checked'), icon: ShieldCheck },
  ]

  return (
    <div className="space-y-24 md:space-y-28">
      {/* Hero */}
      <section aria-labelledby="landing-title" className="grid lg:grid-cols-[minmax(0,1.15fr)_minmax(0,0.85fr)] gap-12 items-center animate-fade-up">
        <div>
          <p className="label-caps inline-flex items-center gap-2 text-muted">
            <ShieldCheck aria-hidden className="size-3.5 text-brand" />
            {t('landing.eyebrow')}
          </p>
          <h1 id="landing-title" className="mt-6 font-serif text-[40px] md:text-[60px] leading-[1.05] font-medium tracking-[-0.015em] text-gradient">
            {t('landing.title')}
          </h1>
          <p className="mt-5 text-[16px] md:text-[17px] leading-relaxed text-muted max-w-2xl">{t('landing.subtitle')}</p>
          <div className="mt-8 flex flex-wrap gap-3">
            <Link to="/analyze" className={buttonClass('primary', 'lg')}>
              <ScanSearch aria-hidden className="size-4" />
              {t('landing.ctaCheck')}
            </Link>
            <Link to="/report" className={buttonClass('secondary', 'lg')}>
              <FilePlus2 aria-hidden className="size-4" />
              {t('landing.ctaReport')}
            </Link>
          </div>
          <Link to="/status" className="mt-4 inline-flex items-center gap-1.5 text-sm text-brand hover:underline underline-offset-4">
            <Search aria-hidden className="size-3.5" />
            {t('landing.ctaTrack')}
          </Link>
          <ul className="mt-8 flex flex-wrap gap-x-6 gap-y-2 text-[13px] text-muted">
            {['noAccount', 'noStorage', 'languages'].map((k) => (
              <li key={k} className="inline-flex items-center gap-1.5">
                <Check aria-hidden className="size-3.5 text-emerald-300" />
                {t(`landing.trust.${k}`)}
              </li>
            ))}
          </ul>
        </div>
        <HeroPreview />
      </section>

      {/* Paths */}
      <section aria-labelledby="landing-paths">
        <SectionHeading id="landing-paths" title={t('landing.paths.title')} subtitle={t('landing.paths.subtitle')} />
        <div className="grid md:grid-cols-2 lg:grid-cols-3 gap-5">
          {paths.map(({ key, cta, ...rest }) => (
            <PathCard
              key={key}
              title={t(`landing.paths.${key}.title`)}
              body={t(`landing.paths.${key}.body`)}
              cta={cta || t(`landing.paths.${key}.cta`)}
              {...rest}
            />
          ))}
        </div>
      </section>

      {/* How it works */}
      <section aria-labelledby="landing-how">
        <SectionHeading id="landing-how" title={t('landing.how.title')} subtitle={t('landing.how.subtitle')} />
        <ol className="grid md:grid-cols-5 gap-4">
          {STEPS.map(({ key, icon: Icon }, i) => (
            <li key={key} className="relative">
              {i < STEPS.length - 1 && (
                <span aria-hidden className="hidden md:block absolute top-6 left-[calc(50%+28px)] right-[calc(-50%+28px)] h-px bg-gradient-to-r from-brand/50 to-line" />
              )}
              <div className="flex md:flex-col md:items-center md:text-center gap-4">
                <span className="relative grid place-items-center size-12 shrink-0 rounded-2xl border border-line-strong bg-surface-2">
                  <Icon aria-hidden className="size-5 text-brand" />
                  <span className="absolute -top-2 -right-2 grid place-items-center size-5 rounded-full bg-brand-gradient font-mono text-[10px] font-bold text-canvas">
                    {i + 1}
                  </span>
                </span>
                <div>
                  <h3 className="font-serif text-[16px] leading-snug font-medium tracking-[-0.01em]">{t(`landing.how.steps.${key}.title`)}</h3>
                  <p className="mt-1.5 text-[13px] text-muted leading-relaxed">{t(`landing.how.steps.${key}.body`)}</p>
                </div>
              </div>
            </li>
          ))}
        </ol>
      </section>

      {/* Why */}
      <section aria-labelledby="landing-why" className="grid lg:grid-cols-2 gap-10 items-start">
        <div>
          <SectionHeading id="landing-why" eyebrow={t('landing.why.eyebrow')} title={t('landing.why.title')} />
          <div className="-mt-3 space-y-4 text-[15px] leading-relaxed text-muted">
            <p>{t('landing.why.body1')}</p>
            <p>{t('landing.why.body2')}</p>
          </div>
        </div>
        <ul className="grid sm:grid-cols-2 gap-4">
          {[
            { key: 'find', icon: ScanSearch },
            { key: 'decide', icon: UserCheck },
            { key: 'act', icon: AlarmClock },
            { key: 'prove', icon: FileLock2 },
          ].map(({ key, icon: Icon }) => (
            <li key={key}>
              <Card className="p-5 h-full">
                <Icon aria-hidden className="size-5 text-brand" />
                <p className="mt-3 text-[15px] font-medium leading-snug">{t(`landing.why.points.${key}`)}</p>
              </Card>
            </li>
          ))}
        </ul>
      </section>

      {/* Different */}
      <section aria-labelledby="landing-diff">
        <SectionHeading id="landing-diff" title={t('landing.different.title')} subtitle={t('landing.different.subtitle')} />
        <Card className="overflow-x-auto">
          <table className="w-full min-w-[720px] text-sm">
            <caption className="sr-only">{t('landing.different.title')}</caption>
            <thead>
              <tr className="border-b border-line text-left">
                <th scope="col" className="px-5 py-4 font-medium text-muted">
                  {t('landing.different.cols.feature')}
                </th>
                {['us', 'detector', 'hash', 'manual'].map((c) => (
                  <th key={c} scope="col" className={cn('px-4 py-4 text-center font-medium', c === 'us' ? 'text-fg bg-brand/[0.07]' : 'text-muted')}>
                    {t(`landing.different.cols.${c}`)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {COMPARISON.map(([row, ...marks]) => (
                <tr key={row} className="border-b border-line last:border-0">
                  <th scope="row" className="px-5 py-3.5 text-left font-normal">
                    {t(`landing.different.rows.${row}`)}
                  </th>
                  {marks.map((m, i) => (
                    <td key={i} className={cn('px-4 py-3.5 text-center', i === 0 && 'bg-brand/[0.07]')}>
                      <Mark value={m} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
        <div className="mt-3 flex flex-wrap items-center gap-x-5 gap-y-2 text-xs text-subtle">
          {['yes', 'partial', 'no'].map((v) => {
            const Icon = MARK[v].icon
            return (
              <span key={v} className="inline-flex items-center gap-1.5">
                <Icon aria-hidden className="size-3.5" />
                {t(`landing.different.${v}`)}
              </span>
            )
          })}
          <span>· {t('landing.different.footnote')}</span>
        </div>
      </section>

      {/* Numbers */}
      <section aria-labelledby="landing-numbers">
        <SectionHeading id="landing-numbers" title={t('landing.numbers.title')} />
        <div className="card grid grid-cols-2 lg:grid-cols-5 gap-px overflow-hidden bg-line p-0">
          {numbers.map(({ value, label, icon: Icon }, i) => (
            <div key={label} className={cn('bg-surface p-5 md:p-6', i === numbers.length - 1 && 'col-span-2 lg:col-span-1')}>
              <Icon aria-hidden className="size-4 text-brand" />
              <p className="mt-4 font-serif text-[34px] leading-none font-medium tracking-[-0.02em] tabular-nums">{value}</p>
              <p className="mt-2 text-xs text-muted leading-relaxed">{label}</p>
            </div>
          ))}
        </div>
        <p className="mt-4 max-w-3xl text-xs text-subtle leading-relaxed">
          {t('landing.numbers.caveat')}{' '}
          <Link to="/dashboard" className="text-brand hover:underline underline-offset-4">
            {t('landing.numbers.dashboard')}
          </Link>
        </p>
      </section>

      {/* Privacy */}
      <section aria-labelledby="landing-privacy">
        <SectionHeading id="landing-privacy" title={t('landing.privacy.title')} />
        <ul className="grid sm:grid-cols-2 lg:grid-cols-4 gap-4">
          {[
            { key: 'noStore', icon: LockKeyhole },
            { key: 'onDevice', icon: Cpu },
            { key: 'consent', icon: Fingerprint },
            { key: 'human', icon: UserCheck },
          ].map(({ key, icon: Icon }) => (
            <li key={key}>
              <Card className="p-5 h-full">
                <span className="grid place-items-center size-9 rounded-xl border border-emerald-400/25 bg-emerald-400/[0.07]">
                  <Icon aria-hidden className="size-4 text-emerald-300" />
                </span>
                <h3 className="mt-3 font-serif text-[16px] leading-snug font-medium tracking-[-0.01em]">{t(`landing.privacy.${key}.title`)}</h3>
                <p className="mt-1 text-[13px] text-muted leading-relaxed">{t(`landing.privacy.${key}.body`)}</p>
              </Card>
            </li>
          ))}
        </ul>
      </section>

      {/* Final CTA */}
      <section aria-labelledby="landing-final">
        <Card className="relative overflow-hidden px-6 py-14 md:px-12 text-center">
          <div aria-hidden className="absolute inset-0 bg-grid [mask-image:radial-gradient(ellipse_60%_80%_at_50%_0%,black,transparent)]" />
          <div aria-hidden className="absolute -top-32 left-1/2 -translate-x-1/2 h-56 w-[560px] rounded-full bg-white/[0.06] blur-3xl" />
          <h2 id="landing-final" className="relative font-serif text-[28px] md:text-[40px] leading-[1.1] font-medium tracking-[-0.015em]">
            {t('landing.final.title')}
          </h2>
          <p className="relative mt-2 text-muted">{t('landing.final.body')}</p>
          <div className="relative mt-7 flex flex-wrap justify-center gap-3">
            <Link to="/analyze" className={buttonClass('primary', 'lg')}>
              <ScanSearch aria-hidden className="size-4" />
              {t('landing.ctaCheck')}
            </Link>
            <Link to="/report" className={buttonClass('secondary', 'lg')}>
              <FilePlus2 aria-hidden className="size-4" />
              {t('landing.ctaReport')}
            </Link>
          </div>
          <Link to="/explained" className="relative mt-5 inline-flex items-center gap-1.5 text-sm text-brand hover:underline underline-offset-4">
            <BookOpen aria-hidden className="size-4" />
            {t('landing.final.guide')}
          </Link>
        </Card>
      </section>
    </div>
  )
}
