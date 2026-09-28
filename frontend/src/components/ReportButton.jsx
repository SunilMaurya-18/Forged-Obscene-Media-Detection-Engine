import { useRef, useState } from 'react'
import { FileText, Loader2, Paperclip, ShieldAlert, ShieldCheck, ShieldQuestion, X } from 'lucide-react'
import { downloadReport, verifyReport } from '../api/client'
import { useToast } from './Toast'
import { buttonClass } from './ui'
import { cn, dateTime } from '../lib/format'

const PANEL_NOTE = {
  ORIGINAL: 'panels rendered from the verified original',
  SIGNATURE: 'panels from the stored forensic signature',
  NONE: 'no forensic panels (item predates signature capture)',
}

const VERDICT = {
  VALID: { icon: ShieldCheck, label: 'Valid', tone: 'border-emerald-400/30 bg-emerald-400/[0.07] text-emerald-200' },
  TAMPERED: { icon: ShieldAlert, label: 'Tampered', tone: 'border-rose-400/30 bg-rose-400/[0.08] text-rose-200' },
  NOT_FOUND: { icon: ShieldQuestion, label: 'Not found', tone: 'border-amber-400/30 bg-amber-400/[0.07] text-amber-200' },
}

function Verdict({ result, onClose }) {
  const meta = VERDICT[result.status] || VERDICT.NOT_FOUND
  const Icon = meta.icon
  return (
    <div role="status" className={cn('relative rounded-xl border px-3 py-2.5 text-left animate-fade-up', meta.tone)}>
      <button
        type="button"
        onClick={onClose}
        aria-label="Dismiss"
        className="absolute top-1.5 right-1.5 grid place-items-center size-6 rounded-md opacity-70 hover:opacity-100 hover:bg-white/10"
      >
        <X className="size-3.5" />
      </button>
      <p className="flex items-center gap-1.5 text-xs font-semibold pr-6">
        <Icon className="size-4" /> {meta.label}
        {result.reportId && <span className="font-mono font-normal opacity-70 truncate">{result.reportId}</span>}
      </p>
      <p className="text-[11px] leading-relaxed mt-1 text-fg/80">{result.message}</p>
      {result.changedFields?.length > 0 && (
        <div className="flex flex-wrap gap-1 mt-1.5">
          {result.changedFields.map((f) => (
            <span key={f} className="rounded-md bg-black/25 px-1.5 py-0.5 font-mono text-[10px]">
              {f}
            </span>
          ))}
        </div>
      )}
      {result.signedAt && (
        <p className="text-[10px] mt-1.5 opacity-70 font-mono">
          {result.algorithm} · key {result.keyId}
          {result.keyStatus === 'RETIRED' && ' (retired)'} · signed {dateTime(result.signedAt)}
          {result.timestamp?.status === 'VERIFIED' &&
            ` · RFC 3161 ${dateTime(result.timestamp.time)}${result.timestamp.trusted ? '' : ' (untrusted TSA)'}`}
        </p>
      )}
    </div>
  )
}

/**
 * "Download report", a paperclip that attaches the original for full-resolution panels, and
 * "Verify report integrity", which checks the latest signed report against the record.
 */
export default function ReportButton({ item, size = 'md', className, onUnauthorized }) {
  const toast = useToast()
  const inputRef = useRef(null)
  const [busy, setBusy] = useState(false)
  const [verifying, setVerifying] = useState(false)
  const [reportId, setReportId] = useState(item.reportId || null)
  const [verdict, setVerdict] = useState(null)

  const run = async (original) => {
    setBusy(true)
    try {
      const report = await downloadReport(item.id, original)
      if (report.reportId) setReportId(report.reportId)
      setVerdict(null)
      toast(`${report.reportId || 'Report'} downloaded and signed: ${PANEL_NOTE[report.panels] || 'panels included'}`, {
        duration: 5000,
      })
    } catch (err) {
      if ((err.status === 401 || err.status === 403) && onUnauthorized) return onUnauthorized()
      toast(err.message || 'Could not generate the report', { tone: 'error', duration: 6000 })
    } finally {
      setBusy(false)
    }
  }

  const verify = async () => {
    setVerifying(true)
    try {
      setVerdict(await verifyReport(reportId || item.id))
    } catch (err) {
      toast(err.message || 'Could not verify the report', { tone: 'error' })
    } finally {
      setVerifying(false)
    }
  }

  const iconSize = size === 'sm' ? 'size-3.5' : 'size-4'

  return (
    <div className={cn('flex flex-col gap-2', className)}>
      <div className="flex">
        <button
          type="button"
          disabled={busy}
          onClick={() => run()}
          title="Generate the signed evidentiary PDF (scores, panels, decision, SLA, provenance)"
          className={buttonClass('secondary', size, 'flex-1 rounded-r-none')}
        >
          {busy ? <Loader2 className={cn(iconSize, 'animate-spin')} /> : <FileText className={iconSize} />}
          Download report
        </button>
        <button
          type="button"
          disabled={busy}
          onClick={() => inputRef.current?.click()}
          title="Attach the original image for full-resolution panels. It is verified against the stored hash and never stored."
          aria-label="Generate report with the original attached"
          className={buttonClass('secondary', size, 'rounded-l-none border-l-0 px-2.5')}
        >
          <Paperclip className={iconSize} />
        </button>
        <input
          ref={inputRef}
          type="file"
          accept="image/*"
          className="hidden"
          onChange={(e) => {
            const file = e.target.files?.[0]
            e.target.value = ''
            if (file) run(file)
          }}
        />
      </div>
      <button
        type="button"
        disabled={verifying || busy || !reportId}
        onClick={verify}
        title={reportId ? `Check ${reportId} against the current record` : 'Generate a report first'}
        className={buttonClass('ghost', 'sm', 'w-full border border-line')}
      >
        {verifying ? <Loader2 className="size-3.5 animate-spin" /> : <ShieldCheck className="size-3.5" />}
        Verify report integrity
      </button>
      {verdict && <Verdict result={verdict} onClose={() => setVerdict(null)} />}
    </div>
  )
}
