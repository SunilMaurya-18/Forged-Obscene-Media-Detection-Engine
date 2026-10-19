import { ShieldAlert, ShieldCheck, ShieldX } from 'lucide-react'
import { DEFAULT_THRESHOLDS } from './decision'

/**
 * Plain-language verdicts, worded to match the glossary one-pager. Each has a distinct icon and label,
 * so the traffic-light colour is never the only cue.
 */
export const VERDICTS = {
  LIKELY_FORGED: {
    icon: ShieldX,
    text: 'text-rose-300',
    ring: 'border-rose-400/50 bg-rose-400/15',
    glow: 'from-rose-400/30',
    light: 'bg-rose-400',
  },
  NEEDS_REVIEW: {
    icon: ShieldAlert,
    text: 'text-amber-300',
    ring: 'border-amber-400/40 bg-amber-400/[0.1]',
    glow: 'from-amber-400/20',
    light: 'bg-amber-400',
  },
  LOOKS_GENUINE: {
    icon: ShieldCheck,
    text: 'text-emerald-300',
    ring: 'border-emerald-400/50 bg-emerald-400/15',
    glow: 'from-emerald-400/30',
    light: 'bg-emerald-400',
  },
}

/** Same bands as the backend decision engine, applied to the forgery score only. */
export function verdictFor(forgeryScore, thresholds = DEFAULT_THRESHOLDS) {
  const f = Number(forgeryScore) || 0
  if (f >= thresholds.forgeryAutoFlag) return 'LIKELY_FORGED'
  if (f >= thresholds.forgeryReview) return 'NEEDS_REVIEW'
  return 'LOOKS_GENUINE'
}
