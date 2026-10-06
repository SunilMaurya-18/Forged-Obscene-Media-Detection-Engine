import { useCallback, useEffect, useState } from 'react'
import { Link, Navigate, useLocation, useSearchParams } from 'react-router-dom'
import { Check, FilePlus2, RefreshCw, Scale, ShieldOff, UserCheck, X } from 'lucide-react'
import { decideLegalRequest, fetchInvestigatorAccounts, fetchLegalRequests, fileLegalRequest } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import RequestStatusBadge from '../components/RequestStatusBadge'
import { ErrorState, LoadingState } from '../components/Status'
import { useToast } from '../components/Toast'
import { Alert, Button, Card, CardHeader, INPUT, PageHeader, StatTile } from '../components/ui'
import { cn, dateTime, timeAgo } from '../lib/format'
import {
  AUTHORITY_MAX,
  MAX_TERM_DAYS,
  REFERENCE_MAX,
  dateInputValue,
  expiryInstant,
  filingProblem,
  groupRequests,
  parseCaseNumbers,
  timeLeft,
} from '../lib/handover'
import { NOTE_MAX } from '../lib/investigations'

function Field({ label, hint, children }) {
  return (
    <label className="block">
      <span className="text-xs font-medium text-muted">{label}</span>
      <div className="mt-1.5">{children}</div>
      {hint && <p className="text-xs text-subtle mt-1">{hint}</p>}
    </label>
  )
}

function FileForm({ investigators, initialCase, onFiled, onUnauthorized }) {
  const [form, setForm] = useState(() => ({
    authority: '',
    orderReference: '',
    investigator: investigators[0]?.username || '',
    caseText: initialCase || '',
    expiry: dateInputValue(new Date(), 30),
    note: '',
  }))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [touched, setTouched] = useState(false)
  const set = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.value }))
  const problem = filingProblem(form)
  const { cases } = parseCaseNumbers(form.caseText)

  const submit = async (e) => {
    e.preventDefault()
    setTouched(true)
    if (problem) return
    setBusy(true)
    setError(null)
    try {
      const r = await fileLegalRequest({
        authority: form.authority.trim(),
        orderReference: form.orderReference.trim(),
        investigator: form.investigator,
        caseNumbers: cases,
        expiresAt: expiryInstant(form.expiry),
        note: form.note.trim() || undefined,
      })
      setForm((f) => ({ ...f, authority: '', orderReference: '', caseText: '', note: '' }))
      setTouched(false)
      onFiled(r)
    } catch (err) {
      if (err.status === 401) onUnauthorized()
      else setError(err)
    } finally {
      setBusy(false)
    }
  }

  if (investigators.length === 0) {
    return (
      <p className="text-sm text-muted">
        No investigator accounts exist. An administrator adds them (role INVESTIGATOR) once an agency's officer has been verified.
      </p>
    )
  }
  return (
    <form onSubmit={submit} className="space-y-4">
      <div className="grid sm:grid-cols-2 gap-4">
        <Field label="Issuing authority" hint="The agency or court named on the order">
          <input value={form.authority} onChange={set('authority')} maxLength={AUTHORITY_MAX} placeholder="Cyber Crime Cell, …" className={INPUT} />
        </Field>
        <Field label="Order reference" hint="The number on the order, to trace it later">
          <input value={form.orderReference} onChange={set('orderReference')} maxLength={REFERENCE_MAX} className={cn(INPUT, 'font-mono')} />
        </Field>
        <Field label="Investigator" hint="The verified officer account the order names">
          <select value={form.investigator} onChange={set('investigator')} className={INPUT}>
            {investigators.map((i) => (
              <option key={i.username} value={i.username}>
                {i.displayName || i.username} ({i.agency || 'no agency'})
              </option>
            ))}
          </select>
        </Field>
        <Field label="Valid until" hint={`Access ends at the end of this day; at most ${MAX_TERM_DAYS} days`}>
          <input type="date" value={form.expiry} onChange={set('expiry')} className={INPUT} />
        </Field>
      </div>
      <Field label="Cases the order covers" hint={cases.length ? `${cases.length} ${cases.length === 1 ? 'case' : 'cases'}: ${cases.join(', ')}` : 'Case numbers, separated by commas or new lines'}>
        <textarea
          value={form.caseText}
          onChange={set('caseText')}
          rows={2}
          placeholder="INV-7K3Q-9XPA"
          className={cn(INPUT, 'h-auto py-2.5 font-mono')}
        />
      </Field>
      <Field label="Note (optional)" hint="How the order was received and checked">
        <input value={form.note} onChange={set('note')} maxLength={NOTE_MAX} className={INPUT} />
      </Field>
      {touched && problem && <Alert tone="warning">{problem}</Alert>}
      {error && <Alert title="Couldn't file the request">{error.message}</Alert>}
      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" icon={FilePlus2} loading={busy}>
          File request
        </Button>
        <p className="text-xs text-subtle">A senior moderator other than you has to approve it before the investigator sees anything.</p>
      </div>
    </form>
  )
}

