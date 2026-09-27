import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  ArrowRight,
  Check,
  FileSearch,
  Fingerprint,
  History,
  ImageUp,
  Link2,
  Repeat2,
  Search,
  ShieldQuestion,
  Stamp,
  UploadCloud,
  X,
} from 'lucide-react'
import { fetchProvenance, verifyUpload } from '../api/client'
import { Alert, Button, Card, CardHeader, CopyButton, DecisionBadge, EmptyState, PageHeader } from '../components/ui'
import { cn, dateTime, score, timeAgo } from '../lib/format'
import { EVENT_META, MATCH_VIA, manifestStatus, shortId } from '../lib/provenance'
import { COMPLAINT_TYPES, SLA_STATUS } from '../lib/sla'

const ID_PATTERN = /^[A-Za-z0-9-]{1,64}$/

function Fact({ label, children, mono }) {
  return (
    <div className="rounded-xl border border-line bg-white/[0.02] px-3.5 py-2.5 min-w-0">
      <dt className="text-xs text-muted">{label}</dt>
      <dd className={cn('text-sm mt-0.5 truncate', mono && 'font-mono')}>{children}</dd>
    </div>
  )
}

function IdRow({ icon: Icon, label, value, to }) {
  return (
    <div className="flex items-center gap-3 rounded-xl border border-line bg-white/[0.02] px-3.5 py-2.5">
      <Icon className="size-4 text-muted shrink-0" />
      <div className="min-w-0 flex-1">
        <p className="text-xs text-muted">{label}</p>
        {to ? (
          <Link to={to} className="block font-mono text-sm truncate text-brand hover:underline">
            {value}
          </Link>
        ) : (
          <p className="font-mono text-sm truncate">{value || '—'}</p>
        )}
      </div>
      {value && <CopyButton value={value} />}
    </div>
  )
}

function Tick({ ok, yes, no }) {
  return (
    <span className={cn('inline-flex items-center gap-1.5', ok ? 'text-emerald-300' : 'text-rose-300')}>
      {ok ? <Check className="size-3.5" /> : <X className="size-3.5" />}
      {ok ? yes : no}
    </span>
  )
}

function LookupCard({ initial }) {
  const navigate = useNavigate()
  const [value, setValue] = useState(initial || '')
  const trimmed = value.trim()
  const valid = ID_PATTERN.test(trimmed)

  return (
    <Card className="p-5 md:p-6">
      <CardHeader icon={Search} title="Look up an ID" description="Paste the provenance ID from a result, a report, or a labelled file." />
      <form
        onSubmit={(e) => {
          e.preventDefault()
          if (valid) navigate(`/verify/${trimmed}`)
        }}
        className="flex gap-2"
      >
        <input
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder="e.g. 3f2b6c1e-…"
          spellCheck={false}
          aria-label="Provenance ID"
          className="min-w-0 flex-1 h-10 rounded-xl border border-line-strong bg-white/[0.03] px-3.5 font-mono text-sm placeholder:text-subtle focus:outline-none focus:border-brand/60 focus:ring-2 focus:ring-brand/20"
        />
        <Button type="submit" icon={ArrowRight} disabled={!valid} aria-label="Look up" />
      </form>
    </Card>
  )
}

function CheckFileCard({ busy, fileName, onFile }) {
  const inputRef = useRef(null)
  const [dragging, setDragging] = useState(false)

  return (
    <Card className="p-5 md:p-6">
      <CardHeader icon={FileSearch} title="Check a file" description="Reads the embedded label and checks its signature and pixels. Nothing is stored." />
      <button
        type="button"
        onClick={() => inputRef.current?.click()}
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => {
          e.preventDefault()
          setDragging(false)
          onFile(e.dataTransfer.files?.[0])
        }}
        disabled={busy}
        className={cn(
          'w-full rounded-xl border border-dashed px-4 py-7 text-center transition-all duration-200',
          dragging ? 'border-brand bg-brand/[0.07]' : 'border-line-strong hover:border-white/25 hover:bg-white/[0.03]',
        )}
      >
        <input
          ref={inputRef}
          type="file"
          accept="image/*"
          className="hidden"
          onChange={(e) => {
            onFile(e.target.files?.[0])
            e.target.value = ''
          }}
        />
        <UploadCloud className="size-6 text-brand mx-auto mb-2" />
        <p className="text-sm font-medium">{busy ? 'Checking…' : dragging ? 'Release to check' : 'Drop a labelled image'}</p>
        <p className="text-xs text-subtle mt-1 truncate">{fileName || 'or click to browse'}</p>
      </button>
    </Card>
  )
}

