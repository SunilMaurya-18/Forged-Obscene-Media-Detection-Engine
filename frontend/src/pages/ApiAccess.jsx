import { useCallback, useEffect, useState } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { Activity, Ban, Gauge, KeyRound, Plus, RefreshCw, RotateCw, ShieldAlert, TerminalSquare, X } from 'lucide-react'
import { changeApiClientPlan, createApiClient, fetchApiClients, revokeApiClient, rotateApiClient } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { useToast } from '../components/Toast'
import { Alert, Button, Card, CardHeader, CopyButton, EmptyState, PageHeader, StatTile } from '../components/ui'
import { cn, dateTime, timeAgo } from '../lib/format'

const PLANS = ['FREE', 'PRO', 'ENTERPRISE']
const PLAN_TONE = {
  FREE: 'border-line-strong text-muted',
  PRO: 'border-brand/40 text-indigo-200 bg-brand/[0.08]',
  ENTERPRISE: 'border-emerald-400/35 text-emerald-200 bg-emerald-400/[0.08]',
}

function origin() {
  return typeof window === 'undefined' ? 'http://localhost:3000' : window.location.origin
}

function CodeBlock({ code }) {
  return (
    <div className="relative group">
      <pre className="overflow-x-auto rounded-xl border border-line bg-canvas/80 p-4 pr-12 text-[12.5px] leading-relaxed font-mono text-slate-300">
        {code}
      </pre>
      <CopyButton value={code} className="absolute top-2.5 right-2.5" />
    </div>
  )
}

function IssuedKey({ issued, onDismiss }) {
  return (
    <Card className="p-6 mb-8 border-amber-400/30 bg-amber-400/[0.04] animate-fade-up">
      <div className="flex items-start justify-between gap-4 mb-4">
        <div className="flex items-start gap-3">
          <span className="grid place-items-center size-9 shrink-0 rounded-xl border border-amber-400/30 bg-amber-400/10 text-amber-300">
            <KeyRound className="size-[18px]" />
          </span>
          <div>
            <h2 className="text-[15px] font-semibold tracking-tight">API key for {issued.name}</h2>
            <p className="text-sm text-amber-100/80 mt-0.5">
              Copy it now. It is stored only as a SHA-256 hash and cannot be shown again.
            </p>
          </div>
        </div>
        <button type="button" onClick={onDismiss} aria-label="Dismiss" className="text-subtle hover:text-fg transition">
          <X className="size-5" />
        </button>
      </div>
      <div className="flex items-center gap-2 rounded-xl border border-amber-400/25 bg-canvas/70 px-4 py-3">
        <code className="flex-1 min-w-0 truncate font-mono text-sm text-amber-100 select-all">{issued.apiKey}</code>
        <CopyButton value={issued.apiKey} />
      </div>
      <div className="mt-4">
        <CodeBlock
          code={`curl -H "X-API-Key: ${issued.apiKey}" \\\n  -F file=@upload.jpg ${origin()}/api/v1/analyze`}
        />
      </div>
    </Card>
  )
}

function NewClientForm({ onCreated, onCancel }) {
  const [name, setName] = useState('')
  const [plan, setPlan] = useState('FREE')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const submit = async (e) => {
    e.preventDefault()
    if (!name.trim()) return setError('Give the client a name, for example the platform it belongs to.')
    setBusy(true)
    setError('')
    try {
      onCreated(await createApiClient(name.trim(), plan))
    } catch (err) {
      setError(err.message || 'Could not create the client')
      setBusy(false)
    }
  }

  return (
    <Card className="p-6 mb-8 animate-fade-up">
      <CardHeader icon={Plus} title="Issue a new API key" description="One key per integrating platform. You can rotate or revoke it at any time." />
      <form onSubmit={submit} className="grid md:grid-cols-[1fr_auto_auto] gap-3 items-start">
        <input
          autoFocus
          value={name}
          onChange={(e) => setName(e.target.value)}
          maxLength={80}
          placeholder="Client name, e.g. Acme Social"
          className="h-10 rounded-xl border border-line-strong bg-canvas/60 px-3.5 text-sm placeholder:text-subtle focus:outline-none focus:border-brand/60 focus:ring-4 focus:ring-brand/15"
        />
        <div role="radiogroup" aria-label="Plan" className="inline-flex p-1 rounded-xl border border-line bg-white/[0.02]">
          {PLANS.map((p) => (
            <button
              key={p}
              type="button"
              role="radio"
              aria-checked={plan === p}
              onClick={() => setPlan(p)}
              className={cn(
                'h-8 px-3 rounded-lg text-xs font-semibold tracking-wide transition',
                plan === p ? 'bg-white/[0.08] text-fg' : 'text-muted hover:text-fg',
              )}
            >
              {p}
            </button>
          ))}
        </div>
        <div className="flex gap-2">
          <Button variant="ghost" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" icon={KeyRound} loading={busy}>
            Create key
          </Button>
        </div>
      </form>
      {error && (
        <div className="mt-4">
          <Alert>{error}</Alert>
        </div>
      )}
    </Card>
  )
}

