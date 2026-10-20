import { useCallback, useEffect, useState } from 'react'
import { Link, Navigate } from 'react-router-dom'
import {
  KeyRound,
  Plus,
  RefreshCw,
  ShieldCheck,
  UserCog,
  Users,
} from 'lucide-react'
import {
  createAdminAccount,
  fetchAdminAccounts,
  fetchAdminOverview,
  setAdminAccountPassword,
  updateAdminAccount,
} from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { useToast } from '../components/Toast'
import {
  Alert,
  Button,
  Card,
  CardHeader,
  EmptyState,
  Field,
  INPUT,
  PageHeader,
  StatTile,
  buttonClass,
} from '../components/ui'
import { cn } from '../lib/format'

const ROLES = ['ADMIN', 'SENIOR_MODERATOR', 'MODERATOR', 'INVESTIGATOR']

const ROLE_LABEL = {
  ADMIN: 'Owner admin',
  SENIOR_MODERATOR: 'Senior moderator',
  MODERATOR: 'Moderator',
  INVESTIGATOR: 'Investigator',
}

function errorText(err) {
  return err?.message || 'Request failed'
}

function CreateAccountForm({ onCreated, onCancel }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState('MODERATOR')
  const [team, setTeam] = useState('core')
  const [displayName, setDisplayName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      onCreated(await createAdminAccount({ username, password, role, team, displayName }))
    } catch (err) {
      setError(errorText(err))
      setBusy(false)
    }
  }

  return (
    <Card className="p-6 mb-6 animate-fade-up">
      <CardHeader icon={Plus} title="Add a staff account" description="Passwords need 12+ characters. Published defaults like admin or moderator are refused." />
      <form onSubmit={submit} className="grid sm:grid-cols-2 gap-4">
        <Field label="Username">
          {(props) => (
            <input {...props} className={INPUT} value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" required />
          )}
        </Field>
        <Field label="Display name" optional="optional">
          {(props) => (
            <input {...props} className={INPUT} value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
          )}
        </Field>
        <Field label="Password" hint="Shown once here; store it safely.">
          {(props) => (
            <input
              {...props}
              type="password"
              className={INPUT}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="new-password"
              required
              minLength={12}
            />
          )}
        </Field>
        <Field label="Team / agency">
          {(props) => (
            <input {...props} className={INPUT} value={team} onChange={(e) => setTeam(e.target.value)} />
          )}
        </Field>
        <Field label="Role" className="sm:col-span-2">
          {(props) => (
            <select {...props} className={INPUT} value={role} onChange={(e) => setRole(e.target.value)}>
              {ROLES.map((r) => (
                <option key={r} value={r}>
                  {ROLE_LABEL[r]}
                </option>
              ))}
            </select>
          )}
        </Field>
        {error && (
          <div className="sm:col-span-2">
            <Alert tone="error">{error}</Alert>
          </div>
        )}
        <div className="sm:col-span-2 flex flex-wrap gap-2">
          <Button type="submit" loading={busy}>
            Create account
          </Button>
          <Button type="button" variant="ghost" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
        </div>
      </form>
    </Card>
  )
}

function AccountRow({ account, self, onChanged }) {
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const [password, setPassword] = useState('')

  const run = async (fn, ok) => {
    setBusy(true)
    try {
      onChanged(await fn())
      toast(ok)
    } catch (err) {
      toast(errorText(err), { tone: 'error' })
    } finally {
      setBusy(false)
    }
  }

  return (
    <tr className={cn('border-b border-line last:border-0', !account.enabled && 'opacity-55')}>
      <td className="px-5 py-3 align-top">
        <p className="font-medium font-mono text-sm">{account.username}</p>
        <p className="text-xs text-muted">{account.displayName}</p>
        {self && <p className="text-[11px] text-brand mt-0.5">You</p>}
      </td>
      <td className="px-5 py-3 align-top">
        <select
          className={cn(INPUT, 'h-9 py-0 text-xs')}
          value={account.role}
          disabled={busy}
          onChange={(e) => run(() => updateAdminAccount(account.username, { role: e.target.value }), 'Role updated')}
        >
          {ROLES.map((r) => (
            <option key={r} value={r}>
              {ROLE_LABEL[r]}
            </option>
          ))}
        </select>
        <p className="text-[11px] text-subtle mt-1">{account.team}</p>
      </td>
      <td className="px-5 py-3 align-top text-xs">
        {account.enabled ? (
          <span className="text-emerald-300">Active</span>
        ) : (
          <span className="text-rose-300">Disabled</span>
        )}
      </td>
      <td className="px-5 py-3 align-top">
        <div className="flex flex-col gap-2 min-w-[12rem]">
          <div className="flex gap-1.5">
            <input
              type="password"
              className={cn(INPUT, 'h-9 py-0 text-xs')}
              placeholder="New password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="new-password"
            />
            <Button
              size="sm"
              variant="secondary"
              disabled={busy || password.length < 12}
              loading={busy}
              onClick={() =>
                run(async () => {
                  const next = await setAdminAccountPassword(account.username, password)
                  setPassword('')
                  return next
                }, 'Password updated')
              }
            >
              Set
            </Button>
          </div>
          <Button
            size="sm"
            variant={account.enabled ? 'danger' : 'success'}
            disabled={busy || self}
            loading={busy}
            onClick={() =>
              run(
                () => updateAdminAccount(account.username, { enabled: !account.enabled }),
                account.enabled ? 'Account disabled' : 'Account re-enabled',
              )
            }
          >
            {account.enabled ? 'Disable' : 'Enable'}
          </Button>
        </div>
      </td>
    </tr>
  )
}