function RequestRow({ r, me, onDone, onUnauthorized }) {
  const [mode, setMode] = useState(null)
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const act = async (action) => {
    setBusy(true)
    setError(null)
    try {
      const updated = await decideLegalRequest(r.requestNumber, action, note.trim() || undefined)
      setMode(null)
      setNote('')
      onDone(updated, { approve: 'approved', reject: 'rejected', revoke: 'revoked' }[action])
    } catch (err) {
      if (err.status === 401) onUnauthorized()
      else setError(err)
    } finally {
      setBusy(false)
    }
  }
  const ownPending = r.status === 'PENDING' && r.filedBy === me
  return (
    <li className="rounded-xl border border-line bg-white/[0.02] p-4">
      <div className="flex flex-wrap items-center gap-2">
        <Scale className="size-4 text-brand" />
        <span className="font-mono text-sm font-semibold">{r.requestNumber}</span>
        <RequestStatusBadge status={r.status} />
        <span className="text-sm font-medium">{r.authority}</span>
        <span className="text-xs text-muted">
          ref <span className="font-mono">{r.orderReference}</span>
        </span>
        {!mode && (r.canDecide || r.canRevoke) && (
          <div className="flex gap-2 ml-auto">
            {r.canDecide && (
              <>
                <Button size="sm" icon={Check} onClick={() => setMode('approve')}>
                  Approve
                </Button>
                <Button size="sm" variant="ghost" icon={X} onClick={() => setMode('reject')}>
                  Reject
                </Button>
              </>
            )}
            {r.canRevoke && (
              <Button size="sm" variant="ghost" icon={ShieldOff} onClick={() => setMode('revoke')}>
                Revoke
              </Button>
            )}
          </div>
        )}
      </div>
      <p className="text-xs text-muted mt-2">
        <UserCheck className="inline size-3.5 mr-1 text-subtle" />
        {r.investigator} may read{' '}
        {r.cases.map((c, i) => (
          <span key={c.caseNumber}>
            {i > 0 && ', '}
            <Link to={`/investigations/${c.caseNumber}`} className="font-mono hover:text-brand" title={c.title}>
              {c.caseNumber}
            </Link>
          </span>
        ))}{' '}
        until <span title={dateTime(r.expiresAt)}>{dateTime(r.expiresAt)}</span>
        {r.status === 'APPROVED' && <span className="text-subtle"> ({timeLeft(r.expiresAt)})</span>}
      </p>
      <p className="text-xs text-subtle mt-1">
        Filed by {r.filedBy} {timeAgo(r.filedAt)}
        {r.note && <> · {r.note}</>}
        {r.decidedBy && (
          <>
            {' '}
            · {r.status === 'REJECTED' ? 'rejected' : 'approved'} by {r.decidedBy} {timeAgo(r.decidedAt)}
            {r.decisionNote && `: ${r.decisionNote}`}
          </>
        )}
        {r.revokedBy && (
          <>
            {' '}
            · revoked by {r.revokedBy} {timeAgo(r.revokedAt)}: {r.revokeReason}
          </>
        )}
      </p>
      {ownPending && <p className="text-xs text-amber-200/90 mt-2">You filed this, so a different senior moderator has to decide it.</p>}
      {r.status === 'PENDING' && !r.canDecide && !ownPending && (
        <p className="text-xs text-subtle mt-2">Waiting for a senior moderator.</p>
      )}
      {mode && (
        <form
          onSubmit={(e) => {
            e.preventDefault()
            act(mode)
          }}
          className="flex flex-wrap items-center gap-2 mt-3"
        >
          <input
            value={note}
            onChange={(e) => setNote(e.target.value)}
            maxLength={NOTE_MAX}
            required={mode !== 'approve'}
            placeholder={mode === 'approve' ? 'What you checked (optional)' : mode === 'reject' ? 'Why it is rejected' : 'Why access ends now'}
            className={cn(INPUT, 'flex-1 min-w-48')}
            autoFocus
          />
          <Button type="submit" size="sm" variant={mode === 'approve' ? 'primary' : 'danger'} loading={busy}>
            {mode === 'approve' ? 'Approve access' : mode === 'reject' ? 'Reject' : 'Revoke access'}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setMode(null)}>
            Cancel
          </Button>
        </form>
      )}
      {error && <p className="text-xs text-rose-300 mt-2">{error.message}</p>}
    </li>
  )
}

