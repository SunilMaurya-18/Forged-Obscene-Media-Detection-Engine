import { Suspense, useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  BarChart3,
  BookOpen,
  ChevronRight,
  ExternalLink,
  FilePlus2,
  FolderSearch,
  Gavel,
  HeartHandshake,
  Inbox,
  KeyRound,
  Loader2,
  LogIn,
  LogOut,
  Menu,
  Scale,
  ScanSearch,
  Search,
  ShieldCheck,
  Sparkles,
  Stamp,
  Timer,
  WifiOff,
  X,
} from 'lucide-react'
import { fetchComplianceSummary } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { useDemo } from '../context/DemoContext'
import { useStats } from '../context/StatsContext'
import { cn, mockFeedUrl } from '../lib/format'
import DemoBanner from './DemoBanner'
import LanguageToggle from './LanguageToggle'
import { Logo } from './Logo'
import { LoadingState } from './Status'
import ThemeToggle from './ThemeToggle'
import { buttonClass } from './ui'

/** Signed-out visitors: the end-user flows (report, track), translated. */
const PUBLIC_NAV = [
  { to: '/tour', key: 'tour', icon: Sparkles, demoOnly: true },
  { to: '/analyze', key: 'analyze', icon: ScanSearch },
  { to: '/report', key: 'report', icon: FilePlus2 },
  { to: '/status', key: 'status', icon: Search },
  { to: '/appeal', key: 'appeal', icon: Gavel },
  { to: '/verify', key: 'verify', icon: Stamp },
  { to: '/dashboard', key: 'dashboard', icon: BarChart3 },
  { to: '/explained', key: 'explained', icon: BookOpen, iconOnly: true },
]

/** Moderators: English-first operational screens, grouped the way the work flows. */
const MODERATOR_NAV = [
  {
    label: 'Workspace',
    items: [
      { to: '/analyze', label: 'Analyze', icon: ScanSearch },
      { to: '/queue', label: 'Queue', icon: Inbox, badge: 'queue' },
      { to: '/compliance', label: 'Compliance', icon: Timer, badge: 'compliance' },
    ],
  },
  {
    label: 'Cases',
    items: [
      { to: '/investigations', label: 'Investigations', icon: FolderSearch },
      { to: '/legal-requests', label: 'Legal requests', icon: Scale },
    ],
  },
  {
    label: 'Trust',
    items: [
      { to: '/verify', label: 'Verify', icon: Stamp },
      { to: '/dashboard', label: 'Dashboard', icon: BarChart3 },
      { to: '/api-access', label: 'API access', icon: KeyRound },
    ],
  },
]

const MODERATOR_FOOTER_NAV = [
  { to: '/explained', label: 'How it works', icon: BookOpen },
  { to: '/support', label: 'Wellbeing support', icon: HeartHandshake },
]

