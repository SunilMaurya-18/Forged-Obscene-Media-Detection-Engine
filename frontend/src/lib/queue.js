import { hammingDistance } from '../utils/onDeviceHash.js'
import { humanDuration } from './sla.js'

/** Max pHash distance (of 64 bits) for "clearly similar": re-uploads, recompressions, light crops. */
export const SIMILAR_MAX_DISTANCE = 10

const time = (iso) => (iso ? new Date(iso).getTime() : null)

/**
 * Same order as the backend's AnalysisService.URGENCY: items with a legal deadline first, nearest
 * deadline first; then everything else oldest first.
 */
export function byUrgency(a, b) {
  const da = time(a.slaDeadline)
  const db = time(b.slaDeadline)
  if (da != null && db != null && da !== db) return da - db
  if (da != null && db == null) return -1
  if (da == null && db != null) return 1
  return time(a.createdAt) - time(b.createdAt)
}

export function byNewest(a, b) {
  return time(b.createdAt) - time(a.createdAt)
}

/** { tone, text } for the deadline chip; tone also drives the icon, so it's never colour alone. */
export function deadlineInfo(slaDeadline, now = Date.now(), atRiskSeconds = 30 * 60) {
  if (!slaDeadline) return { tone: 'none', text: 'No legal deadline' }
  const left = Math.round((time(slaDeadline) - now) / 1000)
  if (left <= 0) return { tone: 'overdue', text: `Overdue by ${humanDuration(left)}` }
  return { tone: left <= atRiskSeconds ? 'soon' : 'ok', text: `Due in ${humanDuration(left)}` }
}

/** Ids of other items whose perceptual hash is within {@link SIMILAR_MAX_DISTANCE} of {@code item}. */
export function similarIds(item, items, maxDistance = SIMILAR_MAX_DISTANCE) {
  if (!item?.phash) return []
  return items
    .filter((o) => o.id !== item.id && o.phash && o.phash.length === item.phash.length)
    .filter((o) => hammingDistance(item.phash, o.phash) <= maxDistance)
    .map((o) => o.id)
}