function Section({ title, empty, requests, me, onDone, onUnauthorized }) {
  return (
    <Card className="p-5">
      <p className="text-sm font-medium">{title}</p>
      {requests.length === 0 ? (
        <p className="text-sm text-muted mt-1">{empty}</p>
      ) : (
        <ul className="space-y-3 mt-3">
          {requests.map((r) => (
            <RequestRow key={r.requestNumber} r={r} me={me} onDone={onDone} onUnauthorized={onUnauthorized} />
          ))}
        </ul>
      )}
    </Card>
  )
}

function Requests({ me, onUnauthorized }) {
  const toast = useToast()
  const [params] = useSearchParams()
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const load = useCallback(async () => {
    setError(null)
    try {
      const [requests, investigators] = await Promise.all([fetchLegalRequests(), fetchInvestigatorAccounts()])
      setData({ requests, investigators })
    } catch (err) {
      if (err.status === 401 || err.status === 403) onUnauthorized()
      else setError(err)
    }
  }, [onUnauthorized])
  useEffect(() => {
    load()
  }, [load])

  if (error) return <ErrorState error={error} onRetry={load} />
  if (!data) return <LoadingState />
  const g = groupRequests(data.requests)
  const onDone = (r, verb) => {
    toast(`${r.requestNumber} ${verb}.`)
    load()
  }
  return (
    <>
      <PageHeader
        eyebrow="Investigator handover"
        title="Legal requests"
        description="An authorised investigator reads a case only under a request recorded here and approved by a second, senior moderator. Access is read-only, ends at the expiry or on revocation, and every read is written to the case's custody log."
        actions={
          <Button variant="secondary" icon={RefreshCw} onClick={load}>
            Refresh
          </Button>
        }
      />
      <div className="grid sm:grid-cols-3 gap-4 mb-6">
        <StatTile icon={Scale} label="Awaiting approval" value={g.pending.length} accent="text-amber-300" />
        <StatTile icon={UserCheck} label="Active" value={g.active.length} accent="text-emerald-300" />
        <StatTile icon={ShieldOff} label="Ended" value={g.closed.length} />
      </div>
      <div className="grid lg:grid-cols-[1fr_1.1fr] gap-6 items-start">
        <Card className="p-5">
          <CardHeader icon={FilePlus2} title="Record an order" description="Enter it as received from the agency, after checking it came from them." />
          <FileForm
            investigators={data.investigators}
            initialCase={params.get('case') || ''}
            onUnauthorized={onUnauthorized}
            onFiled={(r) => {
              toast(`${r.requestNumber} filed. A different senior moderator now has to approve it.`)
              load()
            }}
          />
        </Card>
        <div className="space-y-6">
          <Section title="Awaiting approval" empty="Nothing waiting." requests={g.pending} me={me} onDone={onDone} onUnauthorized={onUnauthorized} />
          <Section title="Active" empty="No investigator has access right now." requests={g.active} me={me} onDone={onDone} onUnauthorized={onUnauthorized} />
          {g.closed.length > 0 && (
            <Section title="Ended" empty="" requests={g.closed} me={me} onDone={onDone} onUnauthorized={onUnauthorized} />
          )}
        </div>
      </div>
    </>
  )
}

export default function LegalRequests() {
  const { isAuthenticated, loading, user, logout } = useAuth()
  const location = useLocation()
  if (!loading && !isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }
  if (!isAuthenticated) return <LoadingState />
  return <Requests me={user?.username} onUnauthorized={logout} />
}
