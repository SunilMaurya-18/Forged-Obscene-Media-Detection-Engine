import { useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { ArrowLeft, Eye, EyeOff, FileLock2, Inbox, Lock, LogIn, Scale, User } from 'lucide-react'
import { useAuth } from '../auth/AuthContext'
import { Logo } from '../components/Logo'
import { Alert, Button, Card, Field, INPUT } from '../components/ui'
import { cn } from '../lib/format'

const HIGHLIGHTS = [
  { icon: Inbox, title: 'Deadline-first review', body: 'The complaint closest to its legal deadline is always at the top of the queue.' },
  { icon: FileLock2, title: 'Evidence that verifies itself', body: 'Signed reports, a hash-chained custody log and packages anyone can check offline.' },
  { icon: Scale, title: 'Four-eyes by default', body: 'Overturning a removal or handing over a case always needs a second person.' },
]

export default function Login() {
  const { login, isAuthenticated, isInvestigator, loading } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = location.state?.from || '/queue'

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  if (!loading && (isAuthenticated || isInvestigator)) {
    return <Navigate to={isAuthenticated ? from : '/investigator'} replace />
  }

  const onSubmit = async (e) => {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      const me = await login(username, password)
      const roles = me?.roles || []
      if (roles.includes('ROLE_ADMIN') && (from === '/queue' || from === '/login')) {
        navigate('/admin', { replace: true })
      } else if (roles.includes('ROLE_MODERATOR')) {
        navigate(from, { replace: true })
      } else {
        navigate('/investigator', { replace: true })
      }
    } catch (err) {
      setError(err.status === 401 ? 'That username and password combination was not recognised.' : err.message || 'Sign-in failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="max-w-4xl mx-auto md:pt-4 animate-fade-up">
      <Card className="grid md:grid-cols-[1fr_1.05fr] overflow-hidden p-0">
        <div className="relative hidden md:flex flex-col justify-between gap-10 p-8 border-r border-line bg-surface-2 overflow-hidden">
          <div aria-hidden className="absolute inset-0 bg-grid [mask-image:linear-gradient(to_bottom,black,transparent_75%)]" />
          <div aria-hidden className="absolute -top-24 -left-24 size-72 rounded-full bg-white/[0.05] blur-3xl" />
          <div className="relative">
            <Logo always />
            <h2 className="mt-10 font-serif text-[26px] leading-[1.15] font-medium tracking-[-0.01em]">The console for trust &amp; safety teams.</h2>
            <p className="mt-2 text-sm text-muted leading-relaxed">
              Moderators review flagged uploads. Authorised investigators read the cases their approved legal requests cover.
            </p>
          </div>
          <ul className="relative space-y-5">
            {HIGHLIGHTS.map(({ icon: Icon, title, body }) => (
              <li key={title} className="flex gap-3">
                <span className="grid place-items-center size-8 shrink-0 rounded-lg border border-line-strong bg-surface text-brand">
                  <Icon aria-hidden className="size-4" />
                </span>
                <div>
                  <p className="text-[13.5px] font-medium">{title}</p>
                  <p className="mt-0.5 text-[13px] text-muted leading-relaxed">{body}</p>
                </div>
              </li>
            ))}
          </ul>
        </div>

        <div className="p-6 sm:p-8 md:p-10">
          <div className="md:hidden mb-8">
            <Logo always />
          </div>
          <h1 className="font-serif text-[30px] leading-[1.1] font-medium tracking-[-0.015em]">Sign in</h1>
          <p className="mt-1.5 text-sm text-muted">Use your console account. Public reporting never needs one.</p>

          <form onSubmit={onSubmit} className="mt-8 space-y-4">
            <Field label="Username">
              {(props) => (
                <div className="relative">
                  <User aria-hidden className="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-subtle pointer-events-none" />
                  <input
                    {...props}
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    autoComplete="username"
                    required
                    autoFocus
                    className={cn(INPUT, 'h-11 pl-9')}
                  />
                </div>
              )}
            </Field>
            <Field label="Password">
              {(props) => (
                <div className="relative">
                  <Lock aria-hidden className="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-subtle pointer-events-none" />
                  <input
                    {...props}
                    type={showPassword ? 'text' : 'password'}
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    autoComplete="current-password"
                    placeholder="••••••••"
                    required
                    className={cn(INPUT, 'h-11 pl-9 pr-11')}
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((v) => !v)}
                    aria-label={showPassword ? 'Hide password' : 'Show password'}
                    className="absolute right-1.5 top-1/2 -translate-y-1/2 grid place-items-center size-8 rounded-lg text-subtle hover:text-fg transition"
                  >
                    {showPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                  </button>
                </div>
              )}
            </Field>
            {error && <Alert>{error}</Alert>}
            <Button type="submit" size="lg" icon={LogIn} loading={busy} className="w-full">
              {busy ? 'Signing in…' : 'Sign in'}
            </Button>
          </form>
        </div>
      </Card>

      <Link to="/" className="mt-6 flex items-center justify-center gap-1.5 text-sm text-muted hover:text-fg transition">
        <ArrowLeft className="size-4" /> Back to home
      </Link>
    </div>
  )
}
