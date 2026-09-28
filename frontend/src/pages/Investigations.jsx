import { useCallback, useEffect, useState } from 'react'
import { Link, Navigate, useLocation, useNavigate, useParams } from 'react-router-dom'
import {
  ArrowLeft,
  CircleDot,
  FileCheck2,
  FolderSearch,
  Globe,
  History,
  Link2,
  Lock,
  LockOpen,
  MapPin,
  NotebookPen,
  Plus,
  Radar,
  RefreshCw,
  Search,
  ShieldCheck,
  Users,
} from 'lucide-react'
import {
  addInvestigationItem,
  addInvestigationNote,
  closeInvestigation,
  fetchInvestigation,
  fetchInvestigations,
  openInvestigation,
  recordSighting,
  reopenInvestigation,
} from '../api/client'
import { useAuth } from '../auth/AuthContext'
import FamilyChip from '../components/FamilyChip'
import { ErrorState, LoadingState } from '../components/Status'
import { useToast } from '../components/Toast'
import { Alert, Button, Card, CardHeader, CopyButton, DecisionBadge, EmptyState, PageHeader, StatTile } from '../components/ui'
import { cn, dateTime, score, timeAgo } from '../lib/format'
import {
  ACCOUNT_ID_MAX,
  NOTE_MAX,
  SOURCE,
  TIMELINE_KINDS,
  VIA,
  eventLabel,
  groupByDay,
  matchLabel,
  normalizeInvestigationNumber,
  normalizePlatform,
  postRefProblem,
} from '../lib/investigations'

const INPUT =
  'w-full h-10 rounded-xl border border-line-strong bg-canvas/60 px-3.5 text-sm placeholder:text-subtle focus:outline-none focus:border-brand/60 focus:ring-4 focus:ring-brand/15'

const STATUS_FILTERS = [
  { key: 'open', label: 'Open' },
  { key: 'closed', label: 'Closed' },
  { key: 'all', label: 'All' },
]

function StatusBadge({ status }) {
  const open = status === 'OPEN'
  const Icon = open ? LockOpen : Lock
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-semibold',
        open ? 'border-emerald-400/30 text-emerald-200' : 'border-line-strong text-muted',
      )}
    >
      <Icon className="size-3.5" />
      {open ? 'Open' : 'Closed'}
    </span>
  )
}

function Field({ label, hint, error, children }) {
  return (
    <label className="block">
      <span className="text-xs font-medium text-muted">{label}</span>
      <div className="mt-1.5">{children}</div>
      {error ? <p className="text-xs text-rose-300 mt-1">{error}</p> : hint && <p className="text-xs text-subtle mt-1">{hint}</p>}
    </label>
  )
}

/** Handles the common shape of a case mutation: busy flag, plain-language error, sign-out on 401/403. */
function useAction(onUnauthorized) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const run = useCallback(
    async (fn) => {
      setBusy(true)
      setError(null)
      try {
        return await fn()
      } catch (err) {
        if (err.status === 401 || err.status === 403) onUnauthorized()
        else setError(err)
        return null
      } finally {
        setBusy(false)
      }
    },
    [onUnauthorized],
  )
  return { busy, error, run, setError }
}

// ------------------------------------------------------------------ list

