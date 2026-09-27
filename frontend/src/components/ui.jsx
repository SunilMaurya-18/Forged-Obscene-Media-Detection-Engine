import { useState } from 'react'
import { AlertTriangle, Check, Copy, Loader2 } from 'lucide-react'
import { decisionMeta, scoreBand } from '../lib/decision'
import { cn, score as fmtScore } from '../lib/format'

const BUTTON_VARIANTS = {
  primary:
    'bg-brand-gradient text-[#fff] font-semibold shadow-[0_8px_24px_-8px_rgb(220_38_38/0.55)] hover:brightness-110',
  secondary: 'border border-line-strong bg-white/[0.04] text-fg hover:bg-white/[0.08] hover:border-white/20',
  ghost: 'text-muted hover:text-fg hover:bg-white/[0.05]',
  success:
    'border border-emerald-400/30 bg-emerald-400/10 text-emerald-200 hover:bg-emerald-400/20 hover:border-emerald-400/50',
  danger: 'border border-rose-400/30 bg-rose-400/10 text-rose-200 hover:bg-rose-400/20 hover:border-rose-400/50',
}

const BUTTON_SIZES = {
  sm: 'h-8 px-3 text-xs gap-1.5 rounded-lg',
  md: 'h-10 px-4 text-sm gap-2 rounded-xl',
  lg: 'h-12 px-6 text-[15px] gap-2.5 rounded-xl',
}

export function buttonClass(variant = 'primary', size = 'md', className = '') {
  return cn(
    'inline-flex items-center justify-center font-medium whitespace-nowrap transition-all duration-200',
    'disabled:opacity-50 disabled:pointer-events-none active:scale-[0.98]',
    BUTTON_VARIANTS[variant],
    BUTTON_SIZES[size],
    className,
  )
}

export function Button({ variant, size, icon: Icon, loading, disabled, className, children, ...props }) {
  return (
    <button type="button" {...props} className={buttonClass(variant, size, className)} disabled={loading || disabled}>
      {loading ? <Loader2 className="size-4 animate-spin" /> : Icon && <Icon className="size-4" />}
      {children}
    </button>
  )
}

export function Card({ className, children, ...props }) {
  return (
    <div className={cn('card', className)} {...props}>
      {children}
    </div>
  )
}

export function CardHeader({ icon: Icon, title, description, action }) {
  return (
    <div className="flex items-start justify-between gap-4 mb-5 print:mb-2">
      <div className="flex items-start gap-3 min-w-0">
        {Icon && (
          <span className="grid place-items-center size-9 shrink-0 rounded-xl border border-line bg-white/[0.04] text-brand print:hidden">
            <Icon className="size-[18px]" />
          </span>
        )}
        <div className="min-w-0">
          <h2 className="text-[15px] font-semibold tracking-tight print:text-[12.5px]">{title}</h2>
          {description && <p className="text-sm text-muted mt-0.5 print:hidden">{description}</p>}
        </div>
      </div>
      {action}
    </div>
  )
}

export function PageHeader({ eyebrow, title, description, actions }) {
  return (
    <div className="flex flex-col md:flex-row md:items-end md:justify-between gap-5 mb-8 animate-fade-up print:mb-3">
      <div className="max-w-2xl print:max-w-none">
        {eyebrow && (
          <p className="inline-flex items-center gap-2 text-[11px] font-semibold uppercase tracking-[0.18em] text-brand mb-3 print:mb-1 print:text-[9px]">
            <span className="size-1.5 rounded-full bg-brand-gradient" />
            {eyebrow}
          </p>
        )}
        <h1 className="text-3xl md:text-[40px] md:leading-[1.1] font-semibold tracking-[-0.03em] text-gradient print:text-[22px]">
          {title}
        </h1>
        {description && <p className="text-muted mt-3 text-[15px] leading-relaxed print:mt-1 print:text-[11px]">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2 md:shrink-0">{actions}</div>}
    </div>
  )
}

export function DecisionBadge({ decision, size = 'md' }) {
  const meta = decisionMeta(decision)
  const Icon = meta.icon
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full border font-semibold tracking-wide',
        meta.ring,
        meta.text,
        size === 'sm' ? 'px-2 py-0.5 text-[11px]' : 'px-2.5 py-1 text-xs',
      )}
    >
      <Icon className={size === 'sm' ? 'size-3' : 'size-3.5'} />
      {meta.label}
    </span>
  )
}

