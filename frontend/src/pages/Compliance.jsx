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
  ShieldAlert,
  Siren,
  Timer,
  UserRound,
  X,
} from 'lucide-react'
import {
  fetchComplianceBreaches,
  fetchComplianceQueue,
  fetchComplianceSummary,
  fileComplaint,
  updateQueueDecision,
} from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { useToast } from '../components/Toast'
import { Alert, Button, Card, CardHeader, DecisionBadge, EmptyState, PageHeader, StatTile } from '../components/ui'
import { useStats } from '../context/StatsContext'
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

function TypeChip({ type, minutes }) {
  const Icon = type === 'GOVERNMENT_ORDER' ? Landmark : UserRound
  return (
    <span className="inline-flex items-center gap-1.5 rounded-lg border border-line bg-white/[0.03] px-2 py-1 text-xs text-muted">
      <Icon className="size-3.5 text-brand" />
      {COMPLAINT_TYPES[type]?.label || type}
      {minutes != null && <span className="text-subtle">· {humanDuration(minutes * 60)} SLA</span>}
    </span>
  )
}

function ComplaintRow({ item, remaining, windowMinutes, atRiskMinutes, busy, onAct }) {
  const status = liveStatus(remaining, atRiskMinutes)
  const meta = SLA_STATUS[status]
  const total = (windowMinutes || 120) * 60
  const elapsedPct = Math.min(100, Math.max(0, ((total - remaining) / total) * 100))

  return (
    <Card className={cn('p-5 transition-all duration-300', status !== 'ON_TRACK' && meta.ring, busy && 'opacity-50')}>
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
            <TypeChip type={item.complaintType} minutes={windowMinutes} />
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

        <div className="flex lg:flex-col gap-2 shrink-0">
          <Button variant="danger" icon={Ban} disabled={busy} onClick={() => onAct(item, 'AUTO_FLAG')} className="flex-1 lg:flex-none lg:w-36">
            Take down
          </Button>
          <Button variant="secondary" icon={CircleCheck} disabled={busy} onClick={() => onAct(item, 'ALLOW')} className="flex-1 lg:flex-none lg:w-36">
            Dismiss
          </Button>
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
        <table className="w-full text-sm min-w-[640px]">
          <thead>
            <tr className="text-left text-xs text-subtle border-b border-line">
              {['Item', 'Type', 'Reference', 'Deadline', 'Missed by', 'State'].map((h) => (
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

function FileComplaintDialog({ slaMinutes, onClose, onFiled }) {
  const inputRef = useRef(null)
  const [file, setFile] = useState(null)
  const [type, setType] = useState('USER_COMPLAINT')
  const [reference, setReference] = useState('')
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
      onFiled(await fileComplaint(file, type, reference.trim()))
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
                    <span className="font-mono text-xs text-muted">{humanDuration((slaMinutes?.[key] ?? 0) * 60)}</span>
                  </span>
                  <span className="block text-sm font-medium mt-2">{meta.label}</span>
                  <span className="block text-[11px] text-subtle mt-0.5 leading-snug">{meta.detail}</span>
                </button>
              )
            })}
          </div>

          <label className="block">
            <span className="block text-[13px] font-medium text-muted mb-1.5">
              {type === 'GOVERNMENT_ORDER' ? 'Order number' : 'Complaint reference'} <span className="text-subtle">(optional)</span>
            </span>
            <input
              value={reference}
              onChange={(e) => setReference(e.target.value)}
              maxLength={128}
              placeholder={type === 'GOVERNMENT_ORDER' ? 'MeitY/69A/2026/…' : 'NCII-2026-…'}
              className="w-full h-11 rounded-xl border border-line-strong bg-canvas/60 px-3.5 text-sm font-mono placeholder:text-subtle focus:outline-none focus:border-brand/60 focus:ring-4 focus:ring-brand/15"
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
  const [error, setError] = useState('')
  const [refreshing, setRefreshing] = useState(false)
  const [busyId, setBusyId] = useState(null)
  const [dialogOpen, setDialogOpen] = useState(false)

  const load = useCallback(
    async ({ silent = false } = {}) => {
      if (!silent) setRefreshing(true)
      try {
        const [queue, breaches, summary] = await Promise.all([
          fetchComplianceQueue(),
          fetchComplianceBreaches(),
          fetchComplianceSummary(),
        ])
        setData({ queue, breaches, summary, fetchedAt: Date.now() })
        setError('')
      } catch (err) {
        if (err.status === 401 || err.status === 403) return logout()
        setError(err.message || 'Could not load compliance data')
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

  const closeDialog = useCallback(() => setDialogOpen(false), [])

  if (!authLoading && !isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  const summary = data?.summary
  const windows = summary?.slaMinutes || {}
  const atRiskMinutes = summary?.atRiskMinutes ?? 30
  const elapsed = data ? Math.floor((now - data.fetchedAt) / 1000) : 0
  const live = (data?.queue || []).map((item) => ({ item, remaining: item.remainingSeconds - elapsed }))
  const counts = { ON_TRACK: 0, AT_RISK: 0, BREACHED: 0 }
  live.forEach(({ remaining }) => counts[liveStatus(remaining, atRiskMinutes)]++)

  const act = async (item, decision) => {
    setBusyId(item.id)
    try {
      await updateQueueDecision(item.id, decision)
      toast(decision === 'AUTO_FLAG' ? `#${item.id} taken down, clock stopped` : `#${item.id} dismissed, clock stopped`)
      await load({ silent: true })
      refreshStats()
    } catch (err) {
      toast(err.message || 'Update failed', { tone: 'error' })
    } finally {
      setBusyId(null)
    }
  }

  const onFiled = (item) => {
    setDialogOpen(false)
    toast(`Complaint #${item.id} filed. ${humanDuration((windows[item.complaintType] ?? 0) * 60)} clock started.`)
    load({ silent: true })
    refreshStats()
  }

  return (
    <>
      <PageHeader
        eyebrow="IT Rules 2026 compliance"
        title="Takedown SLA monitor"
        description={`User deepfake / NCII complaints must be actioned within ${humanDuration((windows.USER_COMPLAINT ?? 120) * 60)} and government orders within ${humanDuration((windows.GOVERNMENT_ORDER ?? 180) * 60)}. Items escalate automatically with ${humanDuration(atRiskMinutes * 60)} left.`}
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

      {error && (
        <div className="mb-6">
          <Alert>{error}</Alert>
        </div>
      )}

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

      <div className="flex items-center gap-2 mb-4">
        <Timer className="size-4 text-brand" />
        <h2 className="text-[15px] font-semibold tracking-tight">Open complaints</h2>
        <span className="text-xs text-subtle">· most urgent first</span>
      </div>

      {!data ? (
        <div className="space-y-4">
          {[0, 1].map((i) => (
            <Card key={i} className="p-5">
              <div className="flex gap-6">
                <div className="skeleton h-12 w-44" />
                <div className="flex-1 space-y-2.5">
                  <div className="skeleton h-5 w-56" />
                  <div className="skeleton h-3 w-80" />
                </div>
              </div>
            </Card>
          ))}
        </div>
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
                busy={busyId === item.id}
                onAct={act}
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

      {dialogOpen && <FileComplaintDialog slaMinutes={windows} onClose={closeDialog} onFiled={onFiled} />}
    </>
  )
}