function OpenCaseForm({ onOpened, onUnauthorized }) {
  const [analysisId, setAnalysisId] = useState('')
  const [title, setTitle] = useState('')
  const { busy, error, run } = useAction(onUnauthorized)
  const id = Number.parseInt(analysisId, 10)
  const valid = Number.isInteger(id) && id > 0

  const submit = async (e) => {
    e.preventDefault()
    if (!valid) return
    const created = await run(() => openInvestigation(id, title.trim() || undefined))
    if (created) onOpened(created)
  }

  return (
    <Card className="p-5">
      <CardHeader icon={Plus} title="Open an investigation" description="Start from any analysis. Sightings already reported for that media are linked straight away." />
      <form onSubmit={submit} className="grid sm:grid-cols-[10rem_1fr_auto] gap-3 items-end">
        <Field label="Analysis ID">
          <input value={analysisId} onChange={(e) => setAnalysisId(e.target.value.replace(/\D/g, ''))} inputMode="numeric" placeholder="e.g. 42" className={cn(INPUT, 'font-mono')} />
        </Field>
        <Field label="Title (optional)">
          <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={160} placeholder="What is being investigated" className={INPUT} />
        </Field>
        <Button type="submit" icon={FolderSearch} loading={busy} disabled={!valid}>
          Open case
        </Button>
      </form>
      {error && (
        <div className="mt-3">
          <Alert title="Couldn't open the case">{error.message}</Alert>
        </div>
      )}
    </Card>
  )
}

function CaseCard({ c }) {
  return (
    <Link to={`/investigations/${c.caseNumber}`} className="block group">
      <Card className="p-5 transition-colors group-hover:border-white/20">
        <div className="flex flex-wrap items-center gap-2 mb-2">
          <span className="font-mono text-sm font-semibold">{c.caseNumber}</span>
          <StatusBadge status={c.status} />
          <span className="text-xs text-subtle ml-auto" title={dateTime(c.lastActivityAt)}>
            Active {timeAgo(c.lastActivityAt)}
          </span>
        </div>
        <p className="font-medium group-hover:text-brand transition-colors">{c.title}</p>
        <div className="flex flex-wrap gap-x-5 gap-y-1 text-xs text-muted mt-3">
          <span>
            <FileCheck2 className="inline size-3.5 mr-1 text-brand" />
            {c.items} {c.items === 1 ? 'item' : 'items'}
          </span>
          <span>
            <MapPin className="inline size-3.5 mr-1 text-brand" />
            {c.sightings} {c.sightings === 1 ? 'sighting' : 'sightings'}
          </span>
          <span>
            <Globe className="inline size-3.5 mr-1 text-brand" />
            {c.platforms} {c.platforms === 1 ? 'platform' : 'platforms'}
          </span>
          <span>
            <Users className="inline size-3.5 mr-1 text-brand" />
            {c.accounts} {c.accounts === 1 ? 'account' : 'accounts'}
          </span>
          <span className="text-subtle">
            Opened by {c.openedBy} {timeAgo(c.openedAt)}
          </span>
        </div>
      </Card>
    </Link>
  )
}

