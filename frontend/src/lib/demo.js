/** Phase 25: helpers for the one-click demo tour and the public-demo banner. */

/** Sample kinds that are still images (the report and complaint forms take images only). */
export const IMAGE_SAMPLES = ['review', 'known', 'credentials']

/** The story in the order the tour tells it. */
export const STORY_STEPS = ['flagged', 'appeal', 'report', 'atRisk', 'breached']

/** Seconds until {@code iso}; negative once it has passed, null without a time. */
export function secondsUntil(iso, now = Date.now()) {
  if (!iso) return null
  const t = new Date(iso).getTime()
  return Number.isNaN(t) ? null : Math.round((t - now) / 1000)
}

/** True when every part of the story is present (a partial story means the records were edited or removed). */
export function storyComplete(story) {
  return Boolean(story) && STORY_STEPS.every((step) => story[step])
}

export function isSenior(user) {
  return Boolean(user?.roles?.includes('ROLE_SENIOR_MODERATOR'))
}

/** A visitor may reset a public demo; otherwise only a senior moderator may. */
export function canReset(demo, user) {
  if (!demo?.enabled) return false
  return Boolean(demo.publicMode) || isSenior(user)
}

/** Six-digit PINs read more easily as "482 913". */
export function spacedPin(pin) {
  return /^\d{6}$/.test(pin || '') ? `${pin.slice(0, 3)} ${pin.slice(3)}` : pin || ''
}
