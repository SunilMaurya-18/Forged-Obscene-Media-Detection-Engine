import { normalizeInvestigationNumber } from './investigations.js'

/** Mirrors LegalRequestService: the limits the server enforces, checked before sending. */
export const MAX_CASES = 25
export const MAX_TERM_DAYS = 180
export const AUTHORITY_MAX = 160
export const REFERENCE_MAX = 128

/** EXPIRED is derived by the server from expiresAt; the others are stored. */
export const REQUEST_STATUS = {
  PENDING: { label: 'Awaiting approval', tone: 'amber', hint: 'A senior moderator other than the one who filed it has to approve it' },
  APPROVED: { label: 'Approved', tone: 'emerald', hint: 'The investigator can read the named cases until it expires' },
  REJECTED: { label: 'Rejected', tone: 'rose', hint: 'Never granted access' },
  REVOKED: { label: 'Revoked', tone: 'rose', hint: 'Access ended before the expiry' },
  EXPIRED: { label: 'Expired', tone: 'slate', hint: 'Access ended at the expiry' },
}

/**
 * Case numbers typed or pasted in any form ("inv 7k3q9xpa", "7K3Q-9XPA", one per line or comma
 * separated), normalised and de-duplicated; {@code invalid} lists what couldn't be read.
 */
export function parseCaseNumbers(raw) {
  const cases = []
  const invalid = []
  const tokens = String(raw ?? '').split(/[\s,;]+/).filter(Boolean)
  for (let i = 0; i < tokens.length; i++) {
    let token = tokens[i]
    if (/^inv-?$/i.test(token) && i + 1 < tokens.length) token += tokens[++i]
    const n = normalizeInvestigationNumber(token)
    if (!n) invalid.push(token)
    else if (!cases.includes(n)) cases.push(n)
  }
  return { cases, invalid }
}

function pad(n) {
  return String(n).padStart(2, '0')
}

/** yyyy-mm-dd (local) {@code days} from {@code now}, for a date input's value. */
export function dateInputValue(now = new Date(), days = 30) {
  const d = new Date(now.getTime() + days * 86400000)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** The end of that local day as an ISO instant: an order "valid until 12 March" covers all of 12 March. */
export function expiryInstant(dateValue) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateValue || '')
  if (!m) return null
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), 23, 59, 59)
  return Number.isNaN(d.getTime()) ? null : d.toISOString()
}

/** Why the server would refuse this expiry, or null. */
export function expiryProblem(dateValue, now = new Date(), maxDays = MAX_TERM_DAYS) {
  const iso = expiryInstant(dateValue)
  if (!iso) return 'Choose the date the order stops being valid.'
  const t = new Date(iso).getTime()
  if (t <= now.getTime() + 3600000) return 'The expiry must be more than an hour from now.'
  if (t > now.getTime() + maxDays * 86400000) return `Requests last at most ${maxDays} days; file a new one to extend.`
  return null
}

/** Why the filing form can't be sent yet, or null. */
export function filingProblem({ authority, orderReference, investigator, caseText, expiry }, now = new Date()) {
  if (!String(authority ?? '').trim()) return 'Name the issuing authority.'
  if (String(authority).trim().length > AUTHORITY_MAX) return `The authority is at most ${AUTHORITY_MAX} characters.`
  if (!String(orderReference ?? '').trim()) return "Give the order's reference number."
  if (String(orderReference).trim().length > REFERENCE_MAX) return `The reference is at most ${REFERENCE_MAX} characters.`
  if (!investigator) return 'Choose the investigator the order names.'
  const { cases, invalid } = parseCaseNumbers(caseText)
  if (invalid.length) return `Not a case number: ${invalid[0]}`
  if (!cases.length) return 'Name at least one case.'
  if (cases.length > MAX_CASES) return `A request names at most ${MAX_CASES} cases.`
  return expiryProblem(expiry, now)
}

/** "3 days left", "5 hours left", "expired"; for access and download windows. */
export function timeLeft(until, now = new Date()) {
  const ms = new Date(until).getTime() - now.getTime()
  if (!(ms > 0)) return 'expired'
  const hours = Math.floor(ms / 3600000)
  if (hours >= 48) return `${Math.floor(hours / 24)} days left`
  if (hours >= 1) return `${hours} hour${hours === 1 ? '' : 's'} left`
  const minutes = Math.max(1, Math.floor(ms / 60000))
  return `${minutes} minute${minutes === 1 ? '' : 's'} left`
}

/** Requests grouped for the moderator screen: what needs a decision first, then active, then the rest. */
export function groupRequests(requests) {
  const out = { pending: [], active: [], closed: [] }
  for (const r of requests || []) {
    if (r.status === 'PENDING') out.pending.push(r)
    else if (r.status === 'APPROVED') out.active.push(r)
    else out.closed.push(r)
  }
  return out
}

/** A package can be downloaded while its window is open; the server re-checks the request too. */
export function packageDownloadable(pkg, now = new Date()) {
  return Boolean(pkg) && new Date(pkg.downloadExpiresAt).getTime() > now.getTime()
}