function CaseList({ onUnauthorized }) {
  const navigate = useNavigate()
  const toast = useToast()
  const [status, setStatus] = useState('open')
  const [cases, setCases] = useState(null)
  const [error, setError] = useState(null)
  const [jump, setJump] = useState('')

  const load = useCallback(async () => {
    try {
      setCases(await fetchInvestigations({ status }))
      setError(null)
    } catch (err) {
      if (err.status === 401 || err.status === 403) return onUnauthorized()
      setError(err)
    }
  }, [status, onUnauthorized])

  useEffect(() => {
    load()
  }, [load])

  const filter = (key) => {
    if (key === status) return
    setCases(null)
    setStatus(key)
  }

  const jumpTo = normalizeInvestigationNumber(jump)

  return (
    <>
      <PageHeader
        eyebrow="Investigation lifecycle"
        title="Investigations"
        description="Follow a piece of media past the verdict: where it appeared on partner platforms, which accounts posted it, and how the evidence builds up. Accounts are pseudonyms; no media is stored."
        actions={
          <form
            onSubmit={(e) => {
              e.preventDefault()
              if (jumpTo) navigate(`/investigations/${jumpTo}`)
            }}
            className="flex gap-2"
          >
            <input value={jump} onChange={(e) => setJump(e.target.value)} placeholder="INV-XXXX-XXXX" aria-label="Case number" className={cn(INPUT, 'w-44 font-mono')} />
            <Button type="submit" variant="secondary" icon={Search} disabled={!jumpTo}>
              Go
            </Button>
          </form>
        }
      />

      <div className="mb-8">
        <OpenCaseForm
          onUnauthorized={onUnauthorized}
          onOpened={(c) => {
            toast(`${c.caseNumber} opened with ${c.counts.sightings} linked ${c.counts.sightings === 1 ? 'sighting' : 'sightings'}.`)
            navigate(`/investigations/${c.caseNumber}`)
          }}
        />
      </div>

      <div className="flex items-center gap-2 mb-4">
        {STATUS_FILTERS.map((f) => (
          <Button key={f.key} size="sm" variant={status === f.key ? 'primary' : 'secondary'} onClick={() => filter(f.key)}>
            {f.label}
          </Button>
        ))}
        <Button size="sm" variant="ghost" icon={RefreshCw} onClick={load} className="ml-auto">
          Refresh
        </Button>
      </div>

      {error && <ErrorState error={error} title="Couldn't load investigations" onRetry={load} className="mb-6" />}
      {!cases ? (
        error ? null : <LoadingState label="Loading investigations…" />
      ) : cases.length === 0 ? (
        <EmptyState
          icon={FolderSearch}
          title={status === 'closed' ? 'No closed investigations' : 'No investigations yet'}
          description="Open one from an analysis ID above, or with Investigate on any item in the moderation queue."
        />
      ) : (
        <div className="space-y-3">
          {cases.map((c, i) => (
            <div key={c.caseNumber} className="animate-fade-up" style={{ animationDelay: `${Math.min(i, 8) * 40}ms` }}>
              <CaseCard c={c} />
            </div>
          ))}
        </div>
      )}
    </>
  )
}

// ------------------------------------------------------------------ detail

function EvidenceCard({ e }) {
  return (
    <div className="rounded-xl border border-line bg-white/[0.02] p-4">
      <div className="flex flex-wrap items-center gap-2 mb-3">
        {e.provenanceId ? (
          <Link to={`/verify/${e.provenanceId}`} title="Provenance trail" className="font-mono font-semibold hover:text-brand transition-colors">
            #{e.analysisId}
          </Link>
        ) : (
          <span className="font-mono font-semibold">#{e.analysisId}</span>
        )}
        <DecisionBadge decision={e.decision} size="sm" />
        <FamilyChip family={e.generationFamily} />
        <span className="text-xs text-subtle ml-auto">
          Added by {e.addedBy} {timeAgo(e.addedAt)}
        </span>
      </div>
      <dl className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs">
        <div>
          <dt className="text-subtle">Forgery</dt>
          <dd className="font-mono text-sm">{score(e.forgeryScore)}</dd>
        </div>
        <div>
          <dt className="text-subtle">Explicit</dt>
          <dd className="font-mono text-sm">{score(e.explicitScore)}</dd>
        </div>
        <div>
          <dt className="text-subtle">pHash</dt>
          <dd className="font-mono text-sm truncate">{e.phash || '—'}</dd>
        </div>
        <div>
          <dt className="text-subtle">Signals</dt>
          <dd className="text-sm">
            {[e.hasForensics && 'Forensics', e.hasContentCredentials && 'Content Credentials'].filter(Boolean).join(' · ') || '—'}
          </dd>
        </div>
      </dl>
      {e.reportId && (
        <p className="flex items-center gap-1.5 text-xs text-muted mt-3">
          <ShieldCheck className="size-3.5 text-emerald-300" />
          Signed report <span className="font-mono">{e.reportId}</span>
          <CopyButton value={e.reportId} className="size-6" />
          {e.reportSignedAt && <span className="text-subtle">· {timeAgo(e.reportSignedAt)}</span>}
        </p>
      )}
      {(e.complaintType || e.appealStatus) && (
        <p className="text-xs text-subtle mt-2">
          {e.complaintType && <>Complaint: {e.complaintType.replace(/_/g, ' ').toLowerCase()}{e.slaStatus && ` (${e.slaStatus.replace(/_/g, ' ').toLowerCase()})`}</>}
          {e.complaintType && e.appealStatus && ' · '}
          {e.appealStatus && <>Appeal: {e.appealStatus.toLowerCase()}</>}
        </p>
      )}
    </div>
  )
}

