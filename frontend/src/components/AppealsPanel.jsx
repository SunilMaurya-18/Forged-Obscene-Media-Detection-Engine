import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { CircleCheck, CircleSlash, EyeOff, Gavel, Hand, ScanFace } from 'lucide-react'
import { decideAppeal, fetchAppeals } from '../api/client'
import { useNow } from '../hooks/useNow'
import { cn, dateTime, timeAgo } from '../lib/format'
import { deadlineInfo } from '../lib/queue'
import JurisdictionBadge from './JurisdictionBadge'
import { ErrorState, LoadingState } from './Status'
import { useToast } from './Toast'
import { Button, Card, EmptyState, ScoreMeter } from './ui'

const STATUS = {
  OPEN: { label: 'Open', cls: 'border-amber-400/30 bg-amber-400/[0.08] text-amber-200' },
  GRANTED: { label: 'Granted', cls: 'border-emerald-400/30 bg-emerald-400/[0.08] text-emerald-200' },
  UPHELD: { label: 'Upheld', cls: 'border-line-strong text-muted' },
}

function AppealCard({ appeal, thresholds, now, disabled, onDecided, onError }) {
  const toast = useToast()
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(null)
  const item = appeal.item || {}
  const status = STATUS[appeal.status] || STATUS.OPEN
  const deadline = deadlineInfo(appeal.slaDeadline, now)

  const decide = async (outcome) => {
    setBusy(outcome)
    try {
      await decideAppeal(appeal.id, outcome, reason.trim())
      toast(outcome === 'GRANT' ? `${appeal.caseNumber} granted; restore sent for second review` : `${appeal.caseNumber} upheld`)
      onDecided()
    } catch (err) {
      if (!onError(err)) toast(err.message || 'Could not record the decision.', { tone: 'error', duration: 6000 })
    } finally {
      setBusy(null)
    }
  }

  return (
    <Card className="p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="font-mono text-sm font-semibold">{appeal.caseNumber}</p>
        <span className={cn('rounded-full border px-2 py-0.5 text-[11px] font-medium', status.cls)}>{status.label}</span>
      </div>
      <div className="flex flex-wrap items-center gap-2 mt-2 text-[11px]">
        {appeal.status === 'OPEN' && <span className="rounded-full border border-line px-2 py-0.5 text-muted">{deadline.text}</span>}
        {appeal.slaBadge && <JurisdictionBadge jurisdiction={item.jurisdiction} badge={appeal.slaBadge} className="py-0.5 text-[11px]" />}
        {appeal.escalatedAt && <span className="rounded-full border border-rose-400/40 px-2 py-0.5 text-rose-200">Escalated</span>}
      </div>
      <p className="text-xs text-muted mt-2" title={dateTime(appeal.filedAt)}>
        Filed {timeAgo(appeal.filedAt)} against item{' '}
        {item.provenanceId ? (
          <Link to={`/verify/${item.provenanceId}`} className="font-mono hover:text-brand">
            #{appeal.analysisId}
          </Link>
        ) : (
          <span className="font-mono">#{appeal.analysisId}</span>
        )}{' '}
        · removed by <strong className="text-fg font-medium">{appeal.contestedBy}</strong>
        {item.mediaType === 'VIDEO' && ' · video'}
      </p>
      {appeal.conflicted?.length > 0 && (
        <p className="text-[11px] text-subtle mt-1">
          Can't decide this appeal or its second review: {appeal.conflicted.join(', ')}
        </p>
      )}

      <blockquote className="mt-3 rounded-lg border border-line bg-white/[0.02] px-3 py-2.5 text-sm whitespace-pre-line">{appeal.statement}</blockquote>

      {item.forgeryScore != null && (
        <div className="grid grid-cols-2 gap-5 mt-4">
          <ScoreMeter compact label="Forgery" icon={ScanFace} value={item.forgeryScore} review={thresholds.forgeryReview} autoFlag={thresholds.forgeryAutoFlag} />
          <ScoreMeter compact label="Explicit" icon={EyeOff} value={item.explicitScore} review={thresholds.explicitReview} autoFlag={thresholds.explicitAutoFlag} />
        </div>
      )}

      {appeal.status === 'OPEN' ? (
        <div className="mt-4 pt-4 border-t border-line space-y-2">
          {!appeal.canDecide ? (
            <p className="flex items-center gap-1.5 text-xs text-amber-200">
              <Hand aria-hidden className="size-3.5 shrink-0" /> You made or kept the removal being appealed, so another moderator must decide this appeal.
            </p>
          ) : (
            <>
              <label htmlFor={`appeal-reason-${appeal.id}`} className="block text-xs text-muted">
                Reason (shown to the uploader)
              </label>
              <textarea
                id={`appeal-reason-${appeal.id}`}
                rows={2}
                maxLength={1000}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                className="w-full rounded-lg border border-line bg-white/[0.03] px-3 py-2 text-sm focus:border-brand/60 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand/40"
              />
              <div className="grid grid-cols-2 gap-2">
                <Button variant="secondary" icon={CircleSlash} disabled={disabled || !reason.trim() || busy} loading={busy === 'UPHOLD'} onClick={() => decide('UPHOLD')}>
                  Uphold removal
                </Button>
                <Button variant="success" icon={CircleCheck} disabled={disabled || !reason.trim() || busy} loading={busy === 'GRANT'} onClick={() => decide('GRANT')}>
                  Grant appeal
                </Button>
              </div>
              <p className="text-[11px] text-subtle">Granting proposes a restore; a second moderator confirms it from the Second review tab.</p>
            </>
          )}
        </div>
      ) : (
        <div className="mt-4 pt-4 border-t border-line text-xs text-muted space-y-1">
          <p>
            Decided by <strong className="text-fg font-medium">{appeal.decidedBy}</strong> {appeal.decidedAt && timeAgo(appeal.decidedAt)}
            {appeal.status === 'GRANTED' && (appeal.reinstatedAt ? ' · reinstated' : ' · awaiting second review')}
          </p>
          {appeal.outcomeReason && <p className="whitespace-pre-line">“{appeal.outcomeReason}”</p>}
        </div>
      )}
    </Card>
  )
}

