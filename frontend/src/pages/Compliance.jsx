import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, Navigate, useLocation } from 'react-router-dom'
import {
  Ban,
  BellRing,
  Building2,
  CircleCheck,
  FilePlus2,
  Gavel,
  ImageUp,
  Landmark,
  RefreshCw,
  Scale,
  ShieldAlert,
  Siren,
  Timer,
  UserRound,
  X,
} from 'lucide-react'
import {
  fetchComplianceBreaches,
  fetchComplianceQueue,
  fetchComplianceRules,
  fetchComplianceSummary,
  fileComplaint,
} from '../api/client'
import { useAuth } from '../auth/AuthContext'
import FamilyChip from '../components/FamilyChip'
import JurisdictionBadge from '../components/JurisdictionBadge'
import ReportButton from '../components/ReportButton'
import SamplePicker from '../components/SamplePicker'
import { ErrorState, LoadingState } from '../components/Status'
import { useToast } from '../components/Toast'
import { Alert, Button, Card, CardHeader, DecisionBadge, EmptyState, PageHeader, StatTile } from '../components/ui'
import { useDecisions } from '../context/DecisionContext'
import { useStats } from '../context/StatsContext'
import { IMAGE_SAMPLES } from '../lib/demo'
import { cn, dateTime, fileSize, score, timeAgo } from '../lib/format'
import { COMPLAINT_TYPES, SLA_STATUS, countdown, humanDuration, liveStatus } from '../lib/sla'

const POLL_MS = 10000

function useNow() {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  return now
}

function SlaBadge({ status }) {
  const meta = SLA_STATUS[status] || SLA_STATUS.ON_TRACK
  const Icon = meta.icon
  return (
    <span className={cn('inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-semibold', meta.ring, meta.text)}>
      <Icon className="size-3.5" />
      {meta.label}
    </span>
  )
}

function TypeChip({ type }) {
  const Icon = type === 'GOVERNMENT_ORDER' ? Landmark : UserRound
  return (
    <span className="inline-flex items-center gap-1.5 rounded-lg border border-line bg-white/[0.03] px-2 py-1 text-xs text-muted">
      <Icon className="size-3.5 text-brand" />
      {COMPLAINT_TYPES[type]?.label || type}
    </span>
  )
}

