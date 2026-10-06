import { useState } from 'react'
import { ArrowUpCircle, Check, Hand, Loader2, RotateCcw, Scale, UserMinus, UserPlus, Users, X } from 'lucide-react'
import { assignItem, escalateItem, secondReview, unassignItem, updateQueueDecision } from '../api/client'
import { cn } from '../lib/format'
import { inTeamPool } from '../lib/queue'
import { useToast } from './Toast'
import { Button, INPUT } from './ui'

/** Runs a moderation call with a busy flag and a plain-language toast on failure. */
function useAction({ onChanged, onError }) {
  const toast = useToast()
  const [busy, setBusy] = useState(null)
  const run = async (key, fn, success) => {
    setBusy(key)
    try {
      const res = await fn()
      if (success) toast(typeof success === 'function' ? success(res) : success)
      onChanged?.(res)
      return true
    } catch (err) {
      if (onError?.(err)) return false
      toast(err.message || 'That action failed. Please try again.', { tone: 'error', duration: 6000 })
      return false
    } finally {
      setBusy(null)
    }
  }
  return [busy, run]
}

/** A one-line reason prompt shown in place of the button that opened it. */
function ReasonForm({ id, label, placeholder, submitLabel, required = true, busy, onSubmit, onCancel, variant = 'secondary' }) {
  const [reason, setReason] = useState('')
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        if (!required || reason.trim()) onSubmit(reason.trim())
      }}
      className="space-y-2 animate-fade-up"
    >
      <label htmlFor={id} className="block text-xs text-muted">
        {label}
      </label>
      <input id={id} autoFocus value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} placeholder={placeholder} className={INPUT} />
      <div className="flex gap-2">
        <Button type="submit" size="sm" variant={variant} loading={busy} disabled={required && !reason.trim()} className="flex-1">
          {submitLabel}
        </Button>
        <Button size="sm" variant="ghost" icon={X} onClick={onCancel} aria-label="Cancel" />
      </div>
    </form>
  )
}

/** Assignment, team routing (seniors) and senior escalation for review / escalated items. */
export function AssignmentControls({ item, me, teams = [], disabled, onChanged, onError }) {
  const [busy, run] = useAction({ onChanged, onError })
  const [escalating, setEscalating] = useState(false)
  const mine = me && item.assignedTo === me.username
  const canRelease = item.assignedTo && (mine || me?.senior)
  const canTake = !item.assignedTo && inTeamPool(item, me) && (!item.seniorEscalated || me?.senior)
  const routable = me?.senior && !item.seniorEscalated ? teams.filter((t) => t !== item.routedTeam) : []

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <span className="text-muted mr-auto">
          {item.assignedTo ? (
            <>
              Assigned to <strong className="text-fg font-medium">{mine ? 'you' : item.assignedTo}</strong>
              {item.assignedTeam && ` · ${item.assignedTeam} team`}
            </>
          ) : item.routedTeam ? (
            <>
              Unclaimed in the <strong className="text-fg font-medium">{item.routedTeam}</strong> team pool
            </>
          ) : (
            'Unassigned'
          )}
        </span>
        {routable.length > 0 && (
          <label className="inline-flex items-center gap-1 text-muted">
            <Users aria-hidden className="size-3.5" />
            <span className="sr-only">Route item {item.id} to a team</span>
            <select
              value=""
              disabled={disabled || busy === 'route'}
              onChange={(e) => {
                const team = e.target.value
                if (team) run('route', () => assignItem(item.id, null, team), `#${item.id} routed to the ${team} team`)
              }}
              className="field w-auto h-8 min-h-8 py-0 px-2 text-xs rounded-lg"
            >
              <option value="" className="bg-surface-2">
                Route to team…
              </option>
              {routable.map((t) => (
                <option key={t} value={t} className="bg-surface-2">
                  {t}
                </option>
              ))}
            </select>
          </label>
        )}
        {canTake && (
          <Button size="sm" variant="ghost" icon={UserPlus} disabled={disabled} loading={busy === 'assign'} onClick={() => run('assign', () => assignItem(item.id), `#${item.id} assigned to you`)}>
            Assign to me
          </Button>
        )}
        {canRelease && (
          <Button size="sm" variant="ghost" icon={UserMinus} disabled={disabled} loading={busy === 'release'} onClick={() => run('release', () => unassignItem(item.id), `#${item.id} released`)}>
            Release
          </Button>
        )}
        {!item.seniorEscalated && !escalating && (
          <Button size="sm" variant="ghost" icon={ArrowUpCircle} disabled={disabled} onClick={() => setEscalating(true)} title="Hand this to a senior moderator">
            Escalate
          </Button>
        )}
      </div>
      {item.seniorEscalated && (
        <p className="rounded-lg border border-violet-400/30 bg-violet-400/[0.07] px-3 py-2 text-xs text-violet-100">
          <ArrowUpCircle aria-hidden className="inline size-3.5 mr-1 -mt-0.5" />
          Escalated to senior{item.seniorEscalatedBy && ` by ${item.seniorEscalatedBy}`}
          {item.seniorEscalationReason && `: ${item.seniorEscalationReason}`}
          {!me?.senior && ' · only a senior moderator can decide it.'}
        </p>
      )}
      {escalating && (
        <ReasonForm
          id={`esc-${item.id}`}
          label="Why does this need a senior moderator?"
          placeholder="e.g. possible public figure, legal hold, unsure about context"
          submitLabel="Escalate to senior"
          busy={busy === 'escalate'}
          onCancel={() => setEscalating(false)}
          onSubmit={async (reason) => {
            if (await run('escalate', () => escalateItem(item.id, reason), `#${item.id} escalated to a senior moderator`)) setEscalating(false)
          }}
        />
      )}
    </div>
  )
}

