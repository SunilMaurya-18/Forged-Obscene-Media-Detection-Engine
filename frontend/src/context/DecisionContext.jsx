import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { Check, Undo2 } from 'lucide-react'
import { updateQueueDecision } from '../api/client'
import { useToast } from '../components/Toast'
import { useNow } from '../hooks/useNow'
import { useStats } from './StatsContext'

export const UNDO_MS = 10_000

const DecisionContext = createContext(null)

/**
 * Moderator decisions are held client-side for {@link UNDO_MS} before anything is sent. Undo therefore
 * never has to reverse server side effects (append-only provenance log, known-hash list, requester
 * notifications). Anything still pending when the page closes is flushed with {@code keepalive}.
 */
export function DecisionProvider({ children }) {
  const toast = useToast()
  const { refresh: refreshStats } = useStats()
  const [pending, setPending] = useState([])
  const [committed, setCommitted] = useState(0)
  const records = useRef(new Map())
  const seq = useRef(0)

  const drop = useCallback((key) => {
    const rec = records.current.get(key)
    if (!rec) return null
    clearTimeout(rec.timer)
    records.current.delete(key)
    setPending((list) => list.filter((p) => p.key !== key))
    return rec
  }, [])

  const commit = useCallback(
    async (key, { keepalive = false } = {}) => {
      const rec = drop(key)
      if (!rec) return
      if (keepalive) {
        rec.ids.forEach((id) => updateQueueDecision(id, rec.decision, { keepalive }).catch(() => {}))
        return
      }
      // One at a time: each decision counts toward the exposure limit, and once a break starts the rest would only 423
      const results = []
      let breakAt = -1
      for (const [i, id] of rec.ids.entries()) {
        try {
          results.push({ status: 'fulfilled', value: await updateQueueDecision(id, rec.decision) })
        } catch (error) {
          results.push({ status: 'rejected', reason: error })
          if (error?.status === 423) {
            breakAt = i
            break
          }
        }
      }
      const proposed = results.flatMap((r, i) => (r.status === 'fulfilled' && r.value?.pendingSecondReview ? [`#${rec.ids[i]}`] : []))
      if (proposed.length) {
        toast(`${proposed.join(', ')} sent for second review: overriding an automatic removal needs a second moderator.`, { duration: 6000 })
      }
      const failed = results.flatMap((r, i) => (r.status === 'rejected' ? [{ id: rec.ids[i], error: r.reason }] : []))
      if (breakAt >= 0) {
        const unsaved = rec.ids.slice(breakAt).map((id) => `#${id}`).join(', ')
        const saved = results.filter((r) => r.status === 'fulfilled').length
        toast(
          `Time for a break. ${saved ? `${saved} of ${rec.ids.length} saved; ` : ''}${unsaved} ${rec.ids.length - breakAt === 1 ? 'was' : 'were'} not saved and will stay in the queue.`,
          { tone: 'error', duration: 8000 },
        )
      } else if (failed.length) {
        const ids = failed.map((f) => `#${f.id}`).join(', ')
        toast(`Couldn't save the decision for ${ids}: ${failed[0].error?.message || 'please try again.'}`, { tone: 'error', duration: 6000 })
      }
      setCommitted((n) => n + 1)
      refreshStats()
    },
    [drop, refreshStats, toast],
  )

  /** Queues a decision for {@code ids}; returns a key usable with {@code undo}. */
  const decide = useCallback(
    ({ ids, decision, label }) => {
      const key = ++seq.current
      const deadline = Date.now() + UNDO_MS
      const timer = setTimeout(() => commit(key), UNDO_MS)
      records.current.set(key, { ids, decision, timer })
      setPending((list) => [...list, { key, ids, decision, label, deadline }])
      return key
    },
    [commit],
  )

  /** Undoes {@code key}, or the most recent pending decision. Returns the ids that were restored. */
  const undo = useCallback(
    (key) => {
      const target = key ?? Math.max(0, ...records.current.keys())
      const rec = drop(target)
      return rec ? rec.ids : []
    },
    [drop],
  )

  useEffect(() => {
    const flush = () => [...records.current.keys()].forEach((key) => commit(key, { keepalive: true }))
    window.addEventListener('pagehide', flush)
    return () => window.removeEventListener('pagehide', flush)
  }, [commit])

  const pendingIds = useMemo(() => new Set(pending.flatMap((p) => p.ids)), [pending])

  const value = useMemo(
    () => ({ decide, undo, commitNow: commit, pending, pendingIds, committed }),
    [decide, undo, commit, pending, pendingIds, committed],
  )

  return (
    <DecisionContext.Provider value={value}>
      {children}
      <UndoDock pending={pending} onUndo={undo} onCommit={commit} />
    </DecisionContext.Provider>
  )
}

function UndoDock({ pending, onUndo, onCommit }) {
  const now = useNow(200, pending.length > 0)
  const visible = pending.slice(-3)
  return (
    <div
      role="region"
      aria-label="Decisions you can still undo"
      className="fixed bottom-5 left-1/2 -translate-x-1/2 z-50 flex flex-col gap-2 w-[min(94vw,440px)] print:hidden"
    >
      <p className="sr-only" aria-live="polite">
        {pending.length ? `${pending[pending.length - 1].label}. Press U to undo within 10 seconds.` : ''}
      </p>
      {visible.map((p) => {
        const left = Math.max(0, p.deadline - now)
        const seconds = Math.ceil(left / 1000)
        return (
          <div key={p.key} className="card relative overflow-hidden bg-surface-2/95 backdrop-blur-xl animate-fade-up">
            <div className="flex items-center gap-3 px-4 py-3">
              <span
                aria-hidden
                className="grid place-items-center size-8 shrink-0 rounded-full border border-line text-xs font-semibold tabular-nums"
              >
                {seconds}
              </span>
              <p className="text-sm flex-1 min-w-0">
                <span className="font-medium">{p.label}</span>
                <span className="block text-xs text-subtle">Saving in {seconds}s</span>
              </p>
              <button
                type="button"
                onClick={() => onUndo(p.key)}
                className="inline-flex items-center gap-1.5 h-8 px-3 rounded-lg bg-white/[0.08] text-sm font-semibold hover:bg-white/[0.14] transition"
              >
                <Undo2 aria-hidden className="size-4" />
                Undo
                <kbd className="hidden sm:inline ml-1 rounded border border-line px-1 text-[10px] font-mono text-subtle">U</kbd>
              </button>
              <button
                type="button"
                onClick={() => onCommit(p.key)}
                aria-label={`Save now: ${p.label}`}
                title="Save now"
                className="grid place-items-center size-8 rounded-lg text-subtle hover:text-fg hover:bg-white/[0.06] transition"
              >
                <Check aria-hidden className="size-4" />
              </button>
            </div>
            <div aria-hidden className="absolute bottom-0 left-0 h-0.5 bg-brand transition-[width] duration-200 ease-linear" style={{ width: `${(left / UNDO_MS) * 100}%` }} />
          </div>
        )
      })}
      {pending.length > visible.length && (
        <p className="text-center text-xs text-subtle">
          +{pending.length - visible.length} more waiting to save
        </p>
      )}
    </div>
  )
}

export function useDecisions() {
  const ctx = useContext(DecisionContext)
  if (!ctx) throw new Error('useDecisions requires DecisionProvider')
  return ctx
}
