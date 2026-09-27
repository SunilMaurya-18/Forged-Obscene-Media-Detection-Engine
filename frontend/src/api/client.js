import { apiError, networkError } from '../lib/errors'

const AUTH_KEY = 'fmd_basic_auth'

export function getAuthHeader() {
  const token = sessionStorage.getItem(AUTH_KEY)
  return token ? { Authorization: `Basic ${token}` } : {}
}

export function setBasicAuth(username, password) {
  const token = btoa(`${username}:${password}`)
  sessionStorage.setItem(AUTH_KEY, token)
}

export function clearAuth() {
  sessionStorage.removeItem(AUTH_KEY)
}

export function hasStoredAuth() {
  return Boolean(sessionStorage.getItem(AUTH_KEY))
}

async function toError(res) {
  let body = null
  try {
    body = await res.json()
  } catch {
    /* non-JSON error page */
  }
  return apiError(res.status, body)
}

/** fetch() that turns network failures and non-2xx responses into plain-language errors. */
async function request(path, options = {}) {
  let res
  try {
    res = await fetch(path, options)
  } catch (e) {
    if (e?.name === 'AbortError') throw e
    throw networkError(e)
  }
  if (!res.ok) throw await toError(res)
  return res
}

export async function apiFetch(path, options = {}) {
  const headers = {
    ...(options.headers || {}),
    ...getAuthHeader(),
  }
  const res = await request(path, { ...options, headers })
  if (res.status === 204) return null
  const ct = res.headers.get('content-type') || ''
  if (ct.includes('application/json')) return res.json()
  return res.text()
}

export async function analyzeImage(file) {
  const body = new FormData()
  body.append('file', file)
  return apiFetch('/api/analyze', { method: 'POST', body })
}

export async function login(username, password) {
  setBasicAuth(username, password)
  try {
    return await apiFetch('/api/auth/login', { method: 'POST' })
  } catch (e) {
    clearAuth()
    throw e
  }
}

export async function fetchQueue() {
  return apiFetch('/api/queue')
}

export async function fetchFlagged() {
  return apiFetch('/api/queue?status=flagged')
}

function dispositionFilename(header, fallback) {
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header || '')
  return match ? decodeURIComponent(match[1]) : fallback
}

/** Generates the evidentiary PDF and saves it. The optional original is only used for panels, never stored. */
export async function downloadReport(id, original) {
  const body = new FormData()
  if (original) body.append('file', original)
  const res = await request(`/api/queue/${id}/report`, {
    method: 'POST',
    body,
    headers: { ...getAuthHeader() },
  })
  const blob = await res.blob()
  const filename = dispositionFilename(res.headers.get('content-disposition'), `evidence-report-analysis-${id}.pdf`)
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  return {
    filename,
    reportId: res.headers.get('x-report-id'),
    sha256: res.headers.get('x-report-sha256'),
    panels: res.headers.get('x-report-panels'),
    signingKey: res.headers.get('x-report-signing-key'),
  }
}

/** {@code keepalive} lets a decision still in its undo window be sent while the page is closing. */
export async function updateQueueDecision(id, decision, { keepalive = false } = {}) {
  return apiFetch(`/api/queue/${id}/decision`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ decision }),
    keepalive,
  })
}

/** Phase 21: public, no login. Returns the case view plus a one-time PIN. */
export async function fileReport({ file, relationship, consent, details, contact, jurisdiction, locale }) {
  const body = new FormData()
  body.append('file', file)
  body.append('consent', consent ? 'true' : 'false')
  if (relationship) body.append('relationship', relationship)
  if (details) body.append('details', details)
  if (contact) body.append('contact', contact)
  if (jurisdiction) body.append('jurisdiction', jurisdiction)
  if (locale) body.append('locale', locale)
  const res = await request('/api/complaints', { method: 'POST', body, credentials: 'omit' })
  return res.json()
}

/** The PIN travels in a header so it never lands in URLs, history or server logs. */
export async function fetchCaseStatus(caseNumber, pin) {
  const res = await request(`/api/complaints/${encodeURIComponent(caseNumber)}/status`, {
    headers: { 'X-Case-Pin': pin },
    credentials: 'omit',
    cache: 'no-store',
  })
  return res.json()
}

export async function fetchStats() {
  return apiFetch('/api/stats')
}

export async function fetchComplianceQueue() {
  return apiFetch('/api/compliance/queue')
}

export async function fetchComplianceBreaches() {
  return apiFetch('/api/compliance/breaches')
}

export async function fetchComplianceSummary() {
  return apiFetch('/api/compliance/summary')
}

export async function fetchApiClients() {
  return apiFetch('/api/admin/clients')
}

function postJson(path, body) {
  return apiFetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  })
}

export async function createApiClient(name, plan) {
  return postJson('/api/admin/clients', { name, plan })
}

export async function rotateApiClient(id) {
  return postJson(`/api/admin/clients/${id}/rotate`)
}

export async function revokeApiClient(id) {
  return postJson(`/api/admin/clients/${id}/revoke`)
}

export async function changeApiClientPlan(id, plan) {
  return postJson(`/api/admin/clients/${id}/plan`, { plan })
}

export async function fetchProvenance(provenanceId) {
  return apiFetch(`/api/verify/${encodeURIComponent(provenanceId)}`)
}

export async function verifyUpload(file) {
  const body = new FormData()
  body.append('file', file)
  return apiFetch('/api/verify', { method: 'POST', body })
}

export async function fileComplaint(file, complaintType, reference, jurisdiction) {
  const body = new FormData()
  body.append('file', file)
  body.append('complaintType', complaintType)
  if (reference) body.append('reference', reference)
  if (jurisdiction) body.append('jurisdiction', jurisdiction)
  return apiFetch('/api/compliance/complaints', { method: 'POST', body })
}

export async function fetchComplianceRules() {
  return apiFetch('/api/compliance/rules')
}

/** Phase 18: sends only the hash computed on this device. The request body is exactly {hash, algorithm}. */
export async function prescreenHash(hash, algorithm = 'phash') {
  const res = await request('/api/prescreen', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ hash, algorithm }),
    credentials: 'omit',
  })
  return res.json()
}

/** Phase 20: VALID / TAMPERED / NOT_FOUND for a report ID or analysis ID. Public. */
export async function verifyReport(id) {
  return apiFetch(`/api/verify-report/${encodeURIComponent(id)}`)
}