function ManifestCard({ check }) {
  const manifest = check.manifest
  if (!manifest) {
    const matched = check.match !== 'NONE'
    return (
      <Card className="p-6 animate-fade-up">
        <div className="flex items-start gap-4">
          <span className="grid place-items-center size-12 shrink-0 rounded-2xl border border-line-strong bg-white/[0.03]">
            <ShieldQuestion className="size-6 text-muted" />
          </span>
          <div>
            <p className="text-xs uppercase tracking-[0.16em] text-muted">Embedded label</p>
            <p className="text-xl font-semibold tracking-tight mt-0.5">No provenance label found</p>
            <p className="text-sm text-muted mt-1.5 leading-relaxed">
              {matched
                ? `The metadata may have been stripped, but the image matches a known analysis by ${MATCH_VIA[check.match].toLowerCase()}.`
                : 'This image was not labelled by this system, or its metadata was stripped and it matches no known analysis.'}
            </p>
          </div>
        </div>
      </Card>
    )
  }

  const meta = manifestStatus(manifest.status)
  const Icon = meta.icon
  return (
    <Card className={cn('p-6 animate-fade-up', meta.ring)}>
      <div className="flex items-start gap-4">
        <span className={cn('grid place-items-center size-12 shrink-0 rounded-2xl border', meta.ring)}>
          <Icon className={cn('size-6', meta.text)} />
        </span>
        <div className="min-w-0">
          <p className="text-xs uppercase tracking-[0.16em] text-muted">Embedded label</p>
          <p className={cn('text-2xl font-semibold tracking-tight mt-0.5', meta.text)}>{meta.label}</p>
          <p className="text-sm text-muted mt-1.5 leading-relaxed">{meta.description}</p>
        </div>
      </div>
      <div className="flex flex-wrap gap-x-5 gap-y-1 text-xs mt-5">
        <Tick ok={manifest.signatureValid} yes="Signature valid" no="Signature invalid" />
        <Tick ok={manifest.contentMatch} yes="Pixels unchanged" no="Pixels differ from the label" />
      </div>
      <dl className="grid sm:grid-cols-2 gap-2.5 mt-5">
        <Fact label="Provenance ID" mono>{manifest.provenanceId}</Fact>
        <Fact label="Issuer" mono>{manifest.issuer || '—'}</Fact>
        <Fact label="Labelled at">{manifest.analyzedAt ? dateTime(manifest.analyzedAt) : '—'}</Fact>
        <Fact label="Decision at labelling">
          {manifest.decision ? <DecisionBadge decision={manifest.decision} size="sm" /> : '—'}
        </Fact>
        <Fact label="Forgery score at labelling" mono>{manifest.forgeryScore != null ? score(manifest.forgeryScore) : '—'}</Fact>
        <Fact label="Manifest spec" mono>{manifest.spec || '—'}</Fact>
      </dl>
      {check.match === 'MANIFEST' && !check.record && (
        <p className="text-xs text-subtle mt-4">This system has no record of this ID, so there is no audit trail to show.</p>
      )}
    </Card>
  )
}

function RecordCard({ record }) {
  const complaint = record.complaint
  const sla = complaint ? SLA_STATUS[complaint.slaStatus] : null
  return (
    <Card className="p-6 animate-fade-up [animation-delay:60ms]">
      <CardHeader
        icon={Stamp}
        title="Provenance record"
        description={`Analysis #${record.analysisId} · analysed ${dateTime(record.analyzedAt)}`}
        action={<DecisionBadge decision={record.decision} />}
      />
      <div className="space-y-2.5">
        <IdRow icon={Stamp} label="Provenance ID" value={record.provenanceId} />
        {record.derivedFrom && (
          <IdRow icon={Link2} label="Re-upload of" value={record.derivedFrom} to={`/verify/${record.derivedFrom}`} />
        )}
      </div>
      <dl className="grid grid-cols-2 sm:grid-cols-3 gap-2.5 mt-2.5">
        <Fact label="Forgery score" mono>{score(record.forgeryScore)}</Fact>
        <Fact label="Explicit score" mono>{score(record.explicitScore)}</Fact>
        <Fact label="Re-uploads seen">
          <span className="inline-flex items-center gap-1.5">
            <Repeat2 className="size-3.5 text-muted" />
            {record.reuploads}
          </span>
        </Fact>
        {complaint && (
          <>
            <Fact label="Takedown complaint">{COMPLAINT_TYPES[complaint.type]?.label || complaint.type}</Fact>
            <Fact label="SLA status">
              <span className={cn('font-medium', sla?.text)}>{sla?.label || complaint.slaStatus}</span>
            </Fact>
            <Fact label="Resolved">{complaint.resolvedAt ? timeAgo(complaint.resolvedAt) : 'Open'}</Fact>
          </>
        )}
      </dl>
      <div className="grid sm:grid-cols-2 gap-2.5 mt-2.5">
        <IdRow icon={Fingerprint} label="Perceptual hash" value={record.phash} />
        <IdRow icon={Fingerprint} label="Pixel hash (SHA-256)" value={record.contentHash} />
      </div>
    </Card>
  )
}

