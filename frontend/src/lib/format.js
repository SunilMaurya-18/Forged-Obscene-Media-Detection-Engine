export function cn(...classes) {
  return classes.filter(Boolean).join(' ')
}

export function pct(value, digits = 1) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return `${(Number(value) * 100).toFixed(digits)}%`
}

export function score(value, digits = 3) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return Number(value).toFixed(digits)
}

export function fileSize(bytes) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / 1024 ** i).toFixed(i ? 1 : 0)} ${units[i]}`
}

const RTF = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })
const STEPS = [
  ['year', 31536000],
  ['month', 2592000],
  ['week', 604800],
  ['day', 86400],
  ['hour', 3600],
  ['minute', 60],
]

export function timeAgo(iso) {
  if (!iso) return ''
  const seconds = (new Date(iso).getTime() - Date.now()) / 1000
  for (const [unit, size] of STEPS) {
    if (Math.abs(seconds) >= size) return RTF.format(Math.round(seconds / size), unit)
  }
  return 'just now'
}

export function dateTime(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

/** Sibling demo app runs on :3001 on the same host. */
export function mockFeedUrl() {
  return `${window.location.protocol}//${window.location.hostname}:3001`
}
