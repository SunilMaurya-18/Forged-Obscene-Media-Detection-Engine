import { BadgeCheck, BadgeX, ShieldQuestion, Sparkles } from 'lucide-react'
import { actionLabel, c2paStatus } from '../lib/media'
import { cn } from '../lib/format'

const TONE = {
  ok: { icon: BadgeCheck, cls: 'border-emerald-400/30 bg-emerald-400/[0.08] text-emerald-200' },
  warn: { icon: ShieldQuestion, cls: 'border-amber-400/30 bg-amber-400/[0.08] text-amber-200' },
  bad: { icon: BadgeX, cls: 'border-rose-400/30 bg-rose-400/[0.08] text-rose-200' },
}

export function C2paChip({ credentials, className }) {
  if (!credentials) return null
  const s = c2paStatus(credentials.status)
  const Icon = TONE[s.tone].icon
  return (
    <span
      title={`C2PA Content Credentials: ${s.label}${credentials.claim_generator ? ` · ${credentials.claim_generator}` : ''}`}
      className={cn('inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-medium', TONE[s.tone].cls, className)}
    >
      <Icon aria-hidden className="size-3" />
      C2PA · {s.label}
    </span>
  )
}

/** Phase 23: what the upload's C2PA manifest claims: signer, generator, edit actions. */
export default function ContentCredentials({ credentials }) {
  if (!credentials) {
    return (
      <p className="text-[13px] text-muted leading-relaxed">
        No C2PA Content Credentials were found. That is normal: most cameras and platforms don't write or keep them, so their absence is
        not evidence of editing.
      </p>
    )
  }
  const s = c2paStatus(credentials.status)
  const Icon = TONE[s.tone].icon
  const signer = credentials.signer || {}
  const actions = credentials.actions || []
  return (
    <div className="space-y-3">
      <div className={cn('flex items-start gap-3 rounded-xl border px-3.5 py-3', TONE[s.tone].cls)}>
        <Icon aria-hidden className="size-5 shrink-0 mt-0.5" />
        <div className="min-w-0">
          <p className="text-sm font-semibold">{s.label}</p>
          {s.description && <p className="text-xs opacity-90 mt-0.5">{s.description}</p>}
        </div>
      </div>
      <dl className="grid sm:grid-cols-2 gap-2 text-[13px]">
        {[
          ['Signed by', signer.common_name ? `${signer.common_name}${signer.issuer ? ` (issuer: ${signer.issuer})` : ''}` : '—'],
          ['Signed at', signer.signed_at || '—'],
          ['Claim generator', credentials.claim_generator || '—'],
          ['Manifests in chain', credentials.manifest_count ?? 1],
        ].map(([k, v]) => (
          <div key={k} className="rounded-lg border border-line bg-white/[0.02] px-3 py-2 min-w-0">
            <dt className="text-xs text-muted">{k}</dt>
            <dd className="truncate" title={String(v)}>
              {v}
            </dd>
          </div>
        ))}
      </dl>
      {credentials.ai_generated && (
        <p className="flex items-center gap-2 text-[13px] text-violet-200">
          <Sparkles aria-hidden className="size-4" /> The manifest itself declares AI-generated content (digital source type).
        </p>
      )}
      {actions.length > 0 && (
        <div>
          <p className="text-xs text-muted mb-1.5">Edit actions</p>
          <ol className="flex flex-wrap gap-1.5">
            {actions.map((a, i) => (
              <li key={i} className="rounded-full border border-line px-2.5 py-0.5 text-xs" title={a.software_agent || undefined}>
                {actionLabel(a.action)}
                {a.software_agent && <span className="text-subtle"> · {a.software_agent}</span>}
              </li>
            ))}
          </ol>
        </div>
      )}
      {(credentials.failures?.length > 0 || credentials.error) && (
        <p className="text-xs text-rose-200 font-mono break-all">
          {credentials.failures?.length ? credentials.failures.map((f) => f.code || f).join(', ') : credentials.error}
        </p>
      )}
      <p className="text-[11px] text-subtle">
        C2PA proves who signed which claims, not that the claims are true. Scores above are computed independently.
      </p>
    </div>
  )
}
