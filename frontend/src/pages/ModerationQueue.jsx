import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, Navigate, useLocation } from 'react-router-dom'
import {
  AlarmClock,
  ArrowUpCircle,
  Ban,
  Check,
  Clock,
  CopyCheck,
  EyeOff,
  Film,
  Gavel,
  Inbox,
  Keyboard,
  RefreshCw,
  Scale,
  ScanFace,
  ScanSearch,
  ShieldBan,
  Siren,
  SkipForward,
  Timer,
  X,
} from 'lucide-react'
import { fetchAppeals, fetchModerationMe, fetchQueue, fetchTeams, takeBreak } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import AppealsPanel from '../components/AppealsPanel'
import { C2paChip } from '../components/ContentCredentials'
import FamilyChip from '../components/FamilyChip'
import FrameStrip from '../components/FrameStrip'
import InvestigateButton from '../components/InvestigateButton'
import JurisdictionBadge from '../components/JurisdictionBadge'
import MediaPreview from '../components/MediaPreview'
import { AssignmentControls, ProposeRestore, SecondReviewPanel } from '../components/ModerationControls'
import ReportButton from '../components/ReportButton'
import { ErrorState, LoadingState } from '../components/Status'
import { useToast } from '../components/Toast'
import { Button, Card, CopyButton, DecisionBadge, EmptyState, Kbd, PageHeader, ScoreMeter, buttonClass } from '../components/ui'
import { BreakScreen, WellbeingMeter } from '../components/Wellbeing'
import { useDecisions } from '../context/DecisionContext'
import { useStats } from '../context/StatsContext'
import { useNow } from '../hooks/useNow'
import { cn, dateTime, mockFeedUrl, timeAgo } from '../lib/format'
import { byNewest, byUrgency, deadlineInfo, inTeamPool, similarIds } from '../lib/queue'

const POLL_MS = 15000

const DEADLINE_TONE = {
  none: { icon: Clock, cls: 'border-line text-subtle' },
  ok: { icon: Timer, cls: 'border-sky-400/30 bg-sky-400/[0.07] text-sky-200' },
  soon: { icon: AlarmClock, cls: 'border-amber-400/35 bg-amber-400/[0.08] text-amber-200' },
  overdue: { icon: Siren, cls: 'border-rose-400/40 bg-rose-400/[0.1] text-rose-200' },
}

const VERB = { ALLOW: 'Approved', AUTO_FLAG: 'Rejected' }

/** Queue tabs backed by {@code GET /api/queue?status=…}; "appeals" has its own endpoint. */
const LIST_TABS = ['review', 'escalated', 'second-review', 'flagged']

const SCOPES = [
  ['all', 'Everyone'],
  ['mine', 'Assigned to me'],
  ['team', 'My team'],
  ['unassigned', 'Unassigned'],
]

const SHORTCUTS = [
  { keys: ['J', 'K'], label: 'Next / previous' },
  { keys: ['A'], label: 'Approve' },
  { keys: ['R'], label: 'Reject' },
  { keys: ['S'], label: 'Skip for now' },
  { keys: ['X'], label: 'Select' },
  { keys: ['M'], label: 'Select similar' },
  { keys: ['U'], label: 'Undo' },
  { keys: ['Esc'], label: 'Clear selection' },
]

function DeadlineChip({ info }) {
  const tone = DEADLINE_TONE[info.tone]
  const Icon = tone.icon
  return (
    <span className={cn('inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-[11px] font-medium', tone.cls)}>
      <Icon aria-hidden className="size-3" />
      {info.text}
    </span>
  )
}

function ShortcutLegend() {
  return (
    <Card className="px-4 py-3 mb-6 animate-fade-up">
      <div className="flex flex-wrap items-center gap-x-5 gap-y-2 text-xs text-muted">
        <span className="inline-flex items-center gap-1.5 font-medium text-fg">
          <Keyboard aria-hidden className="size-4 text-brand" />
          Keyboard
        </span>
        {SHORTCUTS.map(({ keys, label }) => (
          <span key={label} className="inline-flex items-center gap-1.5">
            {keys.map((k) => (
              <Kbd key={k}>{k}</Kbd>
            ))}
            {label}
          </span>
        ))}
      </div>
    </Card>
  )
}