function ClientRow({ client, limits, busy, onRotate, onRevoke, onPlan }) {
  const used = client.lastUsedAt
  return (
    <tr className={cn('border-b border-line last:border-0 align-middle', !client.active && 'opacity-50')}>
      <td className="py-3.5 pr-4">
        <p className="font-medium">{client.name}</p>
        <p className="font-mono text-[11px] text-subtle mt-0.5">{client.keyPrefix}…</p>
      </td>
      <td className="py-3.5 pr-4">
        {client.active ? (
          <select
            value={client.plan}
            disabled={busy}
            onChange={(e) => onPlan(client, e.target.value)}
            aria-label={`Plan for ${client.name}`}
            className={cn(
              'h-8 rounded-lg border bg-canvas/60 px-2 text-xs font-semibold tracking-wide focus:outline-none focus:ring-2 focus:ring-brand/30',
              PLAN_TONE[client.plan],
            )}
          >
            {PLANS.map((p) => (
              <option key={p} value={p}>
                {p} · {limits[p]}/min
              </option>
            ))}
          </select>
        ) : (
          <span className="text-xs text-subtle">{client.plan}</span>
        )}
      </td>
      <td className="py-3.5 pr-4 tabular-nums">{client.requestCount.toLocaleString()}</td>
      <td className={cn('py-3.5 pr-4 tabular-nums', client.throttledCount > 0 ? 'text-amber-300' : 'text-subtle')}>
        {client.throttledCount.toLocaleString()}
      </td>
      <td className="py-3.5 pr-4 tabular-nums">{client.analysisCount.toLocaleString()}</td>
      <td className="py-3.5 pr-4 text-xs text-muted" title={used ? dateTime(used) : undefined}>
        {used ? timeAgo(used) : 'Never'}
      </td>
      <td className="py-3.5 pr-4">
        {client.active ? (
          <span className="inline-flex items-center gap-1.5 text-xs font-medium text-emerald-300">
            <span className="size-1.5 rounded-full bg-emerald-400" /> Active
          </span>
        ) : (
          <span className="text-xs text-rose-300" title={dateTime(client.revokedAt)}>
            Revoked
          </span>
        )}
      </td>
      <td className="py-3.5">
        {client.active && (
          <div className="flex justify-end gap-1.5">
            <Button size="sm" variant="secondary" icon={RotateCw} disabled={busy} onClick={() => onRotate(client)}>
              Rotate
            </Button>
            <Button size="sm" variant="danger" icon={Ban} disabled={busy} onClick={() => onRevoke(client)}>
              Revoke
            </Button>
          </div>
        )}
      </td>
    </tr>
  )
}

