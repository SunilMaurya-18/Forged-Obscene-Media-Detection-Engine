import { Suspense, useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  BarChart3,
  BookOpen,
  ExternalLink,
  FilePlus2,
  Inbox,
  KeyRound,
  Loader2,
  LogIn,
  LogOut,
  ScanSearch,
  Search,
  Stamp,
  Timer,
  WifiOff,
} from 'lucide-react'
import { fetchComplianceSummary } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { useStats } from '../context/StatsContext'
import { cn, mockFeedUrl } from '../lib/format'
import LanguageToggle from './LanguageToggle'
import { Logo } from './Logo'
import { LoadingState } from './Status'
import ThemeToggle from './ThemeToggle'
import { buttonClass } from './ui'

/** Signed-out visitors: the end-user flows (report, track), translated. */
const PUBLIC_NAV = [
  { to: '/analyze', key: 'analyze', icon: ScanSearch },
  { to: '/report', key: 'report', icon: FilePlus2 },
  { to: '/status', key: 'status', icon: Search },
  { to: '/verify', key: 'verify', icon: Stamp },
  { to: '/dashboard', key: 'dashboard', icon: BarChart3 },
  { to: '/explained', key: 'explained', icon: BookOpen, iconOnly: true },
]

/** Moderators: English-first operational screens. */
const MODERATOR_NAV = [
  { to: '/analyze', label: 'Analyze', icon: ScanSearch },
  { to: '/queue', label: 'Queue', icon: Inbox, badge: 'queue' },
  { to: '/compliance', label: 'Compliance', icon: Timer, badge: 'compliance' },
  { to: '/verify', label: 'Verify', icon: Stamp },
  { to: '/dashboard', label: 'Dashboard', icon: BarChart3 },
  { to: '/api-access', label: 'API', icon: KeyRound },
  { to: '/explained', label: 'Explained', icon: BookOpen, iconOnly: true },
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

function NavBadge({ count, tone, label }) {
  if (!count) return null
  return (
    <span
      className={cn(
        'grid place-items-center min-w-5 h-5 px-1.5 rounded-full text-[11px] font-semibold tabular-nums',
        tone === 'rose' ? 'bg-rose-400/15 text-rose-300' : 'bg-amber-400/15 text-amber-300',
      )}
    >
      <span aria-hidden>{count}</span>
      <span className="sr-only">
        ({count} {label})
      </span>
    </span>
  )
}

const navClass = ({ isActive }) =>
  cn(
    'relative inline-flex items-center gap-2 h-9 px-3.5 lg:px-2.5 rounded-xl text-sm font-medium whitespace-nowrap transition-all duration-200',
    isActive
      ? 'bg-white/[0.08] text-fg shadow-[inset_0_1px_0_rgb(255_255_255/0.06)]'
      : 'text-muted hover:text-fg hover:bg-white/[0.04]',
  )

/** Shape differs per state (pulsing dot / spinner / crossed wifi), so it isn't colour-only. */
function ApiStatus({ online }) {
  const label = online == null ? 'Connecting to the API' : online ? 'API online' : 'API offline'
  return (
    <span
      role="img"
      aria-label={label}
      title={label}
      className="hidden sm:inline-flex items-center justify-center size-9 rounded-xl border border-line"
    >
      {online == null ? (
        <Loader2 aria-hidden className="size-3.5 animate-spin text-slate-400" />
      ) : online ? (
        <span aria-hidden className="relative flex size-2">
          <span className="absolute inset-0 rounded-full animate-ping opacity-60 bg-emerald-400" />
          <span className="relative size-2 rounded-full bg-emerald-400" />
        </span>
      ) : (
        <WifiOff aria-hidden className="size-4 text-rose-300" />
      )}
    </span>
  )
}

export default function Layout() {
  const { t } = useTranslation()
  const { isAuthenticated, user, logout } = useAuth()
  const { online, reviewCount } = useStats()
  const compliance = useComplianceAlerts(isAuthenticated)
  const location = useLocation()
  const badges = {
    queue: <NavBadge count={reviewCount} label="waiting for review" />,
    compliance: (
      <NavBadge
        count={compliance.count}
        tone={compliance.breached ? 'rose' : 'amber'}
        label={compliance.breached ? 'at risk or past deadline' : 'close to deadline'}
      />
    ),
  }
  const nav = isAuthenticated
    ? MODERATOR_NAV
    : PUBLIC_NAV.map((item) => ({ ...item, label: t(`common.nav.${item.key}`) }))

  return (
    <div className="relative min-h-screen flex flex-col">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-[60] focus:rounded-xl focus:bg-surface-2 focus:px-4 focus:py-2 focus:text-sm focus:font-medium focus:ring-2 focus:ring-brand"
      >
        {t('common.skipToContent')}
      </a>

      <div aria-hidden className="pointer-events-none fixed inset-0 -z-10 overflow-hidden print:hidden">
        <div className="absolute inset-0 bg-grid [mask-image:radial-gradient(ellipse_80%_60%_at_50%_0%,black,transparent)]" />
        <div className="absolute -top-40 left-1/2 -translate-x-1/2 h-[480px] w-[900px] rounded-full bg-brand/20 blur-[120px]" />
        <div className="absolute top-20 right-[-10%] h-[320px] w-[420px] rounded-full bg-brand-2/10 blur-[120px]" />
      </div>

      <header className="sticky top-0 z-40 border-b border-line bg-canvas/70 backdrop-blur-xl print:hidden">
        <div className="max-w-6xl mx-auto px-4 md:px-8 h-16 flex items-center justify-between gap-4">
          <NavLink to="/" className="shrink-0 rounded-lg" aria-label="Forged Media Detection, home">
            <Logo compact={isAuthenticated} />
          </NavLink>

          <nav aria-label="Main" className="hidden lg:flex items-center gap-1 rounded-2xl border border-line bg-white/[0.02] p-1">
            {nav.map(({ to, label, icon: Icon, end, badge, iconOnly }) => (
              <NavLink key={to} to={to} end={end} title={label} className={navClass}>
                <Icon aria-hidden className="size-4" />
                <span className={iconOnly ? 'sr-only' : undefined}>{label}</span>
                {badge && badges[badge]}
              </NavLink>
            ))}
            {isAuthenticated && (
              <a href={mockFeedUrl()} target="_blank" rel="noreferrer" title="Mock feed (opens in a new tab)" aria-label="Mock feed (opens in a new tab)" className={navClass({ isActive: false })}>
                <ExternalLink aria-hidden className="size-3.5 opacity-70" />
              </a>
            )}
          </nav>

          <div className="flex items-center gap-2 lg:max-xl:gap-1.5">
            <ApiStatus online={online} />
            <LanguageToggle className="hidden sm:inline-flex" iconClassName={isAuthenticated ? 'lg:max-2xl:hidden' : undefined} />
            <ThemeToggle className="hidden sm:grid" />
            {isAuthenticated ? (
              <div className="flex items-center gap-1 h-9 pl-1 pr-1 rounded-xl border border-line">
                <span
                  role="img"
                  aria-label={`Signed in as ${user?.username ?? 'moderator'}`}
                  title={`Signed in as ${user?.username ?? 'moderator'}`}
                  className="grid place-items-center size-7 rounded-lg bg-brand-gradient text-[#fff] text-xs font-bold uppercase"
                >
                  {user?.username?.[0] || 'M'}
                </span>
                <button
                  type="button"
                  onClick={logout}
                  title="Sign out"
                  aria-label="Sign out"
                  className="grid place-items-center size-7 rounded-lg text-muted hover:text-fg hover:bg-white/[0.06] transition"
                >
                  <LogOut aria-hidden className="size-4" />
                </button>
              </div>
            ) : (
              <NavLink to="/login" title={t('common.signIn')} className={buttonClass('secondary', 'sm', 'h-9 rounded-xl max-sm:px-2.5')}>
                <LogIn aria-hidden className="size-4" />
                <span className="max-sm:sr-only">{t('common.signIn')}</span>
              </NavLink>
            )}
          </div>
        </div>

        <nav aria-label="Main" className="lg:hidden flex items-center gap-1 overflow-x-auto px-4 pb-3 -mt-1">
          <div className="sm:hidden flex items-center gap-1 shrink-0 pr-2 mr-1 border-r border-line">
            <LanguageToggle />
            <ThemeToggle />
          </div>
          {nav.map(({ to, label, icon: Icon, end, badge }) => (
            <NavLink key={to} to={to} end={end} className={navClass}>
              <Icon aria-hidden className="size-4" />
              {label}
              {badge && badges[badge]}
            </NavLink>
          ))}
          {isAuthenticated && (
            <a href={mockFeedUrl()} target="_blank" rel="noreferrer" className={navClass({ isActive: false })}>
              Feed <ExternalLink aria-hidden className="size-3.5 opacity-70" />
            </a>
          )}
        </nav>
      </header>

      <main
        id="main"
        tabIndex={-1}
        key={location.pathname.split('/')[1]}
        className="flex-1 w-full max-w-6xl mx-auto px-4 md:px-8 py-10 md:py-14 focus:outline-none print:max-w-none print:p-0"
      >
        <Suspense fallback={<LoadingState />}>
          <Outlet />
        </Suspense>
      </main>

      <footer className="border-t border-line print:hidden">
        <div className="max-w-6xl mx-auto px-4 md:px-8 py-6 flex flex-col md:flex-row gap-3 md:items-center justify-between text-xs text-subtle">
          <p>{t('common.footer.stored')}</p>
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
            <p>{t('common.footer.benchmarks')}</p>
            <nav aria-label={t('common.footer.legal')} className="flex items-center gap-4">
              <NavLink to="/terms" className="hover:text-fg underline-offset-4 hover:underline transition">
                {t('common.footer.terms')}
              </NavLink>
              <NavLink to="/privacy" className="hover:text-fg underline-offset-4 hover:underline transition">
                {t('common.footer.privacy')}
              </NavLink>
            </nav>
          </div>
        </div>
      </footer>
    </div>
  )
}