function ComplaintRow({ item, remaining, windowMinutes: fallbackWindow, atRiskMinutes, onAct, onUnauthorized }) {
  const windowMinutes = item.slaWindowMinutes ?? fallbackWindow
  const status = liveStatus(remaining, atRiskMinutes)
  const meta = SLA_STATUS[status]
  const total = (windowMinutes || 120) * 60
  const elapsedPct = Math.min(100, Math.max(0, ((total - remaining) / total) * 100))

  return (
    <Card className={cn('p-5 transition-all duration-300', status !== 'ON_TRACK' && meta.ring)}>
      {status === 'BREACHED' && (
        <div className="absolute inset-x-0 top-0 h-0.5 rounded-t-[1.1rem] bg-rose-400 animate-pulse-soft" />
      )}
      <div className="flex flex-col lg:flex-row lg:items-center gap-5">
        <div className="lg:w-52 shrink-0">
          <p className={cn('font-mono text-3xl font-semibold tabular-nums tracking-tight', meta.text)}>
            {status === 'BREACHED' && '−'}
            {countdown(remaining)}
          </p>
          <p className="text-xs text-muted mt-1">{status === 'BREACHED' ? 'overdue' : 'until deadline'}</p>
          <div className="h-1.5 rounded-full bg-white/[0.06] mt-3 overflow-hidden">
            <div className={cn('h-full rounded-full transition-[width] duration-1000 ease-linear', meta.bar)} style={{ width: `${elapsedPct}%` }} />
          </div>
        </div>

        <div className="flex-1 min-w-0 space-y-2.5">
          <div className="flex flex-wrap items-center gap-2">
            <SlaBadge status={status} />
            <TypeChip type={item.complaintType} />
            <JurisdictionBadge jurisdiction={item.jurisdiction} badge={item.slaBadge} />
            <FamilyChip family={item.generationFamily} />
            {item.escalatedAt && (
              <span className="inline-flex items-center gap-1 text-xs text-amber-200/90" title={dateTime(item.escalatedAt)}>
                <BellRing className="size-3.5" /> Escalated {timeAgo(item.escalatedAt)}
              </span>
            )}
          </div>
          <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1 text-sm">
            {item.provenanceId ? (
              <Link to={`/verify/${item.provenanceId}`} title="Audit trail" className="font-mono font-semibold hover:text-brand transition-colors">
                #{item.id}
              </Link>
            ) : (
              <span className="font-mono font-semibold">#{item.id}</span>
            )}
            {item.complaintReference && <span className="font-mono text-xs text-muted">{item.complaintReference}</span>}
          </div>
          <div className="flex flex-wrap gap-x-5 gap-y-1 text-xs text-subtle">
            <span title={dateTime(item.complaintReceivedAt)}>Received {timeAgo(item.complaintReceivedAt)}</span>
            <span>Deadline {dateTime(item.slaDeadline)}</span>
            <span className="flex items-center gap-1.5">
              Model: <DecisionBadge decision={item.decision} size="sm" />
              <span className="font-mono">
                f {score(item.forgeryScore, 2)} · e {score(item.explicitScore, 2)}
              </span>
            </span>
          </div>
        </div>

        <div className="grid grid-cols-2 lg:flex lg:flex-col gap-2 shrink-0 lg:w-56">
          <Button variant="danger" icon={Ban} onClick={() => onAct(item, 'AUTO_FLAG')} aria-label={`Take down item ${item.id}`}>
            Take down
          </Button>
          <Button variant="secondary" icon={CircleCheck} onClick={() => onAct(item, 'ALLOW')} aria-label={`Dismiss complaint for item ${item.id}`}>
            Dismiss
          </Button>
          <ReportButton item={item} className="col-span-2" onUnauthorized={onUnauthorized} />
        </div>
      </div>
    </Card>
  )
}

