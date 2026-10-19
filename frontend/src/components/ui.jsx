import { useId, useState } from 'react'
import { AlertTriangle, Check, CheckCircle2, Copy, Info, Loader2 } from 'lucide-react'
import { decisionMeta, scoreBand } from '../lib/decision'
import { cn, score as fmtScore } from '../lib/format'

const BUTTON_VARIANTS = {
  primary: 'btn-chrome font-semibold',
  secondary: 'btn-steel',
  ghost: 'text-muted hover:text-fg hover:bg-white/[0.05]',
  success:
    'border border-emerald-400/45 bg-emerald-400/15 text-emerald-200 hover:bg-emerald-400/25 hover:border-emerald-400/60',
  danger: 'border border-rose-400/45 bg-rose-400/15 text-rose-200 hover:bg-rose-400/25 hover:border-rose-400/60',
}

const BUTTON_SIZES = {
  sm: 'h-8 px-3 text-xs gap-1.5 rounded-lg',
  md: 'h-10 px-4 text-sm gap-2 rounded-lg',
  lg: 'h-11 px-5 text-[15px] gap-2 rounded-lg',
}

export function buttonClass(variant = 'primary', size = 'md', className = '') {
  return cn(
    'inline-flex items-center justify-center font-medium whitespace-nowrap transition-all duration-150',
    'disabled:opacity-50 disabled:pointer-events-none active:translate-y-px',
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

/** Class for text inputs, selects and textareas; extra classes (padding, height, font) still win. */
export const INPUT = 'field'

/** Label, control and an optional hint or error, wired together for screen readers. */
export function Field({ label, hint, error, optional, className, children }) {
  const id = useId()
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined
  return (
    <div className={className}>
      <label htmlFor={id} className="flex items-baseline justify-between gap-2 mb-1.5 text-[13px] font-medium text-fg/90">
        {label}
        {optional && <span className="text-[11px] font-normal text-subtle">{optional}</span>}
      </label>
      {children({ id, 'aria-describedby': describedBy, 'aria-invalid': error ? true : undefined })}
      {error ? (
        <p id={`${id}-error`} className="mt-1.5 text-xs text-rose-300">
          {error}
        </p>
      ) : (
        hint && (
          <p id={`${id}-hint`} className="mt-1.5 text-xs text-subtle leading-relaxed">
            {hint}
          </p>
        )
      )}
    </div>
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
    <div className="flex items-start justify-between gap-5 mb-6 print:mb-2">
      <div className="flex items-start gap-3.5 min-w-0">
        {Icon && (
          <span className="grid place-items-center size-9 shrink-0 rounded-lg border border-line bg-surface-2 text-brand print:hidden">
            <Icon className="size-4" />
          </span>
        )}
        <div className="min-w-0 space-y-1">
          <h2 className="text-[16px] font-semibold tracking-tight leading-snug print:text-[12.5px] print:leading-normal">{title}</h2>
          {description && <p className="text-[14px] text-muted leading-[1.65] print:hidden">{description}</p>}
        </div>
      </div>
      {action}
    </div>
  )
}

export function PageHeader({ eyebrow, title, description, actions }) {
  return (
    <header className="flex flex-col md:flex-row md:items-end md:justify-between gap-6 mb-10 pb-7 border-b border-line animate-fade-up print:mb-3 print:pb-2">
      <div className="max-w-2xl print:max-w-none">
        {eyebrow && (
          <p className="label-caps text-brand inline-flex items-center gap-2 mb-3 print:mb-1 print:text-[9px]">
            <span aria-hidden className="size-1.5 rounded-full bg-brand" />
            {eyebrow}
          </p>
        )}
        <h1 className="font-serif text-[30px] md:text-[38px] leading-[1.2] font-medium tracking-[-0.012em] print:text-[22px]">{title}</h1>
        {description && (
          <p className="text-muted mt-3.5 text-[15.5px] leading-[1.7] print:mt-1 print:text-[11px]">{description}</p>
        )}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2.5 md:shrink-0 print:hidden">{actions}</div>}
    </header>
  )
}

const BADGE_TONES = {
  neutral: 'border-line-strong bg-white/[0.04] text-muted',
  brand: 'border-brand/30 bg-brand/10 text-brand',
  danger: 'border-rose-400/45 bg-rose-400/15 text-rose-200',
  warning: 'border-amber-400/35 bg-amber-400/[0.1] text-amber-200',
  success: 'border-emerald-400/45 bg-emerald-400/15 text-emerald-200',
  info: 'border-sky-400/30 bg-sky-400/[0.08] text-sky-200',
}

export function Badge({ tone = 'neutral', icon: Icon, className, children }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-md border px-1.5 py-0.5 text-[11px] font-medium leading-4 whitespace-nowrap',
        BADGE_TONES[tone],
        className,
      )}
    >
      {Icon && <Icon aria-hidden className="size-3" />}
      {children}
    </span>
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
      <div className="flex items-start justify-between gap-3">
        <span className="text-[13px] font-medium text-muted leading-8">{label}</span>
        {Icon && (
          <span className="grid place-items-center size-8 shrink-0 rounded-lg border border-line bg-surface-2">
            <Icon aria-hidden className={cn('size-4', accent)} />
          </span>
        )}
      </div>
      {loading ? (
        <div className="skeleton h-8 w-24 mt-2" />
      ) : (
        <p className="mt-2 text-[28px] leading-none font-semibold tracking-tight tabular-nums">{value}</p>
      )}
      {hint && <p className="text-xs text-subtle mt-2.5 leading-relaxed">{hint}</p>}
    </Card>
  )
}

const ALERT_TONES = {
  error: { cls: 'border-rose-400/40 bg-rose-400/12 text-rose-200', icon: AlertTriangle },
  warning: { cls: 'border-amber-400/35 bg-amber-400/[0.1] text-amber-100', icon: AlertTriangle },
  info: { cls: 'border-line-strong bg-white/[0.03] text-fg', icon: Info },
  success: { cls: 'border-emerald-400/40 bg-emerald-400/12 text-emerald-100', icon: CheckCircle2 },
}

export function Alert({ tone = 'error', title, children }) {
  const { cls, icon: Icon } = ALERT_TONES[tone] || ALERT_TONES.error
  return (
    <div role="alert" className={cn('flex gap-3 rounded-xl border px-4 py-3 text-sm animate-fade-up', cls)}>
      <Icon className="size-4 mt-0.5 shrink-0 opacity-80" />
      <div>
        {title && <p className="font-medium mb-0.5">{title}</p>}
        <div className="opacity-90 leading-relaxed">{children}</div>
      </div>
    </div>
  )
}

export function EmptyState({ icon: Icon, title, description, children }) {
  return (
    <Card className="px-6 py-14 text-center border-dashed bg-transparent bg-none shadow-none">
      <div className="mx-auto mb-4 grid place-items-center size-12 rounded-xl border border-line-strong bg-surface-2 shadow-[0_0_0_6px_var(--color-surface)]">
        <Icon className="size-5 text-brand" />
      </div>
      <h3 className="text-base font-semibold tracking-tight">{title}</h3>
      {description && <p className="text-sm text-muted mt-1.5 max-w-md mx-auto leading-relaxed">{description}</p>}
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
    <kbd className="inline-flex items-center rounded-md border border-line-strong bg-surface-2 px-1.5 py-0.5 font-mono text-[10px] text-muted shadow-[inset_0_-1px_0_var(--color-line-strong)]">
      {children}
    </kbd>
  )
}
