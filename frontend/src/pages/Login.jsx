import { useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { ArrowLeft, Eye, EyeOff, KeyRound, Lock, LogIn, User } from 'lucide-react'
import { useAuth } from '../auth/AuthContext'
import { LogoMark } from '../components/Logo'
import { Alert, Button, Card } from '../components/ui'

function Field({ icon: Icon, label, trailing, ...props }) {
  return (
    <label className="block">
      <span className="block text-[13px] font-medium text-muted mb-1.5">{label}</span>
      <span className="relative flex items-center">
        <Icon className="absolute left-3.5 size-4 text-subtle pointer-events-none" />
        <input
          {...props}
          className="w-full h-11 rounded-xl border border-line-strong bg-canvas/60 pl-10 pr-11 text-sm placeholder:text-subtle transition focus:outline-none focus:border-brand/60 focus:ring-4 focus:ring-brand/15"
        />
        {trailing && <span className="absolute right-2">{trailing}</span>}
      </span>
    </label>
  )
}

export default function Login() {
  const { login, isAuthenticated, loading } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = location.state?.from || '/queue'

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  if (!loading && isAuthenticated) {
    return <Navigate to={from} replace />
  }

  const onSubmit = async (e) => {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      await login(username, password)
      navigate(from, { replace: true })
    } catch (err) {
      setError(err.status === 401 ? 'That username and password combination was not recognised.' : err.message || 'Sign-in failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="max-w-sm mx-auto pt-4 md:pt-10 animate-fade-up">
      <div className="text-center mb-8">
        <div className="relative mx-auto mb-5 size-14">
          <div className="absolute inset-0 rounded-2xl bg-brand-gradient opacity-30 blur-xl" />
          <LogoMark className="relative size-14" />
        </div>
        <h1 className="text-2xl font-semibold tracking-tight">Moderator sign in</h1>
        <p className="text-sm text-muted mt-2">Access the review queue and act on flagged uploads.</p>
      </div>

      <Card className="p-6">
        <form onSubmit={onSubmit} className="space-y-4">
          <Field
            icon={User}
            label="Username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            placeholder="moderator"
            required
            autoFocus
          />
          <Field
            icon={Lock}
            label="Password"
            type={showPassword ? 'text' : 'password'}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            placeholder="••••••••"
            required
            trailing={
              <button
                type="button"
                onClick={() => setShowPassword((v) => !v)}
                aria-label={showPassword ? 'Hide password' : 'Show password'}
                className="grid place-items-center size-8 rounded-lg text-subtle hover:text-fg transition"
              >
                {showPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
              </button>
            }
          />
          {error && <Alert>{error}</Alert>}
          <Button type="submit" size="lg" icon={LogIn} loading={busy} className="w-full">
            {busy ? 'Signing in…' : 'Sign in'}
          </Button>
        </form>

        <div className="mt-5 flex items-center justify-between gap-3 rounded-xl border border-dashed border-line-strong px-3.5 py-3">
          <span className="flex items-center gap-2 text-xs text-muted">
            <KeyRound className="size-3.5 text-brand" />
            Demo: <span className="font-mono text-fg">moderator / moderator</span>
          </span>
          <button
            type="button"
            onClick={() => {
              setUsername('moderator')
              setPassword('moderator')
            }}
            className="text-xs font-medium text-brand hover:text-brand-2 transition"
          >
            Autofill
          </button>
        </div>
      </Card>

      <Link to="/" className="mt-6 flex items-center justify-center gap-1.5 text-sm text-muted hover:text-fg transition">
        <ArrowLeft className="size-4" /> Back to analysis
      </Link>
    </div>
  )
}
