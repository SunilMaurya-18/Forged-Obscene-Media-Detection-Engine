import { useCallback, useEffect, useState } from 'react'
import { Link, Navigate, useLocation, useParams } from 'react-router-dom'
import {
  Archive,
  ArrowLeft,
  FileCheck2,
  FileDown,
  FolderSearch,
  Gavel,
  Link2,
  PackageCheck,
  PackagePlus,
  Radar,
  RefreshCw,
  Scale,
  ShieldCheck,
  ShieldX,
  Users,
} from 'lucide-react'
import {
  downloadEvidencePackage,
  fetchEvidencePackages,
  fetchInvestigatorCase,
  fetchInvestigatorCases,
  issueEvidencePackage,
} from '../api/client'
import { useAuth } from '../auth/AuthContext'
import RequestStatusBadge from '../components/RequestStatusBadge'
import { ErrorState, LoadingState } from '../components/Status'
import { useToast } from '../components/Toast'
import { Alert, Button, Card, CardHeader, CopyButton, DecisionBadge, EmptyState, PageHeader } from '../components/ui'
import { cn, dateTime, score, timeAgo } from '../lib/format'
import { packageDownloadable, timeLeft } from '../lib/handover'
import { VIA, matchLabel, normalizeInvestigationNumber, shortHash } from '../lib/investigations'

/** 401: signed out. 403: the request lapsed or was revoked while the page was open; shown, not signed out. */
function useLoad(fn, onUnauthorized) {
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const load = useCallback(async () => {
    setError(null)
    try {
      setData(await fn())
    } catch (err) {
      if (err.status === 401) onUnauthorized()
      else setError(err)
    }
  }, [fn, onUnauthorized])
  useEffect(() => {
    load()
  }, [load])
  return { data, error, load }
}

function PackageRow({ p, onDownloaded, onUnauthorized }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const open = packageDownloadable(p)
  const download = async () => {
    setBusy(true)
    setError(null)
    try {
      onDownloaded(await downloadEvidencePackage(p.packageId))
    } catch (err) {
      if (err.status === 401) onUnauthorized()
      else setError(err)
    } finally {
      setBusy(false)
    }
  }
  return (
    <li className="rounded-xl border border-line bg-white/[0.02] p-4">
      <div className="flex flex-wrap items-center gap-2">
        <PackageCheck className="size-4 text-brand" />
        <span className="font-mono text-sm font-semibold">{p.packageId}</span>
        {p.caseNumber && (
          <Link to={`/investigator/${p.caseNumber}`} className="font-mono text-xs text-muted hover:text-brand">
            {p.caseNumber}
          </Link>
        )}
        <span className="text-xs text-subtle" title={dateTime(p.issuedAt)}>
          issued {timeAgo(p.issuedAt)} · {p.downloads} {p.downloads === 1 ? 'download' : 'downloads'}
        </span>
        <Button size="sm" variant="secondary" icon={FileDown} loading={busy} disabled={!open} onClick={download} className="ml-auto">
          {open ? `Download (${timeLeft(p.downloadExpiresAt)})` : 'Download window closed'}
        </Button>
      </div>
      <p className="flex flex-wrap items-center gap-1.5 text-xs text-subtle mt-2">
        ZIP SHA-256 <span className="font-mono text-muted">{shortHash(p.sha256, 16)}…</span>
        <CopyButton value={p.sha256} className="size-6" />
        <span>
          · from {p.preservationId} · custody log to event {p.custodyLength} · key {p.signingKeyId}
        </span>
      </p>
      {error && <p className="text-xs text-rose-300 mt-2">{error.message}</p>}
    </li>
  )
}

function useDownloadedToast() {
  const toast = useToast()
  return (d) =>
    toast(`Saved ${d.filename}. Check it offline with tools/verify_evidence_package.py; its SHA-256 is ${shortHash(d.sha256, 16)}….`)
}

function VerifyHint() {
  return (
    <p className="text-xs text-subtle mt-4 leading-relaxed">
      Check a package without this site: <span className="font-mono text-muted">python3 verify_evidence_package.py PKG-….zip --keys keys.json</span>, with{' '}
      <span className="font-mono text-muted">keys.json</span> from <span className="font-mono text-muted">/api/verify-report/keys</span>. Or upload it on the{' '}
      <Link to="/verify" className="text-brand hover:text-brand-2">
        Verify
      </Link>{' '}
      page.
    </p>
  )
}