function AddItemForm({ caseNumber, onDone, onUnauthorized }) {
  const [analysisId, setAnalysisId] = useState('')
  const { busy, error, run } = useAction(onUnauthorized)
  const id = Number.parseInt(analysisId, 10)
  const submit = async (e) => {
    e.preventDefault()
    const updated = await run(() => addInvestigationItem(caseNumber, id))
    if (updated) {
      setAnalysisId('')
      onDone(updated, `Analysis #${id} added.`)
    }
  }
  return (
    <form onSubmit={submit} className="mt-4">
      <div className="flex gap-2">
        <input value={analysisId} onChange={(e) => setAnalysisId(e.target.value.replace(/\D/g, ''))} inputMode="numeric" placeholder="Another analysis ID" aria-label="Analysis ID" className={cn(INPUT, 'font-mono max-w-48')} />
        <Button type="submit" variant="secondary" icon={Plus} loading={busy} disabled={!(id > 0)}>
          Add evidence
        </Button>
      </div>
      {error && <p className="text-xs text-rose-300 mt-2">{error.message}</p>}
    </form>
  )
}

function SightingForm({ caseNumber, evidence, onDone, onUnauthorized }) {
  const [form, setForm] = useState({ analysisId: evidence[0]?.analysisId ?? '', platform: '', postRef: '', accountId: '', observedAt: '' })
  const [touched, setTouched] = useState(false)
  const { busy, error, run } = useAction(onUnauthorized)
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }))

  const platform = normalizePlatform(form.platform)
  const postProblem = postRefProblem(form.postRef)
  const accountTooLong = form.accountId.trim().length > ACCOUNT_ID_MAX
  const valid = platform && !postProblem && !accountTooLong

  const submit = async (e) => {
    e.preventDefault()
    setTouched(true)
    if (!valid) return
    const updated = await run(() =>
      recordSighting(caseNumber, {
        analysisId: form.analysisId ? Number(form.analysisId) : undefined,
        platform,
        postRef: form.postRef.trim(),
        accountId: form.accountId.trim() || undefined,
        observedAt: form.observedAt ? new Date(form.observedAt).toISOString() : undefined,
      }),
    )
    if (updated) {
      setForm((f) => ({ ...f, postRef: '', accountId: '', observedAt: '' }))
      setTouched(false)
      onDone(updated, `Sighting on ${platform} recorded.`)
    }
  }

  return (
    <form onSubmit={submit} className="rounded-xl border border-line bg-white/[0.02] p-4 mt-5 space-y-3">
      <p className="text-sm font-medium">Record a sighting you were told about</p>
      <div className="grid sm:grid-cols-2 gap-3">
        {evidence.length > 1 && (
          <Field label="Which item">
            <select value={form.analysisId} onChange={set('analysisId')} className={INPUT}>
              {evidence.map((e) => (
                <option key={e.analysisId} value={e.analysisId}>
                  Analysis #{e.analysisId}
                </option>
              ))}
            </select>
          </Field>
        )}
        <Field label="Platform" hint={platform && platform !== form.platform.trim() ? `Saved as "${platform}"` : 'e.g. pixelgram'} error={touched && !platform ? 'Letters, digits, dots and dashes only.' : null}>
          <input value={form.platform} onChange={set('platform')} maxLength={48} className={INPUT} />
        </Field>
        <Field label="Post ID" hint="The platform's own ID for the post, not a link." error={touched ? postProblem : null}>
          <input value={form.postRef} onChange={set('postRef')} maxLength={128} className={cn(INPUT, 'font-mono')} />
        </Field>
        <Field label="Account ID (optional)" hint="Stored only as a keyed pseudonym, never as typed." error={accountTooLong ? `At most ${ACCOUNT_ID_MAX} characters.` : null}>
          <input value={form.accountId} onChange={set('accountId')} autoComplete="off" className={cn(INPUT, 'font-mono')} />
        </Field>
        <Field label="Seen at (optional)" hint="Defaults to now.">
          <input type="datetime-local" value={form.observedAt} onChange={set('observedAt')} className={INPUT} />
        </Field>
      </div>
      {error && <Alert title="Couldn't record the sighting">{error.message}</Alert>}
      <Button type="submit" icon={MapPin} loading={busy}>
        Record sighting
      </Button>
    </form>
  )
}

