/** Mirrors CaseService on the backend so the forms can catch mistakes before sending. */
const CASE_CHARS = /^[2-9A-HJKMNP-Z]{8}$/
const EMAIL = /^[^@\s]{1,64}@[^@\s]+\.[^@\s]{2,}$/
const PHONE = /^\+?[0-9]{8,15}$/

export const DETAILS_MAX = 1000
export const REGIONS = [
  { code: 'IN', key: 'IN' },
  { code: 'US', key: 'US' },
  { code: 'GENERIC', key: 'OTHER' },
]

/** "fmd 7k3q9xpa", "7K3Q-9XPA" and "FMD-7K3Q-9XPA" all become "FMD-7K3Q-9XPA"; anything else is null. */
export function normalizeCaseNumber(raw) {
  if (!raw) return null
  let s = String(raw).toUpperCase().replace(/[^0-9A-Z]/g, '')
  if (s.startsWith('FMD')) s = s.slice(3)
  if (!CASE_CHARS.test(s)) return null
  return `FMD-${s.slice(0, 4)}-${s.slice(4)}`
}

export function isValidPin(pin) {
  return /^\d{6}$/.test(String(pin ?? ''))
}

/** Returns 'EMAIL' / 'SMS' for a usable contact, '' for none, or null if it can't be used. */
export function contactChannel(raw) {
  const c = String(raw ?? '').trim()
  if (!c) return ''
  if (c.length <= 254 && EMAIL.test(c)) return 'EMAIL'
  if (PHONE.test(c.replace(/[\s()-]/g, ''))) return 'SMS'
  return null
}

/** The wizard's two free-text fields travel as one {@code details} string, within the backend limit. */
export function composeDetails(where, notes) {
  const parts = []
  if (where?.trim()) parts.push(`Seen at: ${where.trim()}`)
  if (notes?.trim()) parts.push(notes.trim())
  return parts.join('\n\n')
}

export function formatWhen(iso, lang) {
  if (!iso) return ''
  const locale = lang === 'hi' ? 'hi-IN' : 'en-IN'
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso))
}