export default function Admin() {
  const { isAdmin, isAuthenticated, user, loading: authLoading } = useAuth()
  const toast = useToast()
  const [overview, setOverview] = useState(null)
  const [accounts, setAccounts] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)

  const reload = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [ov, list] = await Promise.all([fetchAdminOverview(), fetchAdminAccounts()])
      setOverview(ov)
      setAccounts(list)
    } catch (err) {
      setError(errorText(err))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (isAdmin) reload()
  }, [isAdmin, reload])

  if (authLoading) return null
  if (!isAuthenticated) return <Navigate to="/login" replace state={{ from: '/admin' }} />
  if (!isAdmin) {
    return (
      <EmptyState
        icon={ShieldCheck}
        title="Owner admin only"
        description="This console is for the ADMIN account. Sign in as the owner, or ask them to promote you."
      >
        <Link to="/queue" className={buttonClass('secondary')}>
          Back to the queue
        </Link>
      </EmptyState>
    )
  }

  const self = user?.username

  return (
    <div>
      <PageHeader
        eyebrow="Owner"
        title="Admin"
        description="You alone manage staff accounts and see the system overview. Public visitors still cannot approve uploads — only accounts you create can."
        actions={
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" icon={RefreshCw} loading={loading} onClick={reload}>
              Refresh
            </Button>
            <Button icon={Plus} onClick={() => setCreating(true)} disabled={creating}>
              Add account
            </Button>
          </div>
        }
      />

      {error && (
        <div className="mb-6">
          <Alert tone="error">{error}</Alert>
        </div>
      )}

      <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
        <StatTile icon={Users} label="Staff accounts" value={overview?.accounts?.staff ?? '—'} loading={loading} />
        <StatTile icon={UserCog} label="Owner admins" value={overview?.accounts?.admins ?? '—'} loading={loading} accent="text-brand" />
        <StatTile icon={KeyRound} label="API clients" value={overview?.apiClients ?? '—'} loading={loading} />
        <StatTile
          icon={ShieldCheck}
          label="Analyses kept"
          value={overview?.analyses != null ? String(overview.analyses) : '—'}
          hint="Hashes and scores only — never the image"
          loading={loading}
        />
      </div>

      <div className="mb-8">
        <Alert tone="info" title="Solo operator mode">
          As ADMIN you can approve legal requests you filed, decide appeals you flagged, and complete second review alone.
          Those overrides are written into the custody / audit trail. Prefer a second senior account when you can.
        </Alert>
      </div>

      {creating && (
        <CreateAccountForm
          onCancel={() => setCreating(false)}
          onCreated={(created) => {
            setCreating(false)
            setAccounts((prev) =>
              [...prev.filter((a) => a.username !== created.username), created].sort(
                (a, b) => a.team.localeCompare(b.team) || a.username.localeCompare(b.username),
              ),
            )
            toast(`Created ${created.username}`)
            reload()
          }}
        />
      )}

      <Card className="overflow-x-auto p-0">
        <div className="px-5 pt-5 pb-3">
          <CardHeader
            icon={Users}
            title="Staff directory"
            description="Disable an account to revoke console access without deleting history."
          />
        </div>
        {accounts.length === 0 && !loading ? (
          <div className="px-5 pb-6">
            <EmptyState icon={Users} title="No accounts yet" description="Create your first moderator or investigator." />
          </div>
        ) : (
          <table className="data-table w-full min-w-[720px] text-sm">
            <thead>
              <tr className="border-y border-line text-left">
                <th className="px-5 py-3">Account</th>
                <th className="px-5 py-3">Role</th>
                <th className="px-5 py-3">Status</th>
                <th className="px-5 py-3">Actions</th>
              </tr>
            </thead>
            <tbody>
              {accounts.map((a) => (
                <AccountRow
                  key={a.username}
                  account={a}
                  self={a.username === self}
                  onChanged={(next) => setAccounts((prev) => prev.map((x) => (x.username === next.username ? next : x)))}
                />
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <div className="mt-8 flex flex-wrap gap-3">
        <Link to="/api-access" className={buttonClass('secondary')}>
          <KeyRound aria-hidden className="size-4" />
          Partner API keys
        </Link>
        <Link to="/queue" className={buttonClass('ghost')}>
          Open the queue
        </Link>
      </div>
    </div>
  )
}