/** Phase 28: investigators see only the cases their approved legal requests cover. */
const INVESTIGATOR_NAV = [
  { label: 'Cases', items: [{ to: '/investigator', label: 'My cases', icon: ShieldCheck }] },
  { label: 'Tools', items: [{ to: '/verify', label: 'Verify', icon: Stamp }] },
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

/** Escape closes the open drawer; the page behind stops scrolling while it is open. */
function useDrawer() {
  const [open, setOpen] = useState(false)
  useEffect(() => {
    if (!open) return
    const onKey = (e) => e.key === 'Escape' && setOpen(false)
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    window.addEventListener('keydown', onKey)
    return () => {
      document.body.style.overflow = overflow
      window.removeEventListener('keydown', onKey)
    }
  }, [open])
  return [open, setOpen]
}

function NavBadge({ count, tone, label }) {
  if (!count) return null
  return (
    <span
      className={cn(
        'ml-auto grid place-items-center min-w-5 h-5 px-1.5 rounded-md text-[11px] font-semibold tabular-nums',
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

/** Shape differs per state (pulsing dot / spinner / crossed wifi), so it isn't colour-only. */
function ApiStatus({ online, withLabel = false, className }) {
  const label = online == null ? 'Connecting to the API' : online ? 'API online' : 'API offline'
  const short = online == null ? 'Connecting' : online ? 'Operational' : 'Offline'
  return (
    <span
      role="img"
      aria-label={label}
      title={label}
      className={cn(
        'items-center justify-center gap-2 h-8 rounded-lg border border-line text-xs font-medium text-muted',
        withLabel ? 'px-2.5' : 'w-8',
        className,
      )}
    >
      {online == null ? (
        <Loader2 aria-hidden className="size-3.5 animate-spin text-slate-400" />
      ) : online ? (
        <span aria-hidden className="relative flex size-2">
          <span className="absolute inset-0 rounded-full animate-ping opacity-60 bg-emerald-400" />
          <span className="relative size-2 rounded-full bg-emerald-400" />
        </span>
      ) : (
        <WifiOff aria-hidden className="size-3.5 text-rose-300" />
      )}
      {withLabel && <span aria-hidden>{short}</span>}
    </span>
  )
}

function SkipLink() {
  const { t } = useTranslation()
  return (
    <a
      href="#main"
      className="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-[60] focus:rounded-xl focus:bg-surface-2 focus:px-4 focus:py-2 focus:text-sm focus:font-medium focus:ring-2 focus:ring-brand"
    >
      {t('common.skipToContent')}
    </a>
  )
}

function Main({ className }) {
  const location = useLocation()
  return (
    <main
      id="main"
      tabIndex={-1}
      key={location.pathname.split('/')[1]}
      className={cn('flex-1 w-full mx-auto px-4 md:px-8 focus:outline-none print:max-w-none print:p-0', className)}
    >
      <Suspense fallback={<LoadingState />}>
        <Outlet />
      </Suspense>
    </main>
  )
}

/* ------------------------------------------------------------------ console (signed in) */

const sideLinkClass = ({ isActive }) =>
  cn(
    'group flex items-center gap-2.5 h-9 px-2.5 rounded-lg text-[13.5px] font-medium transition-colors',
    isActive
      ? 'bg-white/[0.07] text-fg shadow-[inset_0_1px_0_rgb(255_255_255/0.04)]'
      : 'text-muted hover:text-fg hover:bg-white/[0.04]',
  )

function SideLink({ to, label, icon: Icon, badge, onNavigate }) {
  return (
    <NavLink to={to} className={sideLinkClass} onClick={onNavigate}>
      {({ isActive }) => (
        <>
          <Icon aria-hidden className={cn('size-4 shrink-0', isActive ? 'text-brand' : 'text-subtle group-hover:text-muted')} />
          <span className="truncate">{label}</span>
          {badge}
        </>
      )}
    </NavLink>
  )
}

function roleLabel({ isSenior, isModerator, isInvestigator }) {
  if (isSenior) return 'Senior moderator'
  if (isModerator) return 'Moderator'
  if (isInvestigator) return 'Investigator'
  return 'Signed in'
}

function Sidebar({ groups, badges, onNavigate, onClose }) {
  const auth = useAuth()
  const { user, logout, isAuthenticated, isInvestigator } = auth
  const { enabled: demoEnabled } = useDemo()
  const name = user?.username || '?'
  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between gap-2 h-14 px-4 border-b border-line">
        <NavLink
          to={isInvestigator ? '/investigator' : '/queue'}
          onClick={onNavigate}
          className="min-w-0 rounded-lg"
          aria-label="Forged Media Detection, console home"
        >
          <Logo always />
        </NavLink>
        {onClose && (
          <button
            type="button"
            onClick={onClose}
            autoFocus
            aria-label="Close menu"
            className="grid place-items-center size-8 shrink-0 rounded-lg text-muted hover:text-fg hover:bg-white/[0.06]"
          >
            <X aria-hidden className="size-4" />
          </button>
        )}
      </div>

      <nav aria-label="Console" className="flex-1 overflow-y-auto px-3 py-4 space-y-6">
        {groups.map(({ label, items }) => (
          <div key={label}>
            <p className="label-caps px-2.5 mb-1.5">{label}</p>
            <ul className="space-y-0.5">
              {items.map((item) => (
                <li key={item.to}>
                  <SideLink {...item} badge={item.badge && badges[item.badge]} onNavigate={onNavigate} />
                </li>
              ))}
            </ul>
          </div>
        ))}
      </nav>

      <div className="px-3 pb-3 space-y-0.5">
        {isAuthenticated && (
          <>
            {MODERATOR_FOOTER_NAV.map((item) => (
              <SideLink key={item.to} {...item} onNavigate={onNavigate} />
            ))}
            {demoEnabled && (
              <a href={mockFeedUrl()} target="_blank" rel="noreferrer" className={sideLinkClass({ isActive: false })}>
                <ExternalLink aria-hidden className="size-4 shrink-0 text-subtle" />
                <span className="truncate">Mock feed</span>
                <span className="sr-only">(opens in a new tab)</span>
              </a>
            )}
          </>
        )}
        <div className="mt-3 flex items-center gap-2.5 rounded-xl border border-line bg-surface-2 p-2">
          <span
            aria-hidden
            className="grid place-items-center size-8 shrink-0 rounded-lg bg-brand text-[#fff] text-xs font-bold uppercase"
          >
            {name[0]}
          </span>
          <div className="min-w-0 flex-1 leading-tight">
            <p className="text-[13px] font-medium truncate">{name}</p>
            <p className="text-[11px] text-subtle truncate">{roleLabel(auth)}</p>
          </div>
          <button
            type="button"
            onClick={logout}
            title="Sign out"
            aria-label={`Sign out ${name}`}
            className="grid place-items-center size-8 shrink-0 rounded-lg text-muted hover:text-fg hover:bg-white/[0.06] transition"
          >
            <LogOut aria-hidden className="size-4" />
          </button>
        </div>
      </div>
    </div>
  )
}

function currentItem(groups, pathname) {
  for (const group of groups) {
    for (const item of group.items) {
      if (pathname === item.to || pathname.startsWith(`${item.to}/`)) return { group: group.label, item }
    }
  }
  const extra = MODERATOR_FOOTER_NAV.find((item) => pathname === item.to)
  return extra ? { group: 'Help', item: extra } : null
}

function ConsoleShell() {
  const { isAuthenticated } = useAuth()
  const { online, reviewCount } = useStats()
  const compliance = useComplianceAlerts(isAuthenticated)
  const location = useLocation()
  const [drawer, setDrawer] = useDrawer()
  const groups = isAuthenticated ? MODERATOR_NAV : INVESTIGATOR_NAV
  const here = currentItem(groups, location.pathname)
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

  return (
    <div className="min-h-screen lg:pl-64">
      <SkipLink />

      <aside className="hidden lg:block fixed inset-y-0 left-0 z-30 w-64 border-r border-line bg-surface print:hidden">
        <Sidebar groups={groups} badges={badges} />
      </aside>

      {drawer && (
        <div className="lg:hidden fixed inset-0 z-50 print:hidden" role="dialog" aria-modal="true" aria-label="Navigation">
          <button
            type="button"
            tabIndex={-1}
            aria-label="Close menu"
            onClick={() => setDrawer(false)}
            className="absolute inset-0 bg-black/60 backdrop-blur-sm animate-fade-up"
          />
          <aside className="absolute inset-y-0 left-0 w-72 max-w-[85vw] border-r border-line bg-surface shadow-2xl animate-slide-in">
            <Sidebar groups={groups} badges={badges} onNavigate={() => setDrawer(false)} onClose={() => setDrawer(false)} />
          </aside>
        </div>
      )}

      <div className="flex min-h-screen flex-col">
        <DemoBanner />
        <header className="sticky top-0 z-20 h-14 border-b border-line bg-canvas/80 backdrop-blur-xl print:hidden">
          <div className="h-full max-w-7xl mx-auto px-4 md:px-8 flex items-center gap-3">
            <button
              type="button"
              onClick={() => setDrawer(true)}
              aria-label="Open menu"
              aria-expanded={drawer}
              className="lg:hidden grid place-items-center size-9 -ml-1.5 rounded-lg text-muted hover:text-fg hover:bg-white/[0.06]"
            >
              <Menu aria-hidden className="size-5" />
            </button>
            <nav aria-label="Breadcrumb" className="min-w-0">
              <ol className="flex items-center gap-1.5 text-[13px]">
                <li className="text-subtle hidden sm:block">{here?.group ?? 'Console'}</li>
                {here && (
                  <>
                    <li aria-hidden className="text-subtle hidden sm:block">
                      <ChevronRight className="size-3.5" />
                    </li>
                    <li aria-current="page" className="font-medium text-fg truncate">
                      {here.item.label}
                    </li>
                  </>
                )}
              </ol>
            </nav>
            <div className="ml-auto flex items-center gap-2">
              <ApiStatus online={online} withLabel className="hidden sm:inline-flex" />
              <LanguageToggle className="hidden sm:inline-flex" />
              <ThemeToggle />
            </div>
          </div>
        </header>

        <Main className="max-w-7xl py-8 md:py-10" />
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ public site */

const topLinkClass = ({ isActive }) =>
  cn(
    'inline-flex items-center gap-1.5 h-9 px-3 rounded-lg text-[13.5px] font-medium whitespace-nowrap transition-colors',
    isActive ? 'text-fg bg-white/[0.06]' : 'text-muted hover:text-fg',
  )

function FooterColumn({ title, links }) {
  return (
    <div>
      <p className="label-caps mb-3">{title}</p>
      <ul className="space-y-2">
        {links.map(({ to, label }) => (
          <li key={to}>
            <NavLink to={to} className="text-[13px] text-muted hover:text-fg transition-colors">
              {label}
            </NavLink>
          </li>
        ))}
      </ul>
    </div>
  )
}

function PublicShell() {
  const { t } = useTranslation()
  const { online } = useStats()
  const { enabled: demoEnabled } = useDemo()
  const onLogin = useLocation().pathname === '/login'
  const [menu, setMenu] = useDrawer()
  const nav = PUBLIC_NAV.filter((item) => demoEnabled || !item.demoOnly).map((item) => ({ ...item, label: t(`common.nav.${item.key}`) }))
  const label = (key) => t(`common.nav.${key}`)

  return (
    <div className="relative min-h-screen flex flex-col">
      <SkipLink />

      <div aria-hidden className="pointer-events-none fixed inset-0 -z-10 overflow-hidden print:hidden">
        <div className="absolute inset-0 bg-grid [mask-image:radial-gradient(ellipse_70%_45%_at_50%_0%,black,transparent)]" />
        <div className="absolute -top-56 left-1/2 -translate-x-1/2 h-[420px] w-[820px] rounded-full bg-brand/[0.13] blur-[120px]" />
      </div>

      <DemoBanner />

      <header className="sticky top-0 z-40 border-b border-line bg-canvas/75 backdrop-blur-xl print:hidden">
        <div className="max-w-6xl mx-auto px-4 md:px-8 h-16 flex items-center gap-6">
          <NavLink to="/" className="shrink-0 rounded-lg" aria-label="Forged Media Detection, home">
            <Logo />
          </NavLink>

          <nav aria-label="Main" className="hidden lg:flex items-center gap-0.5">
            {nav.map(({ to, label: text, icon: Icon, iconOnly }) => (
              <NavLink key={to} to={to} title={text} className={topLinkClass}>
                {iconOnly && <Icon aria-hidden className="size-4" />}
                <span className={iconOnly ? 'sr-only' : undefined}>{text}</span>
              </NavLink>
            ))}
          </nav>

          <div className="ml-auto flex items-center gap-2">
            <ApiStatus online={online} className="hidden sm:inline-flex" />
            <LanguageToggle className="hidden sm:inline-flex" />
            <ThemeToggle className="hidden sm:grid" />
            {!onLogin && (
              <NavLink to="/login" title={t('common.signIn')} className={buttonClass('secondary', 'sm', 'h-9 max-sm:px-2.5')}>
                <LogIn aria-hidden className="size-4" />
                <span className="max-sm:sr-only">{t('common.signIn')}</span>
              </NavLink>
            )}
            <button
              type="button"
              onClick={() => setMenu(true)}
              aria-label={t('common.menu.open')}
              aria-expanded={menu}
              className="lg:hidden grid place-items-center size-9 rounded-lg border border-line text-muted hover:text-fg hover:bg-white/[0.05]"
            >
              <Menu aria-hidden className="size-4" />
            </button>
          </div>
        </div>
      </header>

      {menu && (
        <div className="lg:hidden fixed inset-0 z-50 print:hidden" role="dialog" aria-modal="true" aria-label={t('common.menu.open')}>
          <button
            type="button"
            tabIndex={-1}
            aria-label={t('common.menu.close')}
            onClick={() => setMenu(false)}
            className="absolute inset-0 bg-black/60 backdrop-blur-sm animate-fade-up"
          />
          <div className="absolute inset-y-0 right-0 w-80 max-w-[88vw] flex flex-col border-l border-line bg-surface shadow-2xl animate-slide-in-right">
            <div className="flex items-center justify-between h-16 px-4 border-b border-line">
              <Logo always />
              <button
                type="button"
                onClick={() => setMenu(false)}
                autoFocus
                aria-label={t('common.menu.close')}
                className="grid place-items-center size-8 rounded-lg text-muted hover:text-fg hover:bg-white/[0.06]"
              >
                <X aria-hidden className="size-4" />
              </button>
            </div>
            <nav aria-label="Main" className="flex-1 overflow-y-auto p-3 space-y-0.5">
              {nav.map(({ to, label: text, icon: Icon }) => (
                <NavLink key={to} to={to} onClick={() => setMenu(false)} className={sideLinkClass}>
                  <Icon aria-hidden className="size-4 text-subtle" />
                  {text}
                </NavLink>
              ))}
            </nav>
            <div className="p-4 border-t border-line flex items-center gap-2">
              <LanguageToggle />
              <ThemeToggle />
              <ApiStatus online={online} className="inline-flex ml-auto" />
            </div>
          </div>
        </div>
      )}

      <Main className="max-w-6xl py-10 md:py-14" />

      <footer className="border-t border-line bg-surface/40 print:hidden">
        <div className="max-w-6xl mx-auto px-4 md:px-8 py-12 grid gap-10 sm:grid-cols-2 lg:grid-cols-[1.6fr_1fr_1fr_1fr]">
          <div className="max-w-xs">
            <Logo always />
            <p className="mt-4 text-[13px] text-muted leading-relaxed">{t('common.footer.stored')}</p>
          </div>
          <FooterColumn
            title={t('common.footer.product')}
            links={[
              ...(demoEnabled ? [{ to: '/tour', label: label('tour') }] : []),
              { to: '/analyze', label: label('analyze') },
              { to: '/verify', label: label('verify') },
              { to: '/dashboard', label: label('dashboard') },
            ]}
          />
          <FooterColumn
            title={t('common.footer.help')}
            links={[
              { to: '/report', label: label('report') },
              { to: '/status', label: label('status') },
              { to: '/appeal', label: label('appeal') },
              { to: '/explained', label: label('explained') },
            ]}
          />
          <FooterColumn
            title={t('common.footer.legal')}
            links={[
              { to: '/transparency', label: label('transparency') },
              { to: '/terms', label: t('common.footer.terms') },
              { to: '/privacy', label: t('common.footer.privacy') },
            ]}
          />
        </div>
        <div className="border-t border-line">
          <div className="max-w-6xl mx-auto px-4 md:px-8 py-5 flex flex-col sm:flex-row gap-2 sm:items-center justify-between text-xs text-subtle">
            <p>© {new Date().getFullYear()} Forged Media Detection</p>
            <p>{t('common.footer.benchmarks')}</p>
          </div>
        </div>
      </footer>
    </div>
  )
}

export default function Layout() {
  const { isSignedIn } = useAuth()
  return isSignedIn ? <ConsoleShell /> : <PublicShell />
}