/** Why this moderator can't decide the item directly, or null. Mirrors the server's guards. */
function decideBlock(item, me) {
  if (!me) return null
  if (item.seniorEscalated && !me.senior) return 'Escalated: a senior moderator decides this one.'
  if (item.assignedTo && item.assignedTo !== me.username && !me.senior) return `Assigned to ${item.assignedTo}.`
  if (!inTeamPool(item, me)) return `Routed to the ${item.routedTeam} team.`
  return null
}

function QueueItem({
  item,
  tab,
  me,
  teams,
  thresholds,
  active,
  selected,
  deadline,
  cardRef,
  disabled,
  onActivate,
  onToggle,
  onDecide,
  onSkip,
  onChanged,
  onError,
  onWellbeing,
  onUnauthorized,
}) {
  const decidable = tab === 'review' || tab === 'escalated'
  const block = decidable ? decideBlock(item, me) : null
  const title = `Item ${item.id}${item.complaintType ? ', user complaint' : ''}${decidable ? `, ${deadline.text}` : ''}`
  const badge = tab === 'second-review' ? item.decision : decidable ? 'REVIEW' : 'AUTO_FLAG'
  return (
    <article
      ref={cardRef}
      tabIndex={active ? 0 : -1}
      aria-label={title}
      onFocus={(e) => e.target === e.currentTarget && onActivate()}
      onClick={(e) => !e.target.closest('button, a, input, textarea, video') && onActivate()}
      className={cn(
        'card p-5 transition-all duration-200 outline-none',
        active && 'ring-2 ring-brand/70 shadow-[0_0_0_4px_rgb(239_68_68/0.12)]',
        selected && 'border-brand/50 bg-brand/[0.05]',
      )}
    >
      <div className="flex gap-4">
        <MediaPreview item={item} disabled={disabled} onWellbeing={onWellbeing} onUnauthorized={onUnauthorized} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              {decidable && (
                <input
                  type="checkbox"
                  checked={selected}
                  onChange={onToggle}
                  onClick={(e) => e.stopPropagation()}
                  aria-label={`Select item ${item.id}`}
                  disabled={Boolean(block)}
                  className="size-4 accent-[var(--color-brand)]"
                />
              )}
              {item.provenanceId ? (
                <Link to={`/verify/${item.provenanceId}`} title="Audit trail" className="font-mono text-sm font-semibold hover:text-brand transition-colors">
                  #{item.id}
                </Link>
              ) : (
                <p className="font-mono text-sm font-semibold">#{item.id}</p>
              )}
              {item.mediaType === 'VIDEO' && (
                <span className="inline-flex items-center gap-1 text-[11px] text-muted" title={`${item.video?.sampled ?? '?'} frames sampled`}>
                  <Film aria-hidden className="size-3.5" /> video
                </span>
              )}
              {active && <span className="sr-only">(current item)</span>}
            </div>
            <DecisionBadge decision={badge} size="sm" />
          </div>
          <div className="flex flex-wrap items-center gap-2 mt-1.5">
            {decidable && <DeadlineChip info={deadline} />}
            {item.slaBadge && <JurisdictionBadge jurisdiction={item.jurisdiction} badge={item.slaBadge} className="py-0.5 text-[11px]" />}
            <FamilyChip family={item.generationFamily} />
            <C2paChip credentials={item.contentCredentials} />
            {item.appealStatus && (
              <span className="inline-flex items-center gap-1 rounded-full border border-line px-2 py-0.5 text-[11px] text-muted">
                <Gavel aria-hidden className="size-3" /> appeal {item.appealStatus.toLowerCase()}
              </span>
            )}
          </div>
          <p className="text-xs text-muted mt-1.5" title={dateTime(item.createdAt)}>
            Submitted {timeAgo(item.createdAt)}
            {item.decidedBy && tab !== 'review' && ` · decided by ${item.decidedBy}`}
            {item.seniorResolvedBy && ` · senior escalation resolved by ${item.seniorResolvedBy}`}
          </p>
          <div className="flex items-center gap-1 mt-1 min-w-0">
            <span className="font-mono text-[11px] text-subtle truncate">pHash {item.phash || '—'}</span>
            {item.phash && <CopyButton value={item.phash} className="size-6" />}
          </div>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-5 mt-5">
        <ScoreMeter compact label="Forgery" icon={ScanFace} value={item.forgeryScore} review={thresholds.forgeryReview} autoFlag={thresholds.forgeryAutoFlag} />
        <ScoreMeter compact label="Explicit" icon={EyeOff} value={item.explicitScore} review={thresholds.explicitReview} autoFlag={thresholds.explicitAutoFlag} />
      </div>
      {item.video && <FrameStrip compact video={item.video} thresholds={thresholds} className="mt-4" />}

      <div className="mt-5 pt-5 border-t border-line space-y-3">
        {decidable && (
          <>
            <div className="grid grid-cols-[1fr_1fr_auto] gap-2">
              <Button variant="success" icon={Check} disabled={disabled || Boolean(block)} onClick={() => onDecide('ALLOW')} aria-label={`Approve item ${item.id}`} aria-keyshortcuts="A">
                Approve <Kbd>A</Kbd>
              </Button>
              <Button variant="danger" icon={Ban} disabled={disabled || Boolean(block)} onClick={() => onDecide('AUTO_FLAG')} aria-label={`Reject item ${item.id}`} aria-keyshortcuts="R">
                Reject <Kbd>R</Kbd>
              </Button>
              <Button variant="ghost" icon={SkipForward} onClick={onSkip} aria-label={`Skip item ${item.id} for now`} aria-keyshortcuts="S" title="Skip for now (S)" />
            </div>
            {block && <p className="text-[11px] text-amber-200 text-center">{block}</p>}
            <AssignmentControls item={item} me={me} teams={teams} disabled={disabled} onChanged={onChanged} onError={onError} />
          </>
        )}
        {tab === 'flagged' && <ProposeRestore item={item} disabled={disabled} onChanged={onChanged} onError={onError} />}
        {tab === 'second-review' && <SecondReviewPanel item={item} me={me} disabled={disabled} onChanged={onChanged} onError={onError} />}
        <ReportButton item={item} className="w-full" onUnauthorized={onUnauthorized} />
        <InvestigateButton item={item} className="w-full border border-line" onUnauthorized={onUnauthorized} />
        {!item.hasForensics && <p className="text-[11px] text-subtle text-center">Analysed before signature capture: attach the original for panels.</p>}
      </div>
    </article>
  )
}