function Timeline({ events }) {
  return (
    <Card className="p-6 animate-fade-up [animation-delay:120ms]">
      <CardHeader icon={History} title="Audit trail" description="Every recorded event for this ID, oldest first." />
      {events.length === 0 ? (
        <p className="text-sm text-muted">No events recorded yet.</p>
      ) : (
        <ol className="relative">
          {events.map((ev, i) => {
            const meta = EVENT_META[ev.type] || { label: ev.type, icon: History, tone: 'text-muted' }
            const Icon = meta.icon
            const last = i === events.length - 1
            return (
              <li key={`${ev.type}-${ev.occurredAt}-${i}`} className="relative flex gap-4 pb-5 last:pb-0">
                {!last && <span className="absolute left-[17px] top-9 bottom-0 w-px bg-line" />}
                <span className="relative grid place-items-center size-9 shrink-0 rounded-xl border border-line bg-surface-2">
                  <Icon className={cn('size-4', meta.tone)} />
                </span>
                <div className="min-w-0 flex-1 pt-0.5">
                  <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
                    <p className="text-sm font-medium">{meta.label}</p>
                    <span className="rounded-md border border-line px-1.5 py-px text-[10px] uppercase tracking-wider text-subtle">
                      {ev.actor}
                    </span>
                    <span className="ml-auto text-xs text-subtle" title={dateTime(ev.occurredAt)}>
                      {timeAgo(ev.occurredAt)}
                    </span>
                  </div>
                  {ev.detail && <p className="text-[13px] text-muted leading-relaxed mt-1">{ev.detail}</p>}
                  {ev.type === 'REUPLOAD_DETECTED' && ev.analysisId && (
                    <p className="text-xs text-subtle mt-1 font-mono">analysis #{ev.analysisId}</p>
                  )}
                </div>
              </li>
            )
          })}
        </ol>
      )}
    </Card>
  )
}

function Skeleton() {
  return (
    <Card className="p-6 space-y-4">
      <div className="skeleton h-6 w-48" />
      <div className="skeleton h-4 w-72" />
      <div className="grid grid-cols-3 gap-2.5">
        {[0, 1, 2].map((i) => (
          <div key={i} className="skeleton h-14" />
        ))}
      </div>
      <div className="skeleton h-40" />
    </Card>
  )
}

function Intro() {
  return (
    <EmptyState
      icon={Stamp}
      title="Check where an image came from"
      description="Every analysed image gets a traceable ID and a signed manifest embedded in its labelled copy. Look one up to see its authenticity and full audit history."
    >
      <Link to="/" className="inline-flex items-center gap-1.5 text-sm text-brand hover:underline">
        <ImageUp className="size-4" /> Analyse an image to get a label
      </Link>
    </EmptyState>
  )
}

export default function Verify() {
  const { id } = useParams()
  const [lookup, setLookup] = useState(null)
  const [check, setCheck] = useState(null)
  const [checking, setChecking] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!id) return
    let cancelled = false
    fetchProvenance(id)
      .then((data) => !cancelled && setLookup({ id, data }))
      .catch((err) => !cancelled && setLookup({ id, error: err.status === 404 ? 'notfound' : err.message }))
    return () => {
      cancelled = true
    }
  }, [id])

  const current = id && lookup?.id === id ? lookup : null
  const loadingId = Boolean(id) && !current && !check

  const onFile = async (file) => {
    if (!file) return
    if (!file.type.startsWith('image/')) {
      setError('That file is not an image.')
      return
    }
    setError('')
    setChecking(true)
    try {
      setCheck({ fileName: file.name, data: await verifyUpload(file) })
    } catch (err) {
      setError(err.message || 'Verification failed')
    } finally {
      setChecking(false)
    }
  }

  let content
  if (checking || loadingId) {
    content = <Skeleton />
  } else if (check) {
    content = (
      <>
        <div className="flex items-center justify-between gap-3 text-sm">
          <p className="text-muted truncate">
            Result for <span className="text-fg">{check.fileName}</span>
          </p>
          <Button variant="ghost" size="sm" icon={X} onClick={() => setCheck(null)}>
            Clear
          </Button>
        </div>
        <ManifestCard check={check.data} />
        {check.data.record && (
          <>
            <RecordCard record={check.data.record} />
            <Timeline events={check.data.record.events} />
          </>
        )}
      </>
    )
  } else if (current?.data) {
    content = (
      <>
        <RecordCard record={current.data} />
        <Timeline events={current.data.events} />
      </>
    )
  } else if (current?.error === 'notfound') {
    content = (
      <EmptyState
        icon={ShieldQuestion}
        title="Unknown provenance ID"
        description={`No analysis with ID ${shortId(id)} exists here. It may have been mistyped, or issued by a different system.`}
      />
    )
  } else if (current?.error) {
    content = <Alert title="Lookup failed">{current.error}</Alert>
  } else {
    content = <Intro />
  }

  return (
    <>
      <PageHeader
        eyebrow="Provenance"
        title="Verify media authenticity"
        description="Look up a provenance ID or drop a labelled image. The signed manifest is read back, its signature and pixel hash are checked, and the item's audit trail is shown."
      />
      <div className="grid lg:grid-cols-[minmax(0,340px)_minmax(0,1fr)] gap-6 items-start">
        <div className="space-y-5 lg:sticky lg:top-24">
          <LookupCard key={id || 'empty'} initial={id} />
          <CheckFileCard busy={checking} fileName={check?.fileName} onFile={onFile} />
        </div>
        <div className="space-y-5 min-w-0">
          {error && <Alert title="Something went wrong">{error}</Alert>}
          {content}
        </div>
      </div>
    </>
  )
}
