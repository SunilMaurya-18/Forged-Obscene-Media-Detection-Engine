import {
  BellRing,
  CheckCircle2,
  FileWarning,
  Gavel,
  Repeat2,
  ScanSearch,
  ShieldAlert,
  ShieldCheck,
  ShieldX,
} from 'lucide-react'

export const MANIFEST_STATUS = {
  VERIFIED: {
    label: 'Verified',
    description: 'Signature is valid and the pixels are unchanged since labelling.',
    icon: ShieldCheck,
    text: 'text-emerald-300',
    ring: 'border-emerald-400/30 bg-emerald-400/10',
  },
  CONTENT_MODIFIED: {
    label: 'Content modified',
    description: 'The manifest is genuine, but the image was edited after it was labelled.',
    icon: ShieldAlert,
    text: 'text-amber-300',
    ring: 'border-amber-400/30 bg-amber-400/10',
  },
  INVALID_SIGNATURE: {
    label: 'Invalid signature',
    description: 'The manifest was forged or edited. Do not trust the values it claims.',
    icon: ShieldX,
    text: 'text-rose-300',
    ring: 'border-rose-400/30 bg-rose-400/10',
  },
}

export const MATCH_VIA = {
  MANIFEST: 'Embedded provenance manifest',
  CONTENT_HASH: 'Identical pixels (metadata was stripped)',
  PHASH: 'Perceptual-hash match (visually the same)',
  NONE: 'No match',
}

export const EVENT_META = {
  ANALYZED: { label: 'Analysed and labelled', icon: ScanSearch, tone: 'text-brand' },
  REUPLOAD_DETECTED: { label: 'Re-upload detected', icon: Repeat2, tone: 'text-sky-300' },
  DECISION_CHANGED: { label: 'Moderator decision', icon: Gavel, tone: 'text-violet-300' },
  COMPLAINT_OPENED: { label: 'Takedown complaint', icon: FileWarning, tone: 'text-amber-300' },
  SLA_ESCALATED: { label: 'SLA escalation', icon: BellRing, tone: 'text-rose-300' },
  SLA_RESOLVED: { label: 'Complaint resolved', icon: CheckCircle2, tone: 'text-emerald-300' },
}

export function manifestStatus(status) {
  return MANIFEST_STATUS[status] || MANIFEST_STATUS.INVALID_SIGNATURE
}

export function shortId(id) {
  return id ? `${id.slice(0, 8)}…${id.slice(-4)}` : '—'
}

const EXT = { 'image/jpeg': 'jpg', 'image/png': 'png' }

export function labelledFilename(original, mimeType) {
  const base = (original || 'image').replace(/\.[^.]+$/, '')
  return `${base}.labelled.${EXT[mimeType] || 'img'}`
}

export function downloadBase64(base64, mimeType, filename) {
  const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))
  const url = URL.createObjectURL(new Blob([bytes], { type: mimeType }))
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