/** Phase 23: uploader appeals (EU DSA Art. 20 style), nearest deadline first. */
export default function AppealsPanel({ thresholds, disabled, refreshKey, onCount, onError }) {
  const now = useNow(30_000)
  const [status, setStatus] = useState('open')
  const [appeals, setAppeals] = useState(null)
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    try {
      const list = await fetchAppeals(status)
      setAppeals(Array.isArray(list) ? list : [])
      if (status === 'open') onCount?.(list.length)
      setError(null)
    } catch (err) {
      if (!onError(err)) setError(err)
    }
  }, [status, onCount, onError])

  useEffect(() => {
    load()
  }, [load, refreshKey])

  return (
    <div>
      <div className="flex items-center gap-2 mb-4 text-sm">
        {['open', 'decided'].map((s) => (
          <button
            key={s}
            type="button"
            aria-pressed={status === s}
            onClick={() => {
              setAppeals(null)
              setStatus(s)
            }}
            className={cn('h-8 px-3 rounded-lg border transition', status === s ? 'border-brand/50 bg-brand/[0.08] text-fg' : 'border-line text-muted hover:text-fg')}
          >
            {s === 'open' ? 'Open' : 'Decided'}
          </button>
        ))}
      </div>
      {error && <ErrorState error={error} title="Couldn't load appeals" onRetry={load} className="mb-4" />}
      {appeals === null ? (
        error ? null : <LoadingState label="Loading appeals…" />
      ) : appeals.length === 0 ? (
        <EmptyState
          icon={Gavel}
          title={status === 'open' ? 'No open appeals' : 'No decided appeals yet'}
          description="Uploaders can appeal an automatic removal with the notice number and PIN they received."
        />
      ) : (
        <div role="list" className="grid md:grid-cols-2 gap-5">
          {appeals.map((a) => (
            <div key={a.id} role="listitem">
              <AppealCard appeal={a} thresholds={thresholds} now={now} disabled={disabled} onDecided={load} onError={onError} />
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