// ------------------------------------------------------------------ list

function CaseList({ onUnauthorized }) {
  const fetchAll = useCallback(async () => {
    const [cases, packages] = await Promise.all([fetchInvestigatorCases(), fetchEvidencePackages()])
    return { cases, packages }
  }, [])
  const { data, error, load } = useLoad(fetchAll, onUnauthorized)
  const downloaded = useDownloadedToast()
  if (error) return <ErrorState error={error} onRetry={load} />
  if (!data) return <LoadingState />
  return (
    <>
      <PageHeader
        eyebrow="Authorised investigator"
        title="Your cases"
        description="Only the cases named in a legal request approved for you, until it expires or is revoked. Access is read-only, and each time you open a case or download a package it is written to that case's custody log."
        actions={
          <Button variant="secondary" icon={RefreshCw} onClick={load}>
            Refresh
          </Button>
        }
      />
      {data.cases.length === 0 ? (
        <EmptyState
          icon={Scale}
          title="No cases available to you"
          description="None of your legal requests is approved and unexpired. Once the platform records your agency's order and a second senior moderator approves it, the cases it names appear here."
        />
      ) : (
        <div className="grid md:grid-cols-2 gap-4">
          {data.cases.map((c) => (
            <Link key={c.caseNumber} to={`/investigator/${c.caseNumber}`} className="card p-5 block transition hover:border-brand/40">
              <div className="flex items-center gap-2">
                <FolderSearch className="size-4 text-brand" />
                <span className="font-mono text-sm font-semibold">{c.caseNumber}</span>
                <span className="text-xs text-subtle ml-auto">{c.status === 'OPEN' ? 'Open' : 'Closed'}</span>
              </div>
              <p className="font-medium mt-2">{c.title}</p>
              {c.requests.map((r) => (
                <p key={r.requestNumber} className="text-xs text-muted mt-2">
                  <span className="font-mono">{r.requestNumber}</span> · {r.authority} · ref <span className="font-mono">{r.orderReference}</span>
                  <span className="text-subtle"> · {timeLeft(r.expiresAt)}</span>
                </p>
              ))}
            </Link>
          ))}
        </div>
      )}
      {data.packages.length > 0 && (
        <Card className="p-5 mt-6">
          <CardHeader icon={PackageCheck} title="Your evidence packages" description="Signed ZIPs you issued. Each can be downloaded for a limited time." />
          <ul className="space-y-3">
            {data.packages.map((p) => (
              <PackageRow
                key={p.packageId}
                p={p}
                onUnauthorized={onUnauthorized}
                onDownloaded={(d) => {
                  downloaded(d)
                  load()
                }}
              />
            ))}
          </ul>
          <VerifyHint />
        </Card>
      )}
    </>
  )
}

// ------------------------------------------------------------------ one case

function Verification({ v }) {
  const ok = v.status === 'VALID'
  const Icon = ok ? ShieldCheck : ShieldX
  return (
    <p className={cn('flex items-start gap-1.5 text-sm', ok ? 'text-emerald-200' : 'text-rose-200')}>
      <Icon className="size-4 mt-0.5 shrink-0" />
      <span>
        {ok ? 'Custody chain intact' : 'Custody chain broken'}
        <span className="block text-xs text-muted">{v.message}</span>
      </span>
    </p>
  )
}

function EvidenceList({ evidence }) {
  return (
    <ul className="divide-y divide-line">
      {evidence.map((e) => (
        <li key={e.analysisId} className="py-3 first:pt-0 last:pb-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-mono text-sm font-semibold">#{e.analysisId}</span>
            <DecisionBadge decision={e.decision} size="sm" />
            <span className="text-xs text-muted">
              {e.mediaType} · forgery {score(e.forgeryScore)} · analysed {timeAgo(e.createdAt)}
            </span>
          </div>
          <p className="flex flex-wrap items-center gap-1.5 text-xs text-subtle mt-1">
            Content SHA-256 <span className="font-mono text-muted">{shortHash(String(e.contentHash || '').replace('sha256:', ''), 16)}…</span>
            {e.contentHash && <CopyButton value={e.contentHash} className="size-6" />}
            {e.reportId && (
              <>
                · signed report{' '}
                <Link to={`/verify/${e.reportId}`} className="font-mono text-muted hover:text-brand">
                  {e.reportId}
                </Link>
              </>
            )}
          </p>
        </li>
      ))}
    </ul>
  )
}