function BatchBar({ count, similar, onSelectSimilar, onApprove, onReject, onClear }) {
  if (!count && !similar) return null
  return (
    <div role="region" aria-label="Batch actions" className="sticky top-20 z-30 mb-5 animate-fade-up">
      <Card className="flex flex-wrap items-center gap-2 px-4 py-2.5 bg-surface-2/95 backdrop-blur-xl">
        {count > 0 ? (
          <p className="text-sm font-medium mr-auto" aria-live="polite">
            {count} selected
          </p>
        ) : (
          <p className="text-sm text-muted mr-auto">
            {similar} similar {similar === 1 ? 'item' : 'items'} to the current one
          </p>
        )}
        {similar > 0 && (
          <Button size="sm" variant="secondary" icon={CopyCheck} onClick={onSelectSimilar} aria-keyshortcuts="M">
            Select {similar} similar <Kbd>M</Kbd>
          </Button>
        )}
        {count > 0 && (
          <>
            <Button size="sm" variant="success" icon={Check} onClick={onApprove}>
              Approve {count}
            </Button>
            <Button size="sm" variant="danger" icon={Ban} onClick={onReject}>
              Reject {count}
            </Button>
            <Button size="sm" variant="ghost" icon={X} onClick={onClear} aria-label="Clear selection">
              <Kbd>Esc</Kbd>
            </Button>
          </>
        )}
      </Card>
    </div>
  )
}

function isTyping(target) {
  return target instanceof HTMLElement && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName)) && target.type !== 'checkbox'
}

