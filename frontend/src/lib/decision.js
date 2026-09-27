import { ShieldAlert, ShieldCheck, ShieldX } from 'lucide-react'

export const DECISIONS = {
  ALLOW: {
    label: 'Allowed',
    short: 'Allow',
    icon: ShieldCheck,
    description: 'Both scores are below the review thresholds, so the post is published automatically.',
    text: 'text-emerald-300',
    dot: 'bg-emerald-400',
    bar: 'bg-emerald-400',
    ring: 'border-emerald-400/25 bg-emerald-400/[0.07]',
    glow: 'from-emerald-400/20',
  },
  REVIEW: {
    label: 'Needs review',
    short: 'Review',
    icon: ShieldAlert,
    description: 'A score is in the review band, so the post goes to the human moderation queue.',
    text: 'text-amber-300',
    dot: 'bg-amber-400',
    bar: 'bg-amber-400',
    ring: 'border-amber-400/25 bg-amber-400/[0.07]',
    glow: 'from-amber-400/20',
  },
  AUTO_FLAG: {
    label: 'Auto-flagged',
    short: 'Flag',
    icon: ShieldX,
    description: 'A score is above the auto-flag threshold, so the post is blocked without review.',
    text: 'text-rose-300',
    dot: 'bg-rose-400',
    bar: 'bg-rose-400',
    ring: 'border-rose-400/25 bg-rose-400/[0.07]',
    glow: 'from-rose-400/20',
  },
}

export const DECISION_ORDER = ['ALLOW', 'REVIEW', 'AUTO_FLAG']

export const DEFAULT_THRESHOLDS = {
  forgeryReview: 0.5,
  forgeryAutoFlag: 0.85,
  explicitReview: 0.5,
  explicitAutoFlag: 0.85,
}

export function decisionMeta(decision) {
  return DECISIONS[decision] || DECISIONS.ALLOW
}

/** Band a single score falls into, given its review / auto-flag thresholds. */
export function scoreBand(value, review, autoFlag) {
  if (value >= autoFlag) return 'AUTO_FLAG'
  if (value >= review) return 'REVIEW'
  return 'ALLOW'
}