function BreachLog({ breaches }) {
  if (!breaches.length) return null
  return (
    <Card className="p-6 overflow-hidden">
      <CardHeader icon={Siren} title="Breach log" description="Every complaint that missed its statutory deadline, for audit." />
      <div className="overflow-x-auto -mx-6 px-6">
        <table className="data-table w-full text-sm min-w-[640px]">
          <thead>
            <tr className="text-left text-xs text-subtle border-b border-line">
              {['Item', 'Type', 'Rule', 'Reference', 'Deadline', 'Missed by', 'State'].map((h) => (
                <th key={h} className="font-medium py-3 pr-4">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {breaches.map((b) => (
              <tr key={b.id} className="border-b border-line last:border-0">
                <td className="py-3 pr-4 font-mono">#{b.id}</td>
                <td className="py-3 pr-4 text-muted">{COMPLAINT_TYPES[b.complaintType]?.label}</td>
                <td className="py-3 pr-4 text-xs text-muted whitespace-nowrap">
                  {b.jurisdiction && <span className="font-mono font-semibold text-sky-200 mr-1.5">{b.jurisdiction}</span>}
                  {b.slaBadge || '—'}
                </td>
                <td className="py-3 pr-4 font-mono text-xs text-muted">{b.complaintReference || '—'}</td>
                <td className="py-3 pr-4 text-xs text-muted">{dateTime(b.slaDeadline)}</td>
                <td className="py-3 pr-4 font-mono text-xs text-rose-300">{humanDuration(b.remainingSeconds)}</td>
                <td className="py-3 pr-4">
                  {b.resolvedAt ? (
                    <span className="text-xs text-muted">Resolved late · {timeAgo(b.resolvedAt)}</span>
                  ) : (
                    <span className="text-xs font-semibold text-rose-300">Still open</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  )
}

/** Hours for (jurisdiction, type), falling back to GENERIC like the server does. */
function ruleFor(rules, jurisdiction, type) {
  const list = rules?.rules || []
  return (
    list.find((r) => r.jurisdiction === jurisdiction && r.complaintType === type) ||
    list.find((r) => r.jurisdiction === 'GENERIC' && r.complaintType === type)
  )
}

/** Uploader appeals run on their own clock (APPEAL rules); decided from the queue's Appeals tab. */
function AppealClocks({ appeals }) {
  const parts = [
    { n: appeals.at_risk, label: 'at risk', cls: 'text-amber-300' },
    { n: appeals.breached, label: 'breached', cls: 'text-rose-300' },
  ].filter((p) => p.n > 0)
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-xl border border-line bg-white/[0.02] px-4 py-3 mb-8 text-sm">
      <Gavel aria-hidden className="size-4 text-brand" />
      <span className="font-medium">Uploader appeals</span>
      <span className="text-muted">
        {appeals.open} open
        {parts.map((p) => (
          <span key={p.label}>
            {' · '}
            <span className={p.cls}>
              {p.n} {p.label}
            </span>
          </span>
        ))}
        {appeals.nextDeadline && (
          <span title={dateTime(appeals.nextDeadline)}> · next decision due {timeAgo(appeals.nextDeadline)}</span>
        )}
      </span>
      <Link to="/queue" className="ml-auto text-xs text-brand hover:underline underline-offset-4">
        Decide in the queue
      </Link>
    </div>
  )
}

const JURISDICTION_NAMES = {
  IN: 'India',
  US: 'United States',
  EU: 'European Union',
  GB: 'United Kingdom',
  AU: 'Australia',
  GENERIC: 'Other (generic policy)',
}

function RulesCard({ rules }) {
  if (!rules?.rules?.length) return null
  return (
    <Card className="p-6 overflow-hidden">
      <CardHeader
        icon={Scale}
        title="Jurisdiction rules"
        description={`Deadlines come from this table, not code. Complaints without a jurisdiction use ${rules.defaultJurisdiction}. EU member states (DE, FR, …) use the EU rule; any other jurisdiction without its own rule falls back to GENERIC.`}
      />
      <div className="overflow-x-auto -mx-6 px-6">
        <table className="data-table w-full text-sm min-w-[720px]">
          <thead>
            <tr className="text-left text-xs text-subtle border-b border-line">
              {['Jurisdiction', 'Complaint type', 'Deadline', 'Badge', 'Legal reference'].map((h) => (
                <th key={h} className="font-medium py-3 pr-4">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rules.rules.map((r) => (
              <tr key={r.id} className="border-b border-line last:border-0 align-top">
                <td className="py-3 pr-4">
                  <span className="font-mono font-semibold">{r.jurisdiction}</span>
                  {r.jurisdiction === rules.defaultJurisdiction && (
                    <span className="ml-2 rounded-md bg-brand/15 px-1.5 py-0.5 text-[10px] text-brand">default</span>
                  )}
                </td>
                <td className="py-3 pr-4 text-muted">
                  {r.complaintType === 'APPEAL' ? 'Uploader appeal' : COMPLAINT_TYPES[r.complaintType]?.label || r.complaintType}
                </td>
                <td className="py-3 pr-4 font-mono text-xs">{r.deadlineHours}h</td>
                <td className="py-3 pr-4 whitespace-nowrap">
                  <JurisdictionBadge badge={r.badge} />
                  {/pending legal review/i.test(`${r.badge} ${r.legalReference}`) && (
                    <span className="ml-2 rounded-md border border-amber-400/30 bg-amber-400/[0.08] px-1.5 py-0.5 text-[10px] text-amber-200">
                      pending legal review
                    </span>
                  )}
                </td>
                <td className="py-3 pr-4 text-xs text-muted leading-relaxed max-w-md">{r.legalReference || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  )
}

function FileComplaintDialog({ rules, onClose, onFiled }) {
  const inputRef = useRef(null)
  const [file, setFile] = useState(null)
  const [type, setType] = useState('USER_COMPLAINT')
  const [jurisdiction, setJurisdiction] = useState(rules?.defaultJurisdiction || 'IN')
  const [reference, setReference] = useState('')
  const jurisdictions = [...new Set((rules?.rules || []).map((r) => r.jurisdiction))].sort((a, b) =>
    a === 'GENERIC' ? 1 : b === 'GENERIC' ? -1 : a.localeCompare(b),
  )
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && !busy && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [busy, onClose])

  const submit = async (e) => {
    e.preventDefault()
    if (!file) return setError('Attach the reported image.')
    setBusy(true)
    setError('')
    try {
      onFiled(await fileComplaint(file, type, reference.trim(), jurisdiction))
    } catch (err) {
      setError(err.message || 'Could not file the complaint')
      setBusy(false)
    }
  }

  return (
    <div role="dialog" aria-modal="true" aria-label="File complaint" className="fixed inset-0 z-50 grid place-items-center bg-black/75 backdrop-blur-sm p-4 animate-fade-up">
      <Card className="w-full max-w-lg p-6 bg-surface">
        <div className="flex items-start justify-between mb-5">
          <div>
            <h2 className="text-lg font-semibold tracking-tight">File a takedown complaint</h2>
            <p className="text-sm text-muted mt-1">The media is analyzed and its SLA clock starts immediately.</p>
          </div>
          <button type="button" onClick={onClose} disabled={busy} aria-label="Close" className="text-subtle hover:text-fg transition">
            <X className="size-5" />
          </button>
        </div>

        <form onSubmit={submit} className="space-y-5">
          <div className="grid grid-cols-2 gap-2" role="radiogroup" aria-label="Complaint type">
            {Object.entries(COMPLAINT_TYPES).map(([key, meta]) => {
              const Icon = key === 'GOVERNMENT_ORDER' ? Building2 : UserRound
              const active = type === key
              return (
                <button
                  key={key}
                  type="button"
                  role="radio"
                  aria-checked={active}
                  onClick={() => setType(key)}
                  className={cn(
                    'rounded-xl border p-3.5 text-left transition',
                    active ? 'border-brand/50 bg-brand/[0.08]' : 'border-line hover:border-line-strong',
                  )}
                >
                  <span className="flex items-center justify-between">
                    <Icon className={cn('size-4', active ? 'text-brand' : 'text-muted')} />
                    <span className="font-mono text-xs text-muted">
                      {ruleFor(rules, jurisdiction, key) ? `${ruleFor(rules, jurisdiction, key).deadlineHours}h` : '—'}
                    </span>
                  </span>
                  <span className="block text-sm font-medium mt-2">{meta.label}</span>
                  <span className="block text-[11px] text-subtle mt-0.5 leading-snug">{meta.detail}</span>
                </button>
              )
            })}
          </div>

          <label className="block">
            <span className="block text-[13px] font-medium text-muted mb-1.5">Jurisdiction</span>
            <select
              value={jurisdiction}
              onChange={(e) => setJurisdiction(e.target.value)}
              className="field h-11"
            >
              {jurisdictions.map((j) => (
                <option key={j} value={j}>
                  {j} · {JURISDICTION_NAMES[j] || j}
                </option>
              ))}
            </select>
            <span className="block text-[11px] text-subtle mt-1.5">
              {ruleFor(rules, jurisdiction, type)?.badge || 'No rule'} · chosen explicitly, never inferred from location
            </span>
          </label>

          <label className="block">
            <span className="block text-[13px] font-medium text-muted mb-1.5">
              {type === 'GOVERNMENT_ORDER' ? 'Order number' : 'Complaint reference'} <span className="text-subtle">(optional)</span>
            </span>
            <input
              value={reference}
              onChange={(e) => setReference(e.target.value)}
              maxLength={128}
              placeholder={type === 'GOVERNMENT_ORDER' ? 'MeitY/69A/2026/…' : 'NCII-2026-…'}
              className="field h-11 font-mono"
            />
          </label>

          <div>
            <span className="block text-[13px] font-medium text-muted mb-1.5">Reported media</span>
            <input
              ref={inputRef}
              type="file"
              accept="image/*"
              className="hidden"
              onChange={(e) => setFile(e.target.files?.[0] || null)}
            />
            <button
              type="button"
              onClick={() => inputRef.current?.click()}
              className="w-full flex items-center gap-3 rounded-xl border border-dashed border-line-strong px-4 py-3.5 text-left hover:border-white/25 hover:bg-white/[0.02] transition"
            >
              <ImageUp className="size-5 text-brand shrink-0" />
              {file ? (
                <span className="min-w-0">
                  <span className="block text-sm truncate">{file.name}</span>
                  <span className="block text-xs text-subtle">{fileSize(file.size)}</span>
                </span>
              ) : (
                <span className="text-sm text-muted">Choose an image…</span>
              )}
            </button>
            {!file && <SamplePicker kinds={IMAGE_SAMPLES} onPick={setFile} disabled={busy} className="mt-2" />}
          </div>

          {error && <Alert>{error}</Alert>}

          <div className="flex justify-end gap-2 pt-1">
            <Button variant="ghost" onClick={onClose} disabled={busy}>
              Cancel
            </Button>
            <Button type="submit" icon={Gavel} loading={busy}>
              File complaint
            </Button>
          </div>
        </form>
      </Card>
    </div>
  )
}

export default function Compliance() {
  const { isAuthenticated, loading: authLoading, logout } = useAuth()
  const { refresh: refreshStats } = useStats()
  const toast = useToast()
  const location = useLocation()
  const now = useNow()

  const [data, setData] = useState(null)
  const { decide, pendingIds, committed } = useDecisions()
  const [error, setError] = useState(null)
  const [refreshing, setRefreshing] = useState(false)
  const [dialogOpen, setDialogOpen] = useState(false)

  const load = useCallback(
    async ({ silent = false } = {}) => {
      if (!silent) setRefreshing(true)
      try {
        const [queue, breaches, summary, rules] = await Promise.all([
          fetchComplianceQueue(),
          fetchComplianceBreaches(),
          fetchComplianceSummary(),
          fetchComplianceRules(),
        ])
        setData({ queue, breaches, summary, rules, fetchedAt: Date.now() })
        setError(null)
      } catch (err) {
        if (err.status === 401 || err.status === 403) return logout()
        setError(err)
      } finally {
        setRefreshing(false)
      }
    },
    [logout],
  )

  useEffect(() => {
    if (!isAuthenticated) return
    load({ silent: true })
    const timer = setInterval(() => load({ silent: true }), POLL_MS)
    return () => clearInterval(timer)
  }, [isAuthenticated, load])

  useEffect(() => {
    if (committed && isAuthenticated) load({ silent: true })
  }, [committed, isAuthenticated, load])

  const closeDialog = useCallback(() => setDialogOpen(false), [])

  if (!authLoading && !isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  const summary = data?.summary
  const windows = summary?.slaMinutes || {}
  const atRiskMinutes = summary?.atRiskMinutes ?? 30
  const elapsed = data ? Math.floor((now - data.fetchedAt) / 1000) : 0
  const live = (data?.queue || [])
    .filter((item) => !pendingIds.has(item.id))
    .map((item) => ({ item, remaining: item.remainingSeconds - elapsed }))
  const counts = { ON_TRACK: 0, AT_RISK: 0, BREACHED: 0 }
  live.forEach(({ remaining }) => counts[liveStatus(remaining, atRiskMinutes)]++)

  const act = (item, decision) =>
    decide({
      ids: [item.id],
      decision,
      label: decision === 'AUTO_FLAG' ? `Taking down #${item.id}; clock stops when saved` : `Dismissing #${item.id}; clock stops when saved`,
    })

  const onFiled = (item) => {
    setDialogOpen(false)
    toast(`Complaint #${item.id} filed under ${item.jurisdiction}: ${item.slaBadge || 'SLA'} clock started.`)
    load({ silent: true })
    refreshStats()
  }

  return (
    <>
      <PageHeader
        eyebrow="Multi-jurisdiction compliance"
        title="Takedown SLA monitor"
        description={`Each complaint runs on its jurisdiction's clock: India IT Rules 2026 (${humanDuration((windows.USER_COMPLAINT ?? 120) * 60)} user complaints, ${humanDuration((windows.GOVERNMENT_ORDER ?? 180) * 60)} government orders), the US Take It Down Act (48h), EU, UK and Australian platform targets (pending legal review), or the generic policy. Items escalate automatically with ${humanDuration(atRiskMinutes * 60)} left.`}
        actions={
          <>
            <Button
              variant="secondary"
              icon={RefreshCw}
              onClick={() => load()}
              disabled={refreshing}
              className={cn(refreshing && '[&>svg]:animate-spin')}
            >
              Refresh
            </Button>
            <Button icon={FilePlus2} onClick={() => setDialogOpen(true)}>
              File complaint
            </Button>
          </>
        }
      />

      {error && <ErrorState error={error} title="Couldn't load compliance data" onRetry={() => load()} className="mb-6" />}

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-8 animate-fade-up">
        <StatTile icon={Timer} label="Open complaints" value={live.length} hint="Clocks currently running" loading={!data} />
        <StatTile icon={CircleCheck} label="On track" value={counts.ON_TRACK} accent="text-emerald-300" hint={`More than ${humanDuration(atRiskMinutes * 60)} left`} loading={!data} />
        <StatTile icon={ShieldAlert} label="At risk" value={counts.AT_RISK} accent="text-amber-300" hint="Escalated to on-call" loading={!data} />
        <StatTile
          icon={Siren}
          label="Breached"
          value={counts.BREACHED}
          accent="text-rose-300"
          hint={`${summary?.totalBreaches ?? 0} ${summary?.totalBreaches === 1 ? 'breach' : 'breaches'} on record`}
          loading={!data}
        />
      </div>

      {summary?.appeals && <AppealClocks appeals={summary.appeals} />}

      <div className="flex items-center gap-2 mb-4">
        <Timer className="size-4 text-brand" />
        <h2 className="text-[15px] font-semibold tracking-tight">Open complaints</h2>
        <span className="text-xs text-subtle">· most urgent first</span>
      </div>

      {!data ? (
        error ? null : <LoadingState label="Loading open complaints and their deadlines…" />
      ) : live.length === 0 ? (
        <EmptyState icon={CircleCheck} title="No open complaints" description="Every takedown request has been actioned. File a complaint to start a new SLA clock.">
          <Button icon={FilePlus2} onClick={() => setDialogOpen(true)}>
            File complaint
          </Button>
        </EmptyState>
      ) : (
        <div className="space-y-4">
          {live.map(({ item, remaining }, i) => (
            <div key={item.id} className="animate-fade-up" style={{ animationDelay: `${Math.min(i, 8) * 40}ms` }}>
              <ComplaintRow
                item={item}
                remaining={remaining}
                windowMinutes={windows[item.complaintType]}
                atRiskMinutes={atRiskMinutes}
                onAct={act}
                onUnauthorized={logout}
              />
            </div>
          ))}
        </div>
      )}

      {data?.breaches?.length > 0 && (
        <div className="mt-10">
          <BreachLog breaches={data.breaches} />
        </div>
      )}

      {data?.rules && (
        <div className="mt-10">
          <RulesCard rules={data.rules} />
        </div>
      )}

      {dialogOpen && <FileComplaintDialog rules={data?.rules} onClose={closeDialog} onFiled={onFiled} />}
    </>
  )
}