/** Horizontal meter with review / auto-flag threshold markers. */
export function ScoreMeter({ label, value, review, autoFlag, icon: Icon, compact = false }) {
  const v = Math.max(0, Math.min(1, Number(value) || 0))
  const meta = decisionMeta(scoreBand(v, review, autoFlag))
  return (
    <div>
      <div className="flex items-baseline justify-between gap-3 mb-2">
        <span className={cn('flex items-center gap-2 text-muted', compact ? 'text-xs' : 'text-sm')}>
          {Icon && <Icon className="size-4" />}
          {label}
        </span>
        <span className={cn('font-mono font-semibold tabular-nums', meta.text, compact ? 'text-sm' : 'text-xl')}>
          {fmtScore(v)}
        </span>
      </div>
      <div className={cn('relative rounded-full bg-white/[0.06]', compact ? 'h-1.5' : 'h-2.5')}>
        <div
          className="absolute inset-y-0 rounded-full bg-amber-400/10"
          style={{ left: `${review * 100}%`, width: `${(autoFlag - review) * 100}%` }}
        />
        <div
          className="absolute inset-y-0 right-0 rounded-r-full bg-rose-400/10"
          style={{ left: `${autoFlag * 100}%` }}
        />
        <div
          className={cn('absolute inset-y-0 left-0 rounded-full transition-[width] duration-700 ease-out', meta.bar)}
          style={{ width: `${Math.max(v * 100, 1.5)}%` }}
        />
        {[review, autoFlag].map((t, i) => (
          <span
            key={i}
            className={cn('absolute -inset-y-1 w-px', i ? 'bg-rose-300/60' : 'bg-amber-300/60')}
            style={{ left: `${t * 100}%` }}
          />
        ))}
      </div>
      {!compact && (
        <div className="relative h-4 mt-1.5 text-[10px] font-mono text-subtle">
          <span className="absolute -translate-x-1/2" style={{ left: `${review * 100}%` }}>
            review {review}
          </span>
          <span className="absolute -translate-x-1/2" style={{ left: `${autoFlag * 100}%` }}>
            flag {autoFlag}
          </span>
        </div>
      )}
    </div>
  )
}

export function StatTile({ icon: Icon, label, value, hint, accent = 'text-brand', loading }) {
  return (
    <Card className="p-5 overflow-hidden">
      <div className="flex items-center justify-between mb-4">
        <span className="text-[13px] text-muted">{label}</span>
        {Icon && <Icon className={cn('size-4', accent)} />}
      </div>
      {loading ? (
        <div className="skeleton h-8 w-24" />
      ) : (
        <p className="text-[28px] leading-none font-semibold tracking-tight tabular-nums">{value}</p>
      )}
      {hint && <p className="text-xs text-subtle mt-2.5">{hint}</p>}
    </Card>
  )
}

export function Alert({ tone = 'error', title, children }) {
  const tones = {
    error: 'border-rose-400/25 bg-rose-400/[0.07] text-rose-200',
    warning: 'border-amber-400/25 bg-amber-400/[0.06] text-amber-100',
    info: 'border-brand/25 bg-brand/[0.06] text-indigo-100',
  }
  return (
    <div role="alert" className={cn('flex gap-3 rounded-xl border px-4 py-3 text-sm animate-fade-up', tones[tone])}>
      <AlertTriangle className="size-4 mt-0.5 shrink-0 opacity-80" />
      <div>
        {title && <p className="font-medium mb-0.5">{title}</p>}
        <div className="opacity-90 leading-relaxed">{children}</div>
      </div>
    </div>
  )
}

export function EmptyState({ icon: Icon, title, description, children }) {
  return (
    <Card className="px-6 py-16 text-center">
      <div className="relative mx-auto mb-5 size-14">
        <div className="absolute inset-0 rounded-2xl bg-brand-gradient opacity-20 blur-xl" />
        <div className="relative grid place-items-center size-14 rounded-2xl border border-line-strong bg-surface-2">
          <Icon className="size-6 text-brand" />
        </div>
      </div>
      <h3 className="text-lg font-semibold tracking-tight">{title}</h3>
      {description && <p className="text-sm text-muted mt-1.5 max-w-sm mx-auto">{description}</p>}
      {children && <div className="flex flex-wrap justify-center gap-2 mt-6">{children}</div>}
    </Card>
  )
}

export function CopyButton({ value, className }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard unavailable (non-secure context) */
    }
  }
  return (
    <button
      type="button"
      onClick={copy}
      title="Copy"
      aria-label="Copy to clipboard"
      className={cn('grid place-items-center size-7 rounded-lg text-muted hover:text-fg hover:bg-white/[0.06] transition', className)}
    >
      {copied ? <Check className="size-3.5 text-emerald-300" /> : <Copy className="size-3.5" />}
    </button>
  )
}

export function Kbd({ children }) {
  return (
    <kbd className="inline-flex items-center rounded-md border border-line-strong bg-white/[0.04] px-1.5 py-0.5 font-mono text-[10px] text-muted">
      {children}
    </kbd>
  )
}
