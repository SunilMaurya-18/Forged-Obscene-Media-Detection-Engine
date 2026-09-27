import { useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { BarChart3, ExternalLink, Inbox, LogIn, LogOut, ScanSearch, ShieldCheck, Stamp, Timer } from 'lucide-react'
import { fetchComplianceSummary } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { useStats } from '../context/StatsContext'
import { cn, mockFeedUrl } from '../lib/format'
import { Logo } from './Logo'
import { buttonClass } from './ui'

const NAV = [
  { to: '/', label: 'Analyze', icon: ScanSearch, end: true },
  { to: '/queue', label: 'Queue', icon: Inbox, badge: 'queue' },
  { to: '/compliance', label: 'Compliance', icon: Timer, badge: 'compliance' },
  { to: '/verify', label: 'Verify', icon: Stamp },
  { to: '/dashboard', label: 'Dashboard', icon: BarChart3 },
]

/** At-risk + breached open complaints; moderator-only data, so only polled when signed in. */
function useComplianceAlerts(enabled) {
  const [alerts, setAlerts] = useState({ count: 0, breached: false })
  useEffect(() => {
    if (!enabled) return
    let cancelled = false
    const poll = async () => {
      try {
        const s = await fetchComplianceSummary()
        if (!cancelled) {
          const breached = s.byStatus?.BREACHED ?? 0
          setAlerts({ count: (s.byStatus?.AT_RISK ?? 0) + breached, breached: breached > 0 })
        }
      } catch {
        /* surfaced on the compliance page itself */
      }
    }
    poll()
    const timer = setInterval(poll, 15000)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [enabled])
  return enabled ? alerts : { count: 0, breached: false }
}

function NavBadge({ count, tone }) {
  if (!count) return null
  return (
    <span
      className={cn(
        'grid place-items-center min-w-5 h-5 px-1.5 rounded-full text-[11px] font-semibold tabular-nums',
        tone === 'rose' ? 'bg-rose-400/15 text-rose-300' : 'bg-amber-400/15 text-amber-300',
      )}
    >
      {count}
    </span>
  )
}

const navClass = ({ isActive }) =>
  cn(
    'relative inline-flex items-center gap-2 h-9 px-3.5 rounded-xl text-sm font-medium whitespace-nowrap transition-all duration-200',
    isActive
      ? 'bg-white/[0.08] text-fg shadow-[inset_0_1px_0_rgb(255_255_255/0.06)]'
      : 'text-muted hover:text-fg hover:bg-white/[0.04]',
  )

function ApiStatus({ online }) {
  const label = online == null ? 'Connecting' : online ? 'API online' : 'API offline'
  const dot = online == null ? 'bg-slate-400' : online ? 'bg-emerald-400' : 'bg-rose-400'
  return (
    <span
      title={label}
      className="hidden sm:inline-flex items-center gap-2 h-9 px-3 rounded-xl border border-line text-xs text-muted whitespace-nowrap"
    >
      <span className="relative flex size-2">
        {online && <span className={cn('absolute inset-0 rounded-full animate-ping opacity-60', dot)} />}
        <span className={cn('relative size-2 rounded-full', dot)} />
      </span>
      <span className="hidden 2xl:inline">{label}</span>
    </span>
  )
}

export default function Layout() {
  const { isAuthenticated, user, logout } = useAuth()
  const { online, reviewCount } = useStats()
  const compliance = useComplianceAlerts(isAuthenticated)
  const location = useLocation()
  const badges = {
    queue: <NavBadge count={reviewCount} />,
    compliance: <NavBadge count={compliance.count} tone={compliance.breached ? 'rose' : 'amber'} />,
  }

  return (
    <div className="relative min-h-screen flex flex-col">
      <div aria-hidden className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
        <div className="absolute inset-0 bg-grid [mask-image:radial-gradient(ellipse_80%_60%_at_50%_0%,black,transparent)]" />
        <div className="absolute -top-40 left-1/2 -translate-x-1/2 h-[480px] w-[900px] rounded-full bg-brand/20 blur-[120px]" />
        <div className="absolute top-20 right-[-10%] h-[320px] w-[420px] rounded-full bg-brand-2/10 blur-[120px]" />
      </div>

      <header className="sticky top-0 z-40 border-b border-line bg-canvas/70 backdrop-blur-xl">
        <div className="max-w-6xl mx-auto px-4 md:px-8 h-16 flex items-center justify-between gap-4">
          <NavLink to="/" className="shrink-0 rounded-lg">
            <Logo />
          </NavLink>

          <nav className="hidden lg:flex items-center gap-1 rounded-2xl border border-line bg-white/[0.02] p-1">
            {NAV.map(({ to, label, icon: Icon, end, badge }) => (
              <NavLink key={to} to={to} end={end} className={navClass}>
                <Icon className="size-4" />
                {label}
                {badge && badges[badge]}
              </NavLink>
            ))}
            <a href={mockFeedUrl()} target="_blank" rel="noreferrer" title="Mock feed" className={navClass({ isActive: false })}>
              <span className="hidden xl:inline">Mock feed</span>
              <ExternalLink className="size-3.5 opacity-70" />
            </a>
          </nav>

          <div className="flex items-center gap-2">
            <ApiStatus online={online} />
            {isAuthenticated ? (
              <div className="flex items-center gap-1 h-9 pl-1 pr-1 rounded-xl border border-line">
                <span className="grid place-items-center size-7 rounded-lg bg-brand-gradient text-[#060a12] text-xs font-bold uppercase">
                  {user?.username?.[0] || 'M'}
                </span>
                <span className="hidden 2xl:flex items-center gap-1 px-1.5 text-xs text-muted">
                  <ShieldCheck className="size-3.5 text-emerald-300" />
                  {user?.username}
                </span>
                <button
                  type="button"
                  onClick={logout}
                  title="Sign out"
                  aria-label="Sign out"
                  className="grid place-items-center size-7 rounded-lg text-muted hover:text-fg hover:bg-white/[0.06] transition"
                >
                  <LogOut className="size-4" />
                </button>
              </div>
            ) : (
              <NavLink to="/login" className={buttonClass('secondary', 'sm', 'h-9 rounded-xl')}>
                <LogIn className="size-4" />
                Sign in
              </NavLink>
            )}
          </div>
        </div>

        <nav className="lg:hidden flex items-center gap-1 overflow-x-auto px-4 pb-3 -mt-1">
          {NAV.map(({ to, label, icon: Icon, end, badge }) => (
            <NavLink key={to} to={to} end={end} className={navClass}>
              <Icon className="size-4" />
              {label}
              {badge && badges[badge]}
            </NavLink>
          ))}
          <a href={mockFeedUrl()} target="_blank" rel="noreferrer" className={navClass({ isActive: false })}>
            Feed <ExternalLink className="size-3.5 opacity-70" />
          </a>
        </nav>
      </header>

      <main key={location.pathname} className="flex-1 w-full max-w-6xl mx-auto px-4 md:px-8 py-10 md:py-14">
        <Outlet />
      </main>

      <footer className="border-t border-line">
        <div className="max-w-6xl mx-auto px-4 md:px-8 py-6 flex flex-col sm:flex-row gap-2 justify-between text-xs text-subtle">
          <p>Only perceptual hashes, scores, and decisions are stored. Raw media is never retained.</p>
          <p>Research benchmarks: FaceForensics++ · DFDC · Celeb-DF</p>
        </div>
      </footer>
    </div>
  )
}
