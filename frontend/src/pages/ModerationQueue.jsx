import { useCallback, useEffect, useState } from 'react'
import { Link, Navigate, useLocation } from 'react-router-dom'
import { Ban, Check, EyeOff, Inbox, RefreshCw, ScanFace, ScanSearch } from 'lucide-react'
import { fetchQueue, updateQueueDecision } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { useToast } from '../components/Toast'
import { Alert, Button, Card, CopyButton, DecisionBadge, EmptyState, PageHeader, ScoreMeter, buttonClass } from '../components/ui'
import { useStats } from '../context/StatsContext'
import { cn, dateTime, mockFeedUrl, timeAgo } from '../lib/format'

const POLL_MS = 15000

/** Raw media is never stored, so the thumbnail is a blurred pattern derived from the pHash. */
function BlurredThumb({ phash }) {
  const seed = parseInt((phash || '8c').slice(0, 4), 16) || 140
  const hue = seed % 360
  return (
    <div className="relative size-20 shrink-0 rounded-xl overflow-hidden border border-line">
      <div
        aria-hidden
        className="absolute -inset-2 blur-lg"
        style={{
          background: `radial-gradient(circle at 30% 30%, hsl(${hue} 55% 45%), transparent 60%),
            radial-gradient(circle at 70% 70%, hsl(${(hue + 70) % 360} 50% 35%), transparent 60%),
            hsl(${(hue + 200) % 360} 30% 14%)`,
        }}
      />
      <div className="absolute inset-0 grid place-items-center bg-black/25">
        <EyeOff className="size-5 text-white/70" />
      </div>
    </div>
  )
}

function QueueItem({ item, thresholds, busy, onAct }) {
  return (
    <Card className={cn('p-5 transition-all duration-300', busy && 'opacity-50 scale-[0.99]')}>
      <div className="flex gap-4">
        <BlurredThumb phash={item.phash} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between gap-2">
            {item.provenanceId ? (
              <Link to={`/verify/${item.provenanceId}`} title="Audit trail" className="font-mono text-sm font-semibold hover:text-brand transition-colors">
                #{item.id}
              </Link>
            ) : (
              <p className="font-mono text-sm font-semibold">#{item.id}</p>
            )}
            <DecisionBadge decision="REVIEW" size="sm" />
          </div>
          <p className="text-xs text-muted mt-1" title={dateTime(item.createdAt)}>
            Submitted {timeAgo(item.createdAt)}
          </p>
          <div className="flex items-center gap-1 mt-1.5 min-w-0">
            <span className="font-mono text-[11px] text-subtle truncate">pHash {item.phash || '—'}</span>
            {item.phash && <CopyButton value={item.phash} className="size-6" />}
          </div>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-5 mt-5">
        <ScoreMeter
          compact
          label="Forgery"
          icon={ScanFace}
          value={item.forgeryScore}
          review={thresholds.forgeryReview}
          autoFlag={thresholds.forgeryAutoFlag}
        />
        <ScoreMeter
          compact
          label="Explicit"
          icon={EyeOff}
          value={item.explicitScore}
          review={thresholds.explicitReview}
          autoFlag={thresholds.explicitAutoFlag}
        />
      </div>

      <div className="grid grid-cols-2 gap-2 mt-5 pt-5 border-t border-line">
        <Button variant="success" icon={Check} disabled={busy} onClick={() => onAct(item, 'ALLOW')}>
          Approve
        </Button>
        <Button variant="danger" icon={Ban} disabled={busy} onClick={() => onAct(item, 'AUTO_FLAG')}>
          Reject
        </Button>
      </div>
    </Card>
  )
}

function SkeletonItem() {
  return (
    <Card className="p-5">
      <div className="flex gap-4">
        <div className="skeleton size-20 rounded-xl" />
        <div className="flex-1 space-y-2.5 pt-1">
          <div className="skeleton h-4 w-16" />
          <div className="skeleton h-3 w-32" />
          <div className="skeleton h-3 w-44" />
        </div>
      </div>
      <div className="skeleton h-10 w-full mt-6" />
    </Card>
  )
}

export default function ModerationQueue() {
  const { isAuthenticated, loading: authLoading, logout } = useAuth()
  const { thresholds, refresh: refreshStats } = useStats()
  const toast = useToast()
  const location = useLocation()
  const [items, setItems] = useState(null)
  const [error, setError] = useState('')
  const [refreshing, setRefreshing] = useState(false)
  const [busyId, setBusyId] = useState(null)

  const load = useCallback(
    async ({ silent = false } = {}) => {
      if (!silent) setRefreshing(true)
      try {
        const data = await fetchQueue()
        setItems(Array.isArray(data) ? data : [])
        setError('')
      } catch (err) {
        if (err.status === 401 || err.status === 403) return logout()
        setError(err.message || 'Could not load the queue')
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

  if (!authLoading && !isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  const act = async (item, decision) => {
    setBusyId(item.id)
    try {
      await updateQueueDecision(item.id, decision)
      setItems((list) => list.filter((i) => i.id !== item.id))
      toast(decision === 'ALLOW' ? `Item #${item.id} approved and published` : `Item #${item.id} rejected and flagged`)
      refreshStats()
    } catch (err) {
      toast(err.message || 'Update failed', { tone: 'error' })
    } finally {
      setBusyId(null)
    }
  }

  const count = items?.length ?? 0

  return (
    <>
      <PageHeader
        eyebrow="Human in the loop"
        title="Moderation queue"
        description="Uploads that scored in the review band. Thumbnails are placeholders because the API never stores raw media."
        actions={
          <>
            <span className="inline-flex items-center gap-2 h-10 px-4 rounded-xl border border-amber-400/25 bg-amber-400/[0.06] text-sm text-amber-200">
              <Inbox className="size-4" />
              <span className="tabular-nums font-semibold">{items ? count : '–'}</span> pending
            </span>
            <Button
              variant="secondary"
              icon={RefreshCw}
              onClick={() => load()}
              disabled={refreshing}
              className={cn(refreshing && '[&>svg]:animate-spin')}
            >
              Refresh
            </Button>
          </>
        }
      />

      {error && (
        <div className="mb-6">
          <Alert>{error}</Alert>
        </div>
      )}

      {items === null ? (
        <div className="grid md:grid-cols-2 gap-5">
          <SkeletonItem />
          <SkeletonItem />
        </div>
      ) : count === 0 ? (
        <EmptyState
          icon={Inbox}
          title="All clear"
          description="Nothing is waiting for review. New items appear here automatically when an upload lands in the review band."
        >
          <Link to="/" className={buttonClass('primary')}>
            <ScanSearch className="size-4" /> Analyze media
          </Link>
          <a href={mockFeedUrl()} target="_blank" rel="noreferrer" className={buttonClass('secondary')}>
            Open mock feed
          </a>
        </EmptyState>
      ) : (
        <div className="grid md:grid-cols-2 gap-5">
          {items.map((item, i) => (
            <div key={item.id} className="animate-fade-up" style={{ animationDelay: `${Math.min(i, 8) * 40}ms` }}>
              <QueueItem item={item} thresholds={thresholds} busy={busyId === item.id} onAct={act} />
            </div>
          ))}
        </div>
      )}
    </>
  )
}
