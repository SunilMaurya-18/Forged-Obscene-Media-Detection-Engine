/** Mirrors the backend's SightingService and CaseNumbers so the forms can catch mistakes before sending. */
const CASE_CHARS = /^[2-9A-HJKMNP-Z]{8}$/
const PLATFORM = /^[a-z0-9][a-z0-9.-]{0,47}$/
const POST_REF = /^[A-Za-z0-9._:-]{1,128}$/

export const ACCOUNT_ID_MAX = 256
export const NOTE_MAX = 512

/** "inv 7k3q9xpa", "7K3Q-9XPA" and "INV-7K3Q-9XPA" all become "INV-7K3Q-9XPA"; anything else is null. */
export function normalizeInvestigationNumber(raw) {
  if (!raw) return null
  let s = String(raw).toUpperCase().replace(/[^0-9A-Z]/g, '')
  if (s.startsWith('INV')) s = s.slice(3)
  if (!CASE_CHARS.test(s)) return null
  return `INV-${s.slice(0, 4)}-${s.slice(4)}`
}

/** "Pixel Gram" becomes "pixel-gram", as the server stores it; null if it can't be a platform name. */
export function normalizePlatform(raw) {
  const p = String(raw ?? '')
    .trim()
    .toLowerCase()
    .replace(/[\s_]+/g, '-')
  return PLATFORM.test(p) ? p : null
}

/** Why a post ID would be refused, or null if it is fine. Only the platform's own opaque ID is accepted. */
export function postRefProblem(raw) {
  const p = String(raw ?? '').trim()
  if (!p) return 'Enter the post ID.'
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(p) || p.includes('/')) return "Use the platform's post ID, not a link."
  if (p.includes('@')) return "Use the platform's post ID, not a username or e-mail address."
  if (/\s/.test(p)) return 'Post IDs have no spaces.'
  if (!POST_REF.test(p)) return "Post IDs are up to 128 letters, digits, '.', '_', ':' or '-'."
  return null
}

export const VIA = {
  PHASH: { label: 'Perceptual hash', hint: 'A re-encoded or resized copy within the match threshold' },
  PROVENANCE: { label: 'Provenance ID', hint: "Carried the provenance ID from this platform's labelled copy" },
  LINEAGE: { label: 'Re-upload', hint: "Traced to a case item through the provenance trail" },
}

export const SOURCE = {
  PARTNER_API: 'Partner API',
  MODERATOR: 'Moderator',
}

/** "distance 0" reads as an identical hash, anything else as bits out of 64. */
export function matchLabel(via, distance) {
  const base = VIA[via]?.label || via
  if (via !== 'PHASH' || distance == null) return base
  return distance === 0 ? `${base} · identical` : `${base} · ${distance}/64 bits differ`
}

export const TIMELINE_KINDS = {
  case: 'Case log',
  provenance: 'Provenance',
  sighting: 'Sighting',
  appearance: 'Upload here',
}

const EVENT_LABELS = {
  OPENED: 'Case opened',
  ITEM_ADDED: 'Evidence added',
  SIGHTING_LINKED: 'Sighting linked',
  NOTE_ADDED: 'Note',
  CLOSED: 'Case closed',
  REOPENED: 'Case reopened',
  OBSERVED: 'Seen on a platform',
  UPLOADED: 'Uploaded here again',
}

/** Case and sighting events get plain names; provenance events keep theirs, made readable. */
export function eventLabel(entry) {
  if (EVENT_LABELS[entry.type]) return EVENT_LABELS[entry.type]
  const t = String(entry.type || '').toLowerCase().replace(/_/g, ' ')
  return t.charAt(0).toUpperCase() + t.slice(1)
}

/** Newest first, grouped by calendar day (local time) for the timeline view. */
export function groupByDay(entries, locale = 'en-IN') {
  const fmt = new Intl.DateTimeFormat(locale, { dateStyle: 'medium' })
  const groups = []
  const sorted = [...(entries || [])].sort((a, b) => new Date(b.at) - new Date(a.at))
  for (const e of sorted) {
    const day = fmt.format(new Date(e.at))
    const last = groups[groups.length - 1]
    if (last && last.day === day) last.entries.push(e)
    else groups.push({ day, entries: [e] })
  }
  return groups
}