function SightingsTable({ sightings }) {
  if (sightings.length === 0) {
    return <p className="text-sm text-muted">No sightings linked yet. Partners report them through the API; you can also record one below.</p>
  }
  return (
    <div className="overflow-x-auto -mx-1">
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left text-xs text-subtle">
            <th className="font-medium px-1 pb-2">Platform</th>
            <th className="font-medium px-1 pb-2">Post</th>
            <th className="font-medium px-1 pb-2">Account</th>
            <th className="font-medium px-1 pb-2">Seen</th>
            <th className="font-medium px-1 pb-2">Reported by</th>
            <th className="font-medium px-1 pb-2">Match</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {sightings.map((s) => (
            <tr key={s.id}>
              <td className="px-1 py-2 font-medium">{s.platform}</td>
              <td className="px-1 py-2 font-mono text-xs">{s.postRef}</td>
              <td className="px-1 py-2 font-mono text-xs">{s.account || <span className="text-subtle">—</span>}</td>
              <td className="px-1 py-2 text-xs" title={dateTime(s.observedAt)}>
                {timeAgo(s.observedAt)}
              </td>
              <td className="px-1 py-2 text-xs">
                {s.reporter || SOURCE[s.source]}
                <span className="text-subtle"> · {SOURCE[s.source] || s.source}</span>
              </td>
              <td className="px-1 py-2 text-xs" title={VIA[s.via]?.hint}>
                <Link2 className="inline size-3.5 mr-1 text-brand" />
                {matchLabel(s.via, s.distance)}
                {s.matchedAnalysisId && <span className="text-subtle"> · #{s.matchedAnalysisId}</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function AccountsList({ accounts }) {
  if (accounts.length === 0) return <p className="text-sm text-muted">No accounts named in the linked sightings.</p>
  return (
    <div className="space-y-3">
      {accounts.map((a) => (
        <div key={`${a.platform}:${a.account}`} className="rounded-xl border border-line bg-white/[0.02] p-4">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-mono text-sm font-semibold">{a.account}</span>
            <span className="text-xs text-muted">on {a.platform}</span>
            <span className="text-xs text-subtle ml-auto">
              {a.sightings} here · {a.totalSightings} on record
            </span>
          </div>
          <p className="text-xs text-subtle mt-1.5">
            {a.posts} {a.posts === 1 ? 'post' : 'posts'} · first seen {timeAgo(a.firstSeen)} · last seen {timeAgo(a.lastSeen)}
          </p>
          {a.otherCases.length > 0 && (
            <div className="flex flex-wrap items-center gap-2 mt-3 text-xs">
              <span className="text-amber-200/90">Also in</span>
              {a.otherCases.map((o) => (
                <Link key={o.caseNumber} to={`/investigations/${o.caseNumber}`} title={o.title} className="font-mono rounded-lg border border-amber-400/30 px-2 py-0.5 text-amber-100 hover:bg-amber-400/10">
                  {o.caseNumber}
                  {o.status === 'CLOSED' && <span className="text-subtle"> (closed)</span>}
                </Link>
              ))}
            </div>
          )}
        </div>
      ))}
    </div>
  )
}

function Appearances({ appearances, phashMaxDistance }) {
  if (appearances.length === 0) {
    return <p className="text-sm text-muted">No other uploads of this media to this platform (within {phashMaxDistance} bits of pHash distance).</p>
  }
  return (
    <ul className="divide-y divide-line">
      {appearances.map((a) => (
        <li key={a.analysisId} className="flex flex-wrap items-center gap-2 py-2 text-sm">
          {a.provenanceId ? (
            <Link to={`/verify/${a.provenanceId}`} className="font-mono font-semibold hover:text-brand">
              #{a.analysisId}
            </Link>
          ) : (
            <span className="font-mono font-semibold">#{a.analysisId}</span>
          )}
          <DecisionBadge decision={a.decision} size="sm" />
          <span className="text-xs text-muted">{a.channel === 'PARTNER_API' ? `via ${a.partner || 'partner API'}` : 'direct upload'}</span>
          <span className="text-xs text-subtle" title={VIA[a.via]?.hint}>
            · {matchLabel(a.via, a.distance)} of #{a.matchedAnalysisId}
          </span>
          <span className="text-xs text-subtle ml-auto" title={dateTime(a.createdAt)}>
            {timeAgo(a.createdAt)}
          </span>
        </li>
      ))}
    </ul>
  )
}

const KIND_DOT = {
  case: 'bg-brand',
  provenance: 'bg-sky-400',
  sighting: 'bg-amber-400',
  appearance: 'bg-violet-400',
}

function Timeline({ timeline }) {
  const groups = groupByDay(timeline)
  return (
    <div className="space-y-5">
      {groups.map((g) => (
        <div key={g.day}>
          <p className="text-xs font-semibold uppercase tracking-wider text-subtle mb-2">{g.day}</p>
          <ol className="space-y-2.5 border-l border-line pl-4">
            {g.entries.map((e, i) => (
              <li key={`${e.at}-${e.kind}-${i}`} className="relative">
                <span className={cn('absolute -left-[21px] top-1.5 size-2 rounded-full', KIND_DOT[e.kind] || 'bg-muted')} />
                <div className="flex flex-wrap items-baseline gap-x-2 text-sm">
                  <span className="font-medium">{eventLabel(e)}</span>
                  <span className="text-[11px] uppercase tracking-wide text-subtle">{TIMELINE_KINDS[e.kind] || e.kind}</span>
                  <span className="text-xs text-subtle ml-auto" title={dateTime(e.at)}>
                    {new Date(e.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                    {e.actor && ` · ${e.actor}`}
                  </span>
                </div>
                {e.detail && <p className="text-xs text-muted mt-0.5 break-words">{e.detail}</p>}
              </li>
            ))}
          </ol>
        </div>
      ))}
    </div>
  )
}

function NoteForm({ caseNumber, onDone, onUnauthorized }) {
  const [text, setText] = useState('')
  const { busy, error, run } = useAction(onUnauthorized)
  const submit = async (e) => {
    e.preventDefault()
    const updated = await run(() => addInvestigationNote(caseNumber, text.trim()))
    if (updated) {
      setText('')
      onDone(updated, 'Note added.')
    }
  }
  return (
    <form onSubmit={submit} className="mb-5">
      <textarea
        value={text}
        onChange={(e) => setText(e.target.value)}
        maxLength={NOTE_MAX}
        rows={2}
        placeholder="Add a note to the case log"
        className={cn(INPUT, 'h-auto py-2.5 resize-y')}
      />
      <div className="flex items-center gap-3 mt-2">
        <Button type="submit" size="sm" variant="secondary" icon={NotebookPen} loading={busy} disabled={!text.trim()}>
          Add note
        </Button>
        <span className="text-xs text-subtle">
          {text.length}/{NOTE_MAX}
        </span>
        {error && <span className="text-xs text-rose-300">{error.message}</span>}
      </div>
    </form>
  )
}

function CloseControls({ c, onDone, onUnauthorized }) {
  const [closing, setClosing] = useState(false)
  const [reason, setReason] = useState('')
  const { busy, error, run } = useAction(onUnauthorized)
  const open = c.status === 'OPEN'

  if (!open) {
    return (
      <Button variant="secondary" icon={LockOpen} loading={busy} onClick={async () => {
        const updated = await run(() => reopenInvestigation(c.caseNumber))
        if (updated) onDone(updated, `${c.caseNumber} reopened; sightings reported meanwhile are now linked.`)
      }}>
        Reopen
      </Button>
    )
  }
  if (!closing) {
    return (
      <Button variant="secondary" icon={Lock} onClick={() => setClosing(true)}>
        Close case
      </Button>
    )
  }
  return (
    <form
      className="flex flex-wrap items-center gap-2"
      onSubmit={async (e) => {
        e.preventDefault()
        const updated = await run(() => closeInvestigation(c.caseNumber, reason.trim() || undefined))
        if (updated) {
          setClosing(false)
          onDone(updated, `${c.caseNumber} closed. New sightings won't be linked until it is reopened.`)
        }
      }}
    >
      <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={NOTE_MAX} placeholder="Reason (optional)" className={cn(INPUT, 'w-56')} autoFocus />
      <Button type="submit" variant="danger" icon={Lock} loading={busy}>
        Close
      </Button>
      <Button variant="ghost" onClick={() => setClosing(false)}>
        Cancel
      </Button>
      {error && <span className="basis-full text-xs text-rose-300">{error.message}</span>}
    </form>
  )
}

function CaseDetail({ caseNumber, onUnauthorized }) {
  const toast = useToast()
  const [c, setCase] = useState(null)
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    try {
      setCase(await fetchInvestigation(caseNumber))
      setError(null)
    } catch (err) {
      if (err.status === 401 || err.status === 403) return onUnauthorized()
      setError(err)
    }
  }, [caseNumber, onUnauthorized])

  useEffect(() => {
    load()
  }, [load])

  const onDone = useCallback(
    (updated, message) => {
      setCase(updated)
      if (message) toast(message)
    },
    [toast],
  )

  const back = (
    <Link to="/investigations" className="inline-flex items-center gap-1.5 text-sm text-muted hover:text-fg mb-5">
      <ArrowLeft className="size-4" /> All investigations
    </Link>
  )

  if (!c) {
    return (
      <>
        {back}
        {error ? (
          <ErrorState error={error} title={error.status === 404 ? `No investigation ${caseNumber}` : "Couldn't load the investigation"} onRetry={error.status === 404 ? undefined : load} />
        ) : (
          <LoadingState label="Loading the case…" />
        )}
      </>
    )
  }

  const open = c.status === 'OPEN'
  return (
    <>
      {back}
      <PageHeader
        eyebrow={c.caseNumber}
        title={c.title}
        description={
          open
            ? `Opened by ${c.openedBy} ${timeAgo(c.openedAt)}. New sightings of this media within ${c.phashMaxDistance} bits of pHash distance, or carrying its provenance ID, are linked as they arrive.`
            : `Closed by ${c.closedBy} ${timeAgo(c.closedAt)}${c.closeReason ? `: ${c.closeReason}` : ''}. Reopen to link sightings reported since.`
        }
        actions={
          <>
            <StatusBadge status={c.status} />
            <Button variant="ghost" icon={RefreshCw} onClick={load}>
              Refresh
            </Button>
            <CloseControls c={c} onDone={onDone} onUnauthorized={onUnauthorized} />
          </>
        }
      />

      {error && <ErrorState error={error} title="Couldn't refresh" onRetry={load} className="mb-6" />}

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-8 animate-fade-up">
        <StatTile icon={FileCheck2} label="Evidence" value={c.counts.evidence} hint="Analyses in the case" />
        <StatTile icon={MapPin} label="Sightings" value={c.counts.sightings} accent="text-amber-300" hint="Linked partner and moderator reports" />
        <StatTile icon={Globe} label="Platforms" value={c.counts.platforms} hint={`${c.counts.appearances} more ${c.counts.appearances === 1 ? 'upload' : 'uploads'} here`} />
        <StatTile icon={Users} label="Accounts" value={c.counts.accounts} accent="text-sky-300" hint="Keyed pseudonyms, per platform" />
      </div>

      <div className="grid lg:grid-cols-5 gap-6">
        <div className="lg:col-span-3 space-y-6">
          <Card className="p-5">
            <CardHeader icon={FileCheck2} title="Evidence" description="Is it manipulated, and what supports that: scores, forensic signals and signed reports." />
            <div className="space-y-3">
              {c.evidence.map((e) => (
                <EvidenceCard key={e.analysisId} e={e} />
              ))}
            </div>
            {open && <AddItemForm caseNumber={c.caseNumber} onDone={onDone} onUnauthorized={onUnauthorized} />}
          </Card>

          <Card className="p-5">
            <CardHeader icon={Radar} title="Where it appeared" description="Sightings reported by partner platforms or recorded by moderators. Only opaque post IDs are kept, never links." />
            {c.platforms.length > 0 && (
              <div className="flex flex-wrap gap-2 mb-4">
                {c.platforms.map((p) => (
                  <span key={p.platform} className="inline-flex items-center gap-1.5 rounded-lg border border-line bg-white/[0.03] px-2.5 py-1 text-xs" title={`First seen ${dateTime(p.firstSeen)}`}>
                    <Globe className="size-3.5 text-brand" />
                    <span className="font-medium">{p.platform}</span>
                    <span className="text-subtle">
                      {p.sightings} · {p.accounts} {p.accounts === 1 ? 'account' : 'accounts'}
                    </span>
                  </span>
                ))}
              </div>
            )}
            <SightingsTable sightings={c.sightings} />
            {open && c.evidence.length > 0 && <SightingForm caseNumber={c.caseNumber} evidence={c.evidence} onDone={onDone} onUnauthorized={onUnauthorized} />}
          </Card>

          <Card className="p-5">
            <CardHeader icon={CircleDot} title="On this platform" description="Other uploads here: re-uploads traced through provenance, or near-identical by perceptual hash." />
            <Appearances appearances={c.appearances} phashMaxDistance={c.phashMaxDistance} />
          </Card>
        </div>

        <div className="lg:col-span-2 space-y-6">
          <Card className="p-5">
            <CardHeader icon={Users} title="Accounts" description="Who posted it, as pseudonyms. An account seen in other cases is flagged." />
            <AccountsList accounts={c.accounts} />
          </Card>

          <Card className="p-5">
            <CardHeader icon={History} title="Timeline" description="Case log, provenance trail and sightings in one place." />
            {open && <NoteForm caseNumber={c.caseNumber} onDone={onDone} onUnauthorized={onUnauthorized} />}
            <Timeline timeline={c.timeline} />
          </Card>
        </div>
      </div>
    </>
  )
}

export default function Investigations() {
  const { isAuthenticated, loading: authLoading, logout } = useAuth()
  const { caseNumber } = useParams()
  const location = useLocation()

  if (!authLoading && !isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  if (!isAuthenticated) return <LoadingState />

  const normalized = caseNumber ? normalizeInvestigationNumber(caseNumber) : null
  if (caseNumber && normalized && normalized !== caseNumber) {
    return <Navigate to={`/investigations/${normalized}`} replace />
  }
  return caseNumber ? (
    <CaseDetail key={caseNumber} caseNumber={caseNumber} onUnauthorized={logout} />
  ) : (
    <CaseList onUnauthorized={logout} />
  )
}