const EMPTY = {
  escalated: { icon: ArrowUpCircle, title: 'Nothing escalated', description: 'Items a moderator hands to a senior reviewer appear here.' },
  'second-review': {
    icon: Scale,
    title: 'No proposals waiting',
    description: 'Overriding an automatic removal needs a second moderator. Those proposals, including granted appeals, appear here.',
  },
  flagged: {
    icon: ShieldBan,
    title: 'No takedowns yet',
    description: 'Items the model auto-flags, or that a moderator rejects, appear here with an evidentiary report ready to download.',
  },
}

export default function ModerationQueue() {
  const { isAuthenticated, loading: authLoading, logout } = useAuth()
  const { thresholds } = useStats()
  const { decide, undo, pendingIds, committed } = useDecisions()
  const toast = useToast()
  const location = useLocation()
  const now = useNow(30_000)
  const [lists, setLists] = useState({})
  const [me, setMe] = useState(null)
  const [teams, setTeams] = useState([])
  const [wellbeing, setWellbeing] = useState(null)
  const [appealCount, setAppealCount] = useState(null)
  const [tab, setTab] = useState('review')
  const [scope, setScope] = useState('all')
  const [sort, setSort] = useState('deadline')
  const [error, setError] = useState(null)
  const [refreshing, setRefreshing] = useState(false)
  const [breakBusy, setBreakBusy] = useState(false)
  const [activeId, setActiveId] = useState(null)
  const [selected, setSelected] = useState(() => new Set())
  const [skipped, setSkipped] = useState([])
  const [announcement, setAnnouncement] = useState('')
  const [reloadKey, setReloadKey] = useState(0)
  const cards = useRef(new Map())
  const tabRefs = useRef({})

  const refreshMe = useCallback(async () => {
    try {
      const res = await fetchModerationMe()
      setMe(res.moderator)
      setWellbeing(res.wellbeing)
    } catch {
      /* the queue load reports connection problems */
    }
  }, [])

  const load = useCallback(
    async ({ silent = false } = {}) => {
      if (!silent) setRefreshing(true)
      try {
        const [results, appeals] = await Promise.all([
          Promise.all(LIST_TABS.map((s) => fetchQueue(s, scope))),
          fetchAppeals('open'),
          refreshMe(),
        ])
        setLists(Object.fromEntries(LIST_TABS.map((s, i) => [s, Array.isArray(results[i]) ? results[i] : []])))
        if (Array.isArray(appeals)) setAppealCount(appeals.length)
        setError(null)
      } catch (err) {
        if (err.status === 401 || err.status === 403) return logout()
        setError(err)
      } finally {
        setRefreshing(false)
      }
    },
    [logout, refreshMe, scope],
  )

  useEffect(() => {
    if (!isAuthenticated) return
    load({ silent: true })
    const timer = setInterval(() => load({ silent: true }), POLL_MS)
    return () => clearInterval(timer)
  }, [isAuthenticated, load])

  useEffect(() => {
    if (committed && isAuthenticated) load({ silent: true })
  }, [committed, isAuthenticated, load])

  const senior = Boolean(me?.senior)
  useEffect(() => {
    if (!senior) return
    fetchTeams()
      .then((byTeam) => setTeams(Object.keys(byTeam || {}).filter((t) => t && t !== 'null').sort()))
      .catch(() => setTeams([]))
  }, [senior])

  const onChanged = useCallback(() => {
    load({ silent: true })
    setReloadKey((k) => k + 1)
  }, [load])

  /** Returns true when the error was fully handled here. */
  const onError = useCallback(
    (err) => {
      if (err?.status === 401) {
        logout()
        return true
      }
      if (err?.status === 423) refreshMe()
      return false
    },
    [logout, refreshMe],
  )

  const onBreak = Boolean(wellbeing?.onBreak)
  const decisionTab = tab === 'review' || tab === 'escalated'
  const source = lists[tab] ?? null

  const visible = useMemo(() => {
    if (!source) return null
    const list = source.filter((i) => !pendingIds.has(i.id)).sort(sort === 'deadline' ? byUrgency : byNewest)
    if (!decisionTab || !skipped.length) return list
    const rank = new Map(skipped.map((id, i) => [id, i]))
    return [...list.filter((i) => !rank.has(i.id)), ...list.filter((i) => rank.has(i.id)).sort((a, b) => rank.get(a.id) - rank.get(b.id))]
  }, [source, pendingIds, sort, decisionTab, skipped])

  const activeIndex = visible?.length ? Math.max(0, visible.findIndex((i) => i.id === activeId)) : -1
  const active = activeIndex >= 0 ? visible[activeIndex] : null
  const similar = useMemo(
    () => (decisionTab && active && visible ? similarIds(active, visible).filter((id) => !decideBlock(visible.find((i) => i.id === id), me)) : []),
    [decisionTab, active, visible, me],
  )
  const liveSelected = useMemo(() => new Set([...selected].filter((id) => visible?.some((i) => i.id === id))), [selected, visible])

  const pendingFocus = useRef(null)
  const focusItem = useCallback((id) => {
    setActiveId(id)
    pendingFocus.current = id
  }, [])

  // After render, so a card restored by undo exists before it's focused.
  useEffect(() => {
    const el = pendingFocus.current != null && cards.current.get(pendingFocus.current)
    if (el) {
      el.focus()
      pendingFocus.current = null
    }
  })

  const move = (delta) => {
    if (!visible?.length) return
    const next = visible[Math.min(visible.length - 1, Math.max(0, activeIndex + delta))]
    focusItem(next.id)
  }

  const nextAfter = (ids, from) => {
    const gone = new Set(ids)
    const after = visible.slice(from + 1).find((i) => !gone.has(i.id))
    const before = [...visible.slice(0, from)].reverse().find((i) => !gone.has(i.id))
    return (after || before)?.id ?? null
  }

  const decideIds = (ids, decision, from = activeIndex) => {
    if (!decisionTab || onBreak) return
    const allowed = ids.filter((id) => !decideBlock(visible.find((i) => i.id === id) || {}, me))
    if (!allowed.length) return
    const label = allowed.length === 1 ? `${VERB[decision]} #${allowed[0]}` : `${VERB[decision]} ${allowed.length} items`
    const nextId = nextAfter(allowed, from)
    decide({ ids: allowed, decision, label })
    setSelected((s) => new Set([...s].filter((id) => !allowed.includes(id))))
    if (nextId != null) focusItem(nextId)
  }

  const decideCurrent = (decision) => {
    if (liveSelected.size) decideIds([...liveSelected], decision)
    else if (active) decideIds([active.id], decision)
  }

  const skip = (item = active, from = activeIndex) => {
    if (!item || !decisionTab) return
    const nextId = nextAfter([item.id], from)
    setSkipped((s) => [...s.filter((id) => id !== item.id), item.id])
    setAnnouncement(`Skipped #${item.id}; moved to the end of the queue.`)
    if (nextId != null) focusItem(nextId)
  }

  const toggle = (id) =>
    setSelected((s) => {
      const next = new Set(s)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const selectSimilar = () => {
    if (!active) return
    setSelected(new Set([active.id, ...similar]))
    setAnnouncement(`Selected #${active.id} and ${similar.length} similar ${similar.length === 1 ? 'item' : 'items'}.`)
  }

  const undoLast = () => {
    const restored = undo()
    if (!restored.length) return
    setAnnouncement(`Undone. ${restored.length === 1 ? `#${restored[0]} is` : `${restored.length} items are`} back in the queue.`)
    focusItem(restored[0])
  }

  const startBreak = async () => {
    setBreakBusy(true)
    try {
      setWellbeing(await takeBreak())
    } catch (err) {
      if (!onError(err)) toast(err.message || 'Could not start the break.', { tone: 'error' })
    } finally {
      setBreakBusy(false)
    }
  }

  useEffect(() => {
    const onKey = (e) => {
      if (onBreak || tab === 'appeals' || e.defaultPrevented || isTyping(e.target)) return
      const key = e.key.length === 1 ? e.key.toLowerCase() : e.key
      if ((e.ctrlKey || e.metaKey) && key === 'z' && !e.shiftKey) {
        e.preventDefault()
        return undoLast()
      }
      if (e.ctrlKey || e.metaKey || e.altKey) return
      const onCard = e.target instanceof HTMLElement && e.target.tagName === 'ARTICLE'
      const actions = {
        j: () => move(1),
        k: () => move(-1),
        ArrowDown: onCard ? () => move(1) : null,
        ArrowUp: onCard ? () => move(-1) : null,
        a: decisionTab ? () => decideCurrent('ALLOW') : null,
        r: decisionTab ? () => decideCurrent('AUTO_FLAG') : null,
        s: decisionTab ? () => skip() : null,
        x: () => active && decisionTab && !decideBlock(active, me) && toggle(active.id),
        m: () => similar.length && selectSimilar(),
        u: undoLast,
        Escape: liveSelected.size ? () => setSelected(new Set()) : null,
      }
      const run = actions[key]
      if (!run) return
      e.preventDefault()
      run()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  if (!authLoading && !isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  const count = (key) => (lists[key] ? lists[key].filter((i) => !pendingIds.has(i.id)).length : null)
  const pendingCount = count('review')
  const tabs = [
    { key: 'review', label: 'Needs review', icon: Inbox, n: pendingCount, tone: 'text-amber-300' },
    { key: 'escalated', label: 'Escalated', icon: ArrowUpCircle, n: count('escalated'), tone: 'text-violet-300' },
    { key: 'second-review', label: 'Second review', icon: Scale, n: count('second-review'), tone: 'text-sky-300' },
    { key: 'appeals', label: 'Appeals', icon: Gavel, n: appealCount, tone: 'text-emerald-300' },
    { key: 'flagged', label: 'Auto-flagged', icon: ShieldBan, n: count('flagged'), tone: 'text-rose-300' },
  ]

  const switchTab = (key) => {
    setTab(key)
    setSelected(new Set())
    setActiveId(null)
  }

  const onTabKey = (e) => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return
    e.preventDefault()
    const i = tabs.findIndex((t) => t.key === tab)
    const next = tabs[(i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length].key
    switchTab(next)
    tabRefs.current[next]?.focus()
  }

  const renderPanel = () => {
    if (onBreak) return <BreakScreen wellbeing={wellbeing} onEnded={refreshMe} />
    if (tab === 'appeals') {
      return <AppealsPanel thresholds={thresholds} disabled={onBreak} refreshKey={reloadKey} onCount={setAppealCount} onError={onError} />
    }
    if (visible === null) return error ? null : <LoadingState label="Loading the review queue…" />
    if (visible.length === 0) {
      if (tab === 'review') {
        return (
          <EmptyState icon={Inbox} title="All clear" description="Nothing is waiting for review. New items appear here automatically when an upload lands in the review band.">
            <Link to="/analyze" className={buttonClass('primary')}>
              <ScanSearch aria-hidden className="size-4" /> Analyze media
            </Link>
            <a href={mockFeedUrl()} target="_blank" rel="noreferrer" className={buttonClass('secondary')}>
              Open mock feed
            </a>
          </EmptyState>
        )
      }
      return <EmptyState {...EMPTY[tab]} />
    }
    return (
      <div role="list" aria-label={tabs.find((t) => t.key === tab)?.label} className="grid md:grid-cols-2 gap-5">
        {visible.map((item, i) => (
          <div key={item.id} role="listitem" className="animate-fade-up" style={{ animationDelay: `${Math.min(i, 8) * 40}ms` }}>
            <QueueItem
              item={item}
              tab={tab}
              me={me}
              teams={teams}
              thresholds={thresholds}
              active={i === activeIndex}
              selected={liveSelected.has(item.id)}
              deadline={deadlineInfo(item.slaDeadline, now)}
              cardRef={(el) => (el ? cards.current.set(item.id, el) : cards.current.delete(item.id))}
              disabled={onBreak}
              onActivate={() => setActiveId(item.id)}
              onToggle={() => toggle(item.id)}
              onDecide={(decision) => decideIds([item.id], decision, i)}
              onSkip={() => skip(item, i)}
              onChanged={onChanged}
              onError={onError}
              onWellbeing={(w) => w && setWellbeing(w)}
              onUnauthorized={logout}
            />
          </div>
        ))}
      </div>
    )
  }

  return (
    <>
      <PageHeader
        eyebrow="Human in the loop"
        title="Moderation queue"
        description="Complaints with the nearest legal deadline come first. Decisions can be undone for 10 seconds; overriding an automatic removal needs a second moderator. Media stays greyscale and blurred unless you reveal it."
        actions={
          <>
            <span className="inline-flex items-center gap-2 h-10 px-4 rounded-xl border border-amber-400/25 bg-amber-400/[0.06] text-sm text-amber-200">
              <Inbox aria-hidden className="size-4" />
              <span className="tabular-nums font-semibold">{pendingCount ?? '–'}</span> pending
            </span>
            <Button variant="secondary" icon={RefreshCw} onClick={() => onChanged()} disabled={refreshing} className={cn(refreshing && '[&>svg]:animate-spin')}>
              Refresh
            </Button>
          </>
        }
      />

      <p className="sr-only" aria-live="polite">
        {announcement}
      </p>

      {error && <ErrorState error={error} title="Couldn't load the queue" onRetry={() => load()} className="mb-6" />}

      {!onBreak && <WellbeingMeter wellbeing={wellbeing} onBreak={startBreak} busy={breakBusy} />}
      {decisionTab && !onBreak && <ShortcutLegend />}

      <div className="flex flex-wrap items-center justify-between gap-3 mb-6">
        <div role="tablist" aria-label="Queue" onKeyDown={onTabKey} className="inline-flex flex-wrap p-1 rounded-xl border border-line bg-white/[0.02]">
          {tabs.map(({ key, label, icon: Icon, n, tone }) => (
            <button
              key={key}
              ref={(el) => (tabRefs.current[key] = el)}
              id={`queue-tab-${key}`}
              type="button"
              role="tab"
              aria-selected={tab === key}
              aria-controls="queue-panel"
              tabIndex={tab === key ? 0 : -1}
              onClick={() => switchTab(key)}
              className={cn(
                'inline-flex items-center gap-2 h-9 px-3.5 rounded-lg text-sm font-medium transition',
                tab === key ? 'bg-white/[0.08] text-fg shadow-sm' : 'text-muted hover:text-fg',
              )}
            >
              <Icon aria-hidden className={cn('size-4', tab === key && tone)} />
              {label}
              <span className="tabular-nums text-xs text-subtle">{n ?? '–'}</span>
            </button>
          ))}
        </div>
        {tab !== 'appeals' && (
          <div className="flex flex-wrap items-center gap-3">
            <label className="inline-flex items-center gap-2 text-sm text-muted">
              Show
              <select
                value={scope}
                onChange={(e) => setScope(e.target.value)}
                className="h-9 rounded-xl border border-line bg-white/[0.03] px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-brand/40"
              >
                {SCOPES.map(([value, label]) => (
                  <option key={value} value={value} className="bg-surface-2">
                    {label}
                    {value === 'team' && me?.team ? ` (${me.team})` : ''}
                  </option>
                ))}
              </select>
            </label>
            <label className="inline-flex items-center gap-2 text-sm text-muted">
              Sort
              <select
                value={sort}
                onChange={(e) => setSort(e.target.value)}
                className="h-9 rounded-xl border border-line bg-white/[0.03] px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-brand/40"
              >
                <option value="deadline" className="bg-surface-2">
                  Nearest deadline first
                </option>
                <option value="newest" className="bg-surface-2">
                  Newest first
                </option>
              </select>
            </label>
          </div>
        )}
      </div>

      {decisionTab && !onBreak && (
        <BatchBar
          count={liveSelected.size}
          similar={liveSelected.size ? 0 : similar.length}
          onSelectSimilar={selectSimilar}
          onApprove={() => decideIds([...liveSelected], 'ALLOW')}
          onReject={() => decideIds([...liveSelected], 'AUTO_FLAG')}
          onClear={() => setSelected(new Set())}
        />
      )}

      <div id="queue-panel" role="tabpanel" aria-labelledby={`queue-tab-${tab}`}>
        {renderPanel()}
      </div>
    </>
  )
}
