/** Phase 23: client-side checks that mirror the backend's MediaKinds and the ML service's limits. */
export const MAX_IMAGE_BYTES = 25 * 1024 * 1024
export const MAX_VIDEO_BYTES = 50 * 1024 * 1024
const VIDEO_EXT = /\.(mp4|m4v|mov|webm|mkv|avi)$/i

export function isVideoFile(file) {
  if (!file) return false
  return String(file.type || '').startsWith('video/') || VIDEO_EXT.test(file.name || '')
}

export function isImageFile(file) {
  return Boolean(file) && String(file.type || '').startsWith('image/')
}

/** Null when the file is acceptable, otherwise a plain-language reason. */
export function mediaProblem(file, fileSize = (n) => `${Math.round(n / 1024 / 1024)} MB`) {
  if (!file) return 'Choose a file.'
  const video = isVideoFile(file)
  if (!video && !isImageFile(file)) return 'That file is not an image or video. Choose a JPG, PNG, WEBP, MP4, WEBM or MOV.'
  const limit = video ? MAX_VIDEO_BYTES : MAX_IMAGE_BYTES
  if (file.size > limit) return `That ${video ? 'video' : 'image'} is ${fileSize(file.size)}. The limit is ${limit / 1024 / 1024} MB.`
  return null
}

/**
 * Bars for the per-frame strip from the backend's compact timeline ({@code frames: [[t, forgery, explicit], ...]}).
 * {@code band}: 'flag' | 'review' | 'ok' for the forgery score, so colour is never the only cue (the tooltip names it).
 */
export function frameBars(video, thresholds) {
  const frames = Array.isArray(video?.frames) ? video.frames : []
  const peak = video?.peak?.index ?? -1
  const review = thresholds?.forgeryReview ?? 0.5
  const flag = thresholds?.forgeryAutoFlag ?? 0.85
  return frames.map(([t, forgery, explicit], index) => ({
    index,
    t,
    forgery,
    explicit,
    peak: index === peak,
    band: forgery >= flag ? 'flag' : forgery >= review ? 'review' : 'ok',
  }))
}

export function aggregateLabel(mode, k) {
  return mode === 'MAX' ? 'max over frames' : `mean of the top ${k ?? 3} frames`
}

const C2PA_STATUS = {
  TRUSTED: { label: 'Trusted signer', tone: 'ok', description: 'Signature valid and the signer is on the trust list.' },
  VALID: { label: 'Valid', tone: 'ok', description: 'Signature and content hashes check out.' },
  VALID_UNTRUSTED: {
    label: 'Valid, untrusted signer',
    tone: 'warn',
    description: 'Hashes check out, but the signer is not on a trust list. Treat the claims as self-declared.',
  },
  INVALID: { label: 'Invalid', tone: 'bad', description: 'The manifest does not match this content, or its signature failed.' },
}

export function c2paStatus(status) {
  return C2PA_STATUS[status] || { label: status || 'Unknown', tone: 'warn', description: '' }
}

/** "c2pa.color_adjustments" becomes "color adjustments". */
export function actionLabel(action) {
  return String(action || '')
    .replace(/^c2pa\./, '')
    .replace(/[._]/g, ' ')
    .trim()
}