export default function ApiAccess() {
  const { isAuthenticated, loading: authLoading, logout } = useAuth()
  const toast = useToast()
  const location = useLocation()
  const [data, setData] = useState(null)
  const [error, setError] = useState('')
  const [refreshing, setRefreshing] = useState(false)
  const [formOpen, setFormOpen] = useState(false)
  const [issued, setIssued] = useState(null)
  const [busyId, setBusyId] = useState(null)

  const load = useCallback(
    async ({ silent = false } = {}) => {
      if (!silent) setRefreshing(true)
      try {
        setData(await fetchApiClients())
        setError('')
      } catch (err) {
        if (err.status === 401 || err.status === 403) return logout()
        setError(err.message || 'Could not load API clients')
      } finally {
        setRefreshing(false)
      }
    },
    [logout],
  )

  useEffect(() => {
    if (!isAuthenticated) return
    load({ silent: true })
    const timer = setInterval(() => load({ silent: true }), 10000)
    return () => clearInterval(timer)
  }, [isAuthenticated, load])

  if (!authLoading && !isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  const run = async (client, action, message) => {
    setBusyId(client.id)
    try {
      const result = await action()
      if (result?.apiKey) setIssued(result)
      toast(message)
      await load({ silent: true })
    } catch (err) {
      toast(err.message || 'Action failed', { tone: 'error' })
    } finally {
      setBusyId(null)
    }
  }

  const onRotate = (client) => {
    if (!window.confirm(`Rotate the key for ${client.name}? The current key stops working immediately.`)) return
    run(client, () => rotateApiClient(client.id), `New key issued for ${client.name}`)
  }
  const onRevoke = (client) => {
    if (!window.confirm(`Revoke ${client.name}? Every request with its key will get 401.`)) return
    run(client, () => revokeApiClient(client.id), `${client.name} revoked`)
  }
  const onPlan = (client, plan) =>
    run(client, () => changeApiClientPlan(client.id, plan), `${client.name} moved to ${plan}`)

  const onCreated = (result) => {
    setFormOpen(false)
    setIssued(result)
    toast(`Key issued for ${result.name}`)
    load({ silent: true })
  }

  const clients = data?.clients || []
  const limits = data?.plans || {}
  const active = clients.filter((c) => c.active)
  const totals = active.reduce(
    (t, c) => ({ requests: t.requests + c.requestCount, throttled: t.throttled + c.throttledCount }),
    { requests: 0, throttled: 0 },
  )

  return (
    <>
      <PageHeader
        eyebrow="Self-serve API"
        title="API access"
        description="Issue keys to partner platforms so they can call /api/v1/analyze from their own upload pipelines. Each key is rate limited by its plan and can be rotated or revoked instantly."
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
            <Button icon={Plus} onClick={() => setFormOpen(true)}>
              New client
            </Button>
          </>
        }
      />

      {error && (
        <div className="mb-6">
          <Alert>{error}</Alert>
        </div>
      )}

      {issued && <IssuedKey issued={issued} onDismiss={() => setIssued(null)} />}
      {formOpen && <NewClientForm onCreated={onCreated} onCancel={() => setFormOpen(false)} />}

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-8 animate-fade-up">
        <StatTile icon={KeyRound} label="Active keys" value={active.length} hint={`${clients.length - active.length} revoked`} loading={!data} />
        <StatTile icon={Activity} label="API requests" value={totals.requests.toLocaleString()} hint="Across active keys" loading={!data} />
        <StatTile
          icon={ShieldAlert}
          label="Throttled"
          value={totals.throttled.toLocaleString()}
          accent="text-amber-300"
          hint="Requests answered with 429"
          loading={!data}
        />
        <StatTile
          icon={Gauge}
          label="Plan limits"
          value={data ? `${limits.FREE}/${limits.PRO}/${limits.ENTERPRISE}` : ''}
          hint="Free / Pro / Enterprise, per minute"
          loading={!data}
        />
      </div>

      {data && clients.length === 0 ? (
        <EmptyState icon={KeyRound} title="No API clients yet" description="Create a key for the first platform that wants to integrate detection into its uploads.">
          <Button icon={Plus} onClick={() => setFormOpen(true)}>
            New client
          </Button>
        </EmptyState>
      ) : (
        <Card className="p-6 overflow-hidden">
          <CardHeader icon={KeyRound} title="Clients" description="Keys are shown once at creation or rotation. Only a SHA-256 hash and the prefix are kept." />
          <div className="overflow-x-auto -mx-6 px-6">
            <table className="w-full text-sm min-w-[860px]">
              <thead>
                <tr className="text-left text-xs text-subtle border-b border-line">
                  {['Client', 'Plan', 'Requests', 'Throttled', 'Analyses', 'Last used', 'Status', ''].map((h) => (
                    <th key={h} className="font-medium py-3 pr-4">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {!data
                  ? [0, 1].map((i) => (
                      <tr key={i}>
                        <td colSpan={8} className="py-3">
                          <div className="skeleton h-8 w-full" />
                        </td>
                      </tr>
                    ))
                  : clients.map((c) => (
                      <ClientRow
                        key={c.id}
                        client={c}
                        limits={limits}
                        busy={busyId === c.id}
                        onRotate={onRotate}
                        onRevoke={onRevoke}
                        onPlan={onPlan}
                      />
                    ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      <Card className="p-6 mt-8">
        <CardHeader
          icon={TerminalSquare}
          title="Quickstart"
          description="Call it server-side from your upload handler and never ship the key to browsers or apps. The full guide is in API_DOCS.md."
        />
        <div className="grid lg:grid-cols-2 gap-4">
          <div>
            <p className="text-xs text-subtle mb-2">Score an upload before publishing it</p>
            <CodeBlock code={`curl -H "X-API-Key: $FMD_API_KEY" \\\n  -F file=@upload.jpg \\\n  ${origin()}/api/v1/analyze`} />
          </div>
          <div>
            <p className="text-xs text-subtle mb-2">Check your quota (not rate limited)</p>
            <CodeBlock code={`curl -H "X-API-Key: $FMD_API_KEY" \\\n  ${origin()}/api/v1/usage`} />
          </div>
        </div>
        <div className="grid sm:grid-cols-3 gap-3 mt-4 text-xs">
          {[
            ['ALLOW', 'Publish immediately', 'text-emerald-300'],
            ['REVIEW', 'Hold and send to your moderators', 'text-amber-300'],
            ['AUTO_FLAG', 'Block, and keep the provenanceId', 'text-rose-300'],
          ].map(([d, action, tone]) => (
            <div key={d} className="rounded-xl border border-line px-3.5 py-2.5">
              <span className={cn('font-mono font-semibold', tone)}>{d}</span>
              <span className="text-muted"> → {action}</span>
            </div>
          ))}
        </div>
      </Card>
    </>
  )
}
