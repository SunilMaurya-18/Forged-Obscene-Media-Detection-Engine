import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Gavel, Info, Layers, Printer, Scale, Timer, Users } from 'lucide-react'
import { fetchTransparency } from '../api/client'
import { ErrorState, LoadingState } from '../components/Status'
import { Button, Card, CardHeader, PageHeader } from '../components/ui'
import { cell, pct, periodOptions } from '../lib/appeals'
import { cn, dateTime } from '../lib/format'

const DECISIONS = ['ALLOW', 'REVIEW', 'AUTO_FLAG']

function Stat({ label, value, hint }) {
  return (
    <div className="rounded-xl border border-line bg-white/[0.02] px-4 py-3 print:py-1.5 break-inside-avoid">
      <p className="text-xs text-muted">{label}</p>
      <p className="text-2xl font-semibold tracking-tight tabular-nums mt-0.5 print:text-base">{value}</p>
      {hint && <p className="text-[11px] text-subtle mt-0.5">{hint}</p>}
    </div>
  )
}

function Table({ head, rows, caption }) {
  return (
    <table className="w-full text-sm">
      <caption className="sr-only">{caption}</caption>
      <thead>
        <tr className="text-left text-xs text-muted border-b border-line">
          {head.map((h, i) => (
            <th key={h} scope="col" className={cn('py-2 font-medium', i && 'text-right')}>
              {h}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r[0]} className="border-b border-line/60 last:border-0">
            {r.map((c, i) => (
              <td key={i} className={cn('py-2', i ? 'text-right tabular-nums' : '')}>
                {c}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function Section({ icon, title, description, children }) {
  return (
    <Card className="p-5 md:p-6 break-inside-avoid print:p-3 print:shadow-none">
      <CardHeader icon={icon} title={title} description={description} />
      {children}
    </Card>
  )
}

/** Phase 23: public transparency report (EU DSA Art. 15/24 style). Aggregates only; small counts arrive as null. */
export default function Transparency() {
  const { t } = useTranslation()
  const [params, setParams] = useSearchParams()
  const period = params.get('period') || 'last-30d'
  const [report, setReport] = useState(null)
  const [error, setError] = useState(null)
  const options = periodOptions()
  const known = options.some((o) => o.value === period)

  useEffect(() => {
    let live = true
    fetchTransparency(period)
      .then((next) => {
        if (!live) return
        setReport(next)
        setError(null)
      })
      .catch((e) => {
        if (!live) return
        setError(e)
        setReport(null)
      })
    return () => {
      live = false
    }
  }, [period])

  const r = report
  const min = r?.suppression?.minCell ?? 5
  const n = (value) =>
    value == null ? (
      <span title={t('transparency.withheld', { min })} className="text-subtle">
        {cell(value, min)}
      </span>
    ) : (
      cell(value, min)
    )
  const s = (value) => cell(value, min)
  const v = r?.volumes
  const d = r?.decisions
  const a = r?.appeals
  const m = r?.moderation
  const sla = r?.sla

  return (
    <>
      <PageHeader
        eyebrow={t('transparency.eyebrow')}
        title={t('transparency.title')}
        description={t('transparency.description')}
        actions={
          <div className="flex flex-wrap items-center gap-2 print:hidden">
            <label htmlFor="tr-period" className="sr-only">
              {t('transparency.period')}
            </label>
            <select
              id="tr-period"
              value={period}
              onChange={(e) => setParams({ period: e.target.value }, { replace: true })}
              className="rounded-xl border border-line bg-surface-2 px-3 py-2 text-sm"
            >
              {!known && <option value={period}>{period}</option>}
              {options.map((o) => (
                <option key={o.value} value={o.value}>
                  {t(`transparency.periods.${o.key}`)}
                </option>
              ))}
            </select>
            <Button variant="secondary" icon={Printer} onClick={() => window.print()} disabled={!r}>
              {t('transparency.print')}
            </Button>
          </div>
        }
      />

      <ErrorState error={error} />
      {!r && !error && <LoadingState label={t('transparency.loading')} className="py-16" />}

      {r && (
        <div className="space-y-5 print:space-y-3">
          <p className="text-sm text-muted">
            <strong className="text-fg">{r.period.label}</strong> ·{' '}
            {t('transparency.range', { from: dateTime(r.period.from), to: dateTime(r.period.to), generated: dateTime(r.generatedAt) })}
          </p>

          <Section icon={Layers} title={t('transparency.volumes.title')} description={t('transparency.volumes.description')}>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 print:grid-cols-4">
              <Stat
                label={t('transparency.volumes.uploads')}
                value={n(v.uploads)}
                hint={t('transparency.volumes.uploadsHint', { images: s(v.images), videos: s(v.videos) })}
              />
              <Stat label={t('transparency.volumes.api')} value={n(v.apiSubmissions)} />
              <Stat
                label={t('transparency.volumes.complaints')}
                value={n(v.complaints)}
                hint={t('transparency.volumes.complaintsHint', {
                  user: s(v.complaintsByType?.USER_COMPLAINT),
                  gov: s(v.complaintsByType?.GOVERNMENT_ORDER),
                })}
              />
              <Stat label={t('transparency.volumes.appeals')} value={n(v.appeals)} />
            </div>
          </Section>

          <Section icon={Scale} title={t('transparency.decisions.title')} description={t('transparency.decisions.description')}>
            <Table
              caption={t('transparency.decisions.caption')}
              head={[t('transparency.decisions.outcome'), t('transparency.decisions.automated'), t('transparency.decisions.current')]}
              rows={DECISIONS.map((k) => [t(`transparency.decisions.${k}`), n(d.automated[k]), n(d.current[k])])}
            />
            <p className="text-xs text-muted mt-3">
              {t('transparency.decisions.footer', { reviewed: s(d.humanReviewed), pending: s(d.pendingSecondReview) })}
            </p>
          </Section>

          <Section icon={Timer} title={t('transparency.sla.title')} description={t('transparency.sla.description')}>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4 print:grid-cols-4">
              <Stat label={t('transparency.sla.received')} value={n(sla.received)} />
              <Stat label={t('transparency.sla.resolved')} value={n(sla.resolved)} />
              <Stat label={t('transparency.sla.open')} value={n(sla.open)} />
              <Stat
                label={t('transparency.sla.within')}
                value={pct(sla.complianceRate)}
                hint={t('transparency.sla.withinHint', { within: s(sla.withinDeadline), resolved: s(sla.resolved) })}
              />
            </div>
            {sla.byJurisdiction?.length > 0 && (
              <Table
                caption={t('transparency.sla.caption')}
                head={[
                  t('transparency.sla.jurisdiction'),
                  t('transparency.sla.received'),
                  t('transparency.sla.resolved'),
                  t('transparency.sla.within'),
                  t('transparency.sla.rate'),
                ]}
                rows={sla.byJurisdiction.map((j) => [j.jurisdiction, n(j.received), n(j.resolved), n(j.withinDeadline), pct(j.complianceRate)])}
              />
            )}
          </Section>

          <Section icon={Gavel} title={t('transparency.appeals.title')} description={t('transparency.appeals.description')}>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 print:grid-cols-4">
              <Stat label={t('transparency.appeals.filed')} value={n(a.filed)} hint={t('transparency.appeals.filedHint', { open: s(a.open) })} />
              <Stat
                label={t('transparency.appeals.granted')}
                value={n(a.granted)}
                hint={t('transparency.appeals.grantedHint', { reinstated: s(a.reinstated) })}
              />
              <Stat label={t('transparency.appeals.upheld')} value={n(a.upheld)} />
              <Stat label={t('transparency.appeals.grantRate')} value={pct(a.grantRate)} />
              <Stat
                label={t('transparency.appeals.withinSla')}
                value={pct(a.slaComplianceRate)}
                hint={t('transparency.appeals.withinSlaHint', { count: s(a.decidedWithinSla) })}
              />
              <Stat
                label={t('transparency.appeals.median')}
                value={a.medianHoursToDecision == null ? 'n/a' : t('transparency.appeals.hours', { hours: a.medianHoursToDecision.toFixed(1) })}
              />
            </div>
          </Section>

          <Section icon={Users} title={t('transparency.oversight.title')} description={t('transparency.oversight.description')}>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 print:grid-cols-4">
              <Stat label={t('transparency.oversight.reviewed')} value={n(m.humanReviewed)} />
              <Stat
                label={t('transparency.oversight.overturnRate')}
                value={pct(m.overturnRate)}
                hint={t('transparency.oversight.overturnHint', { overturned: s(m.overturned), reviewed: s(m.modelDecisionsReviewed) })}
              />
              <Stat label={t('transparency.oversight.autoFlagReviewed')} value={n(m.autoFlagReviewed)} />
              <Stat
                label={t('transparency.oversight.autoFlagOverturned')}
                value={pct(m.autoFlagOverturnRate)}
                hint={t('transparency.oversight.autoFlagHint', { count: s(m.autoFlagOverturned) })}
              />
              <Stat
                label={t('transparency.oversight.moderatorOverturned')}
                value={pct(m.moderatorOverturnRate)}
                hint={t('transparency.oversight.moderatorHint', {
                  overturned: s(m.moderatorDecisionsOverturned),
                  appealed: s(m.moderatorDecisionsAppealed),
                })}
              />
            </div>
          </Section>

          <Card className="p-5 break-inside-avoid print:p-3">
            <h2 className="flex items-center gap-2 text-sm font-semibold">
              <Info aria-hidden className="size-4 text-brand" /> {t('transparency.notes.title')}
            </h2>
            <ul className="mt-2 space-y-1 text-xs text-muted list-disc pl-5">
              <li>{t('transparency.notes.aggregates')}</li>
              <li>{t('transparency.notes.counting')}</li>
              {min > 1 && <li>{t('transparency.notes.suppression', { max: min - 1, min })}</li>}
              {r.pendingLegalReview?.length > 0 && <li>{t('transparency.notes.pending', { codes: r.pendingLegalReview.join(', ') })}</li>}
            </ul>
          </Card>
        </div>
      )}
    </>
  )
}