function Sightings({ sightings }) {
  if (sightings.length === 0) return <p className="text-sm text-muted">No partner sightings linked to this case.</p>
  return (
    <div className="overflow-x-auto -mx-1">
      <table className="data-table w-full text-sm">
        <thead>
          <tr className="text-left text-xs text-subtle">
            <th className="font-medium px-1 pb-2">Platform</th>
            <th className="font-medium px-1 pb-2">Post</th>
            <th className="font-medium px-1 pb-2">Account (pseudonym)</th>
            <th className="font-medium px-1 pb-2">Seen</th>
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
                {dateTime(s.observedAt)}
              </td>
              <td className="px-1 py-2 text-xs" title={VIA[s.via]?.hint}>
                <Link2 className="inline size-3.5 mr-1 text-brand" />
                {matchLabel(s.via, s.distance)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Accounts({ accounts }) {
  if (accounts.length === 0) return <p className="text-sm text-muted">No accounts named in the linked sightings.</p>
  return (
    <ul className="space-y-2">
      {accounts.map((a) => (
        <li key={`${a.platform}:${a.account}`} className="rounded-xl border border-line bg-white/[0.02] px-4 py-3 text-sm">
          <span className="font-mono font-semibold">{a.account}</span> <span className="text-xs text-muted">on {a.platform}</span>
          <span className="text-xs text-subtle">
            {' '}
            · {a.sightings} in this case · {a.posts} {a.posts === 1 ? 'post' : 'posts'}
          </span>
          {a.otherCases.length > 0 && (
            <p className="text-xs mt-1.5">
              <span className="text-amber-200/90">Also in your cases: </span>
              {a.otherCases.map((o, i) => (
                <span key={o.caseNumber}>
                  {i > 0 && ', '}
                  <Link to={`/investigator/${o.caseNumber}`} className="font-mono hover:text-brand">
                    {o.caseNumber}
                  </Link>
                </span>
              ))}
            </p>
          )}
        </li>
      ))}
    </ul>
  )
}

function CaseView({ caseNumber, onUnauthorized }) {
  const toast = useToast()
  const downloaded = useDownloadedToast()
  const fetchCase = useCallback(() => fetchInvestigatorCase(caseNumber), [caseNumber])
  const { data: c, error, load } = useLoad(fetchCase, onUnauthorized)
  const [busy, setBusy] = useState(false)
  const [issueError, setIssueError] = useState(null)

  if (error) {
    return (
      <>
        <Link to="/investigator" className="inline-flex items-center gap-1.5 text-sm text-muted hover:text-fg mb-6">
          <ArrowLeft className="size-4" /> Your cases
        </Link>
        {error.status === 403 ? (
          <EmptyState icon={Scale} title="No authority for this case" description={error.message} />
        ) : (
          <ErrorState error={error} onRetry={load} />
        )}
      </>
    )
  }
  if (!c) return <LoadingState label="Opening the case (recorded in its custody log)…" />

  const issue = async () => {
    setBusy(true)
    setIssueError(null)
    try {
      const p = await issueEvidencePackage(caseNumber)
      toast(`${p.packageId} issued. Download it within ${timeLeft(p.downloadExpiresAt)}.`)
      load()
    } catch (err) {
      if (err.status === 401) onUnauthorized()
      else setIssueError(err)
    } finally {
      setBusy(false)
    }
  }
  const auth = c.authorisation
  const packages = [...(c.custody.packages || [])].reverse()
  const preservations = c.custody.preservations || []
  const latest = preservations[preservations.length - 1]
  return (
    <>
      <Link to="/investigator" className="inline-flex items-center gap-1.5 text-sm text-muted hover:text-fg mb-6">
        <ArrowLeft className="size-4" /> Your cases
      </Link>
      <PageHeader eyebrow={c.caseNumber} title={c.title} description={`Opened ${dateTime(c.openedAt)}. Read-only.`} />
      <Alert tone="info" title="This view is recorded">
        Opening the case added an &ldquo;investigator read&rdquo; event, with your name and {auth.requestNumber}, to its hash-chained custody log.
      </Alert>

      <div className="grid lg:grid-cols-[1fr_1.3fr] gap-6 mt-6 items-start">
        <div className="space-y-6">
          <Card className="p-5">
            <CardHeader icon={Scale} title="Your authority" />
            <div className="flex flex-wrap items-center gap-2 text-sm">
              <span className="font-mono font-semibold">{auth.requestNumber}</span>
              <RequestStatusBadge status={auth.status} />
            </div>
            <p className="text-sm mt-2">
              {auth.authority} · ref <span className="font-mono">{auth.orderReference}</span>
            </p>
            <p className="text-xs text-subtle mt-1">
              Recorded by {auth.filedBy}, approved by {auth.decidedBy}. Until {dateTime(auth.expiresAt)} ({timeLeft(auth.expiresAt)}).
            </p>
          </Card>

          <Card className="p-5">
            <CardHeader
              icon={PackagePlus}
              title="Evidence package"
              description="A signed, timestamped ZIP: the preserved manifest, this case report (JSON and PDF), sightings, the custody log and the signing key. No media."
            />
            {latest ? (
              <p className="text-xs text-subtle mb-3">
                Built from preservation <span className="font-mono text-muted">{latest.preservationId}</span>, {timeAgo(latest.preservedAt)}.
              </p>
            ) : (
              <p className="text-sm text-muted mb-3">This case hasn&rsquo;t been preserved yet; ask the platform to preserve it first.</p>
            )}
            <Button icon={PackagePlus} loading={busy} onClick={issue} disabled={!latest}>
              Issue package
            </Button>
            {issueError && (
              <div className="mt-3">
                <Alert title="Couldn't issue the package">{issueError.message}</Alert>
              </div>
            )}
            {packages.length > 0 && (
              <ul className="space-y-3 mt-4">
                {packages.map((p) => (
                  <PackageRow
                    key={p.packageId}
                    p={p}
                    onUnauthorized={onUnauthorized}
                    onDownloaded={(d) => {
                      downloaded(d)
                      load()
                    }}
                  />
                ))}
              </ul>
            )}
            <VerifyHint />
          </Card>

          <Card className="p-5">
            <CardHeader icon={Archive} title="Integrity" />
            <Verification v={c.custodyVerification} />
            <p className="text-xs text-subtle mt-3">
              {c.custody.length} custody events · head <span className="font-mono">{shortHash(c.custody.head)}</span>
            </p>
            {(c.custody.legalHolds || []).length > 0 && (
              <ul className="mt-3 space-y-1.5 text-xs text-muted">
                {c.custody.legalHolds.map((h) => (
                  <li key={h.holdNumber} className="flex items-center gap-1.5">
                    <Gavel className="size-3.5 text-subtle" />
                    <span className="font-mono">{h.holdNumber}</span> {h.platform}: {h.postRefs.length}{' '}
                    {h.postRefs.length === 1 ? 'post' : 'posts'} ({h.status.toLowerCase()})
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>

        <div className="space-y-6">
          <Card className="p-5">
            <CardHeader icon={FileCheck2} title="Evidence" description="The analyses in the case. The media stays with the platform that hosts it; the content hash identifies it." />
            <EvidenceList evidence={c.evidence} />
          </Card>
          <Card className="p-5">
            <CardHeader icon={Radar} title="Where it appeared" description="Partner sightings. Accounts are pseudonyms, never the platform's own IDs." />
            <Sightings sightings={c.sightings} />
          </Card>
          <Card className="p-5">
            <CardHeader icon={Users} title="Accounts" description="Links to other cases are shown only for cases your requests also cover." />
            <Accounts accounts={c.accounts} />
          </Card>
        </div>
      </div>
    </>
  )
}

export default function Investigator() {
  const { isInvestigator, isAuthenticated, loading, logout } = useAuth()
  const { caseNumber } = useParams()
  const location = useLocation()
  if (!loading && isAuthenticated) return <Navigate to="/legal-requests" replace />
  if (!loading && !isInvestigator) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  if (!isInvestigator) return <LoadingState />
  const normalized = caseNumber ? normalizeInvestigationNumber(caseNumber) : null
  if (caseNumber && normalized && normalized !== caseNumber) return <Navigate to={`/investigator/${normalized}`} replace />
  return caseNumber ? (
    <CaseView key={caseNumber} caseNumber={caseNumber} onUnauthorized={logout} />
  ) : (
    <CaseList onUnauthorized={logout} />
  )
}
