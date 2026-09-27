import en from '../i18n/en.json'

const CODES = en.errors.codes

/** Server messages that are already written for people (a sentence, not a snake_case code). */
function isSentence(text) {
  return typeof text === 'string' && /\s/.test(text.trim()) && !/^[a-z0-9_]+$/.test(text.trim()) && text.length < 300
}

function keyForStatus(status) {
  if (status === 400) return 'badRequest'
  if (status === 401) return 'auth'
  if (status === 403) return 'forbidden'
  if (status === 404) return 'notFound'
  if (status === 409) return 'conflict'
  if (status === 413) return 'tooLarge'
  if (status === 415) return 'unsupported'
  if (status === 422) return 'unprocessable'
  if (status === 429) return 'tooMany'
  if (status === 502 || status === 503 || status === 504) return 'unavailable'
  return 'server'
}

/**
 * Builds the Error thrown by every API call: {@code message} is always plain English (never "HTTP 500"),
 * {@code i18nKey} lets end-user screens show the same message in the reader's language.
 */
export function apiError(status, body) {
  const code = typeof body?.error === 'string' ? body.error : undefined
  const make = (message, i18nKey) => Object.assign(new Error(message), { status, code, i18nKey })
  if (code && CODES[code]) return make(CODES[code], `errors.codes.${code}`)

  // Spring's default error body ({timestamp, status, error: "Not Found", path}) is not written for people.
  const springDefault = body && 'path' in body && 'status' in body
  const serverText = springDefault ? null : isSentence(body?.message) ? body.message : isSentence(code) ? code : null
  if (serverText && status < 500 && status !== 401 && status !== 403) return make(serverText, null)

  const key = keyForStatus(status)
  return make(en.errors[key], `errors.${key}`)
}

export function networkError(cause) {
  return Object.assign(new Error(en.errors.network), { status: 0, code: 'network', i18nKey: 'errors.network', cause })
}

/** Message for an error in the current language; server-written sentences are shown as they are. */
export function errorText(t, err) {
  if (!err) return ''
  if (typeof err === 'string') return err
  if (err.i18nKey) return t(err.i18nKey, { defaultValue: err.message })
  return err.message || t('errors.server')
}