/** Overriding an AUTO_FLAG always goes to a second moderator; this records the proposal. */
export function ProposeRestore({ item, disabled, onChanged, onError }) {
  const [busy, run] = useAction({ onChanged, onError })
  const [open, setOpen] = useState(false)
  const blocked = item.pendingDecision ? 'A change is already awaiting second review.' : item.appealStatus === 'OPEN' ? 'Decide it from the Appeals tab.' : null

  if (blocked) return <p className="text-[11px] text-subtle text-center">{blocked}</p>
  if (!open) {
    return (
      <Button variant="secondary" icon={RotateCcw} disabled={disabled} onClick={() => setOpen(true)} className="w-full" title="Propose restoring this item; a second moderator must confirm">
        Propose restore
      </Button>
    )
  }
  return (
    <ReasonForm
      id={`restore-${item.id}`}
      label="Why should this be restored? A second moderator will confirm."
      placeholder="e.g. scores driven by compression artefacts; not a face swap"
      submitLabel="Send for second review"
      busy={busy === 'restore'}
      onCancel={() => setOpen(false)}
      onSubmit={async (reason) => {
        if (await run('restore', () => updateQueueDecision(item.id, 'ALLOW', { reason }), `#${item.id} sent for second review`)) setOpen(false)
      }}
    />
  )
}

const VERB = { ALLOW: 'restore (Allow)', REVIEW: 'send back to review', AUTO_FLAG: 'remove' }

/**
 * Why {@code me} can't confirm/reject, mirroring the server: an escalated item needs a senior; the proposer can't
 * confirm; for a granted appeal, neither the granter nor anyone who made or kept the removal can confirm or reject.
 */
function secondReviewBlock(item, me) {
  const grant = item.appealStatus === 'GRANTED'
  if (item.seniorEscalated && !me?.senior) {
    return { confirm: 'Escalated: a senior moderator must do this second review.', reject: true }
  }
  if (item.canConfirm) return null
  if (grant) {
    return { confirm: 'You granted this appeal or made the removal it overturns, so another moderator must review it.', reject: true }
  }
  if (me && item.pendingProposedBy === me.username) {
    return { confirm: 'You proposed this change, so a different moderator must confirm it.', reject: false }
  }
  return { confirm: 'You can’t confirm this change.', reject: false }
}

/** Second review of a proposed AUTO_FLAG override: a different moderator confirms or rejects. */
export function SecondReviewPanel({ item, me, disabled, onChanged, onError }) {
  const [busy, run] = useAction({ onChanged, onError })
  const [rejecting, setRejecting] = useState(false)
  const block = secondReviewBlock(item, me)
  const grant = item.appealStatus === 'GRANTED'
  return (
    <div className="space-y-3">
      <div className="rounded-lg border border-sky-400/30 bg-sky-400/[0.07] px-3 py-2.5 text-xs text-sky-100">
        <p>
          <Scale aria-hidden className="inline size-3.5 mr-1 -mt-0.5" />
          <strong className="font-semibold">{item.pendingProposedBy || 'A moderator'}</strong> proposes to {VERB[item.pendingDecision] || item.pendingDecision}
          {item.appealStatus === 'GRANTED' && ' (appeal granted)'}.
        </p>
        {item.pendingReason && <p className="mt-1 text-sky-100/80 whitespace-pre-line">“{item.pendingReason}”</p>}
      </div>
      {block && (
        <p className="flex items-center gap-1.5 text-[11px] text-amber-200">
          <Hand aria-hidden className="size-3.5 shrink-0" /> {block.confirm}
        </p>
      )}
      {rejecting ? (
        <ReasonForm
          id={`reject-${item.id}`}
          label={grant ? 'Why keep the removal? The uploader sees this as the appeal outcome.' : 'Why keep the removal? (optional)'}
          submitLabel={grant ? 'Keep removal (appeal upheld)' : 'Reject proposal'}
          required={grant}
          variant="danger"
          busy={busy === 'reject'}
          onCancel={() => setRejecting(false)}
          onSubmit={(note) => run('reject', () => secondReview(item.id, false, note), `Proposal for #${item.id} rejected; removal kept`)}
        />
      ) : (
        <div className="grid grid-cols-2 gap-2">
          <Button
            variant="success"
            icon={busy === 'confirm' ? Loader2 : Check}
            disabled={disabled || !item.canConfirm || busy}
            onClick={() => run('confirm', () => secondReview(item.id, true), `#${item.id} restored after second review`)}
            className={cn(busy === 'confirm' && '[&>svg]:animate-spin')}
          >
            Confirm
          </Button>
          <Button variant="danger" icon={X} disabled={disabled || busy || block?.reject} onClick={() => setRejecting(true)}>
            Reject
          </Button>
        </div>
      )}
    </div>
  )
}
