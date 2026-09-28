/** Mirrors the backend's NoticeService / CaseNumbers so the appeal form can catch mistakes before sending. */
const NOTICE_CHARS = /^[2-9A-HJKMNP-Z]{8}$/

export const STATEMENT_MAX = 1000

/** "upl 7k3q9xpa", "7K3Q-9XPA" and "UPL-7K3Q-9XPA" all become "UPL-7K3Q-9XPA"; anything else is null. */
export function normalizeNoticeNumber(raw) {
  if (!raw) return null
  let s = String(raw).toUpperCase().replace(/[^0-9A-Z]/g, '')
  if (s.startsWith('UPL')) s = s.slice(3)
  if (!NOTICE_CHARS.test(s)) return null
  return `UPL-${s.slice(0, 4)}-${s.slice(4)}`
}

/** 'OPEN' | 'GRANTED_PENDING' | 'GRANTED' | 'UPHELD' | null: what the uploader should be told. */
export function appealStage(appeal) {
  if (!appeal) return null
  if (appeal.status === 'GRANTED') return appeal.reinstated ? 'GRANTED' : 'GRANTED_PENDING'
  return appeal.status
}

/** Period presets for the transparency report (the backend also accepts YYYY-MM, YYYY-Qn, YYYY). */
export function periodOptions(now = new Date()) {
  const y = now.getUTCFullYear()
  const m = now.getUTCMonth()
  const q = Math.floor(m / 3) + 1
  const pad = (n) => String(n).padStart(2, '0')
  const prev = new Date(Date.UTC(y, m - 1, 1))
  return [
    { value: 'last-30d', key: 'last30', label: 'Last 30 days' },
    { value: `${y}-${pad(m + 1)}`, key: 'thisMonth', label: 'This month' },
    { value: `${prev.getUTCFullYear()}-${pad(prev.getUTCMonth() + 1)}`, key: 'lastMonth', label: 'Last month' },
    { value: `${y}-Q${q}`, key: 'thisQuarter', label: 'This quarter' },
    { value: String(y), key: 'thisYear', label: 'This year' },
  ]
}

/** 0.873 -> "87.3%"; null (nothing to measure, or withheld) -> "n/a". */
export function pct(rate) {
  if (rate == null || Number.isNaN(rate)) return 'n/a'
  return `${(rate * 100).toFixed(rate === 0 || rate === 1 ? 0 : 1)}%`
}

/** A transparency count; null means the backend withheld a small count (1 to minCell - 1). */
export function cell(n, minCell) {
  return n == null ? `<${minCell ?? 5}` : String(n)
}

/** Share of the exposure limit used, and whether to warn (last 20%). */
export function exposureMeter(w) {
  if (!w || !w.limit) return { ratio: 0, warn: false }
  const ratio = Math.min(1, w.exposures / w.limit)
  return { ratio, warn: !w.onBreak && ratio >= 0.8 }
}
