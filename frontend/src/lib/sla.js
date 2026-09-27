import { AlarmClock, CircleCheck, Siren } from 'lucide-react'

export const SLA_STATUS = {
  ON_TRACK: {
    label: 'On track',
    icon: CircleCheck,
    text: 'text-emerald-300',
    bar: 'bg-emerald-400',
    ring: 'border-emerald-400/25 bg-emerald-400/[0.07]',
  },
  AT_RISK: {
    label: 'At risk',
    icon: AlarmClock,
    text: 'text-amber-300',
    bar: 'bg-amber-400',
    ring: 'border-amber-400/30 bg-amber-400/[0.08]',
  },
  BREACHED: {
    label: 'Breached',
    icon: Siren,
    text: 'text-rose-300',
    bar: 'bg-rose-400',
    ring: 'border-rose-400/30 bg-rose-400/[0.08]',
  },
}

export const SLA_ORDER = ['ON_TRACK', 'AT_RISK', 'BREACHED']

export const COMPLAINT_TYPES = {
  USER_COMPLAINT: { label: 'User complaint', detail: 'Non-consensual / deepfake imagery reported by a user' },
  GOVERNMENT_ORDER: { label: 'Government order', detail: 'Takedown order issued by a government agency' },
}

export function liveStatus(remainingSeconds, atRiskMinutes) {
  if (remainingSeconds <= 0) return 'BREACHED'
  if (remainingSeconds <= atRiskMinutes * 60) return 'AT_RISK'
  return 'ON_TRACK'
}

/** 5025 -> "01:23:45" (sign dropped; callers say "remaining" or "overdue"). */
export function countdown(seconds) {
  const s = Math.abs(Math.trunc(seconds))
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(Math.floor(s / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`
}

export function humanDuration(seconds) {
  const s = Math.abs(Math.trunc(seconds))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  if (h) return m ? `${h}h ${m}m` : `${h}h`
  if (m) return `${m}m`
  return `${s}s`
}
