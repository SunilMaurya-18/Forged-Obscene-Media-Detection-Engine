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

/**
 * Images and (Phase 23) videos: the backend picks the pipeline from the file itself.
 * {@code jurisdiction} (optional) picks the rules for the upload's appeal clock.
 */
export async function analyzeImage(file, jurisdiction) {
  const body = new FormData()
  body.append('file', file)
  if (jurisdiction) body.append('jurisdiction', jurisdiction)
  return apiFetch('/api/analyze', { method: 'POST', body })
}

export const analyzeMedia = analyzeImage

export async function login(username, password) {
  setBasicAuth(username, password)
  try {
    return await apiFetch('/api/auth/login', { method: 'POST' })
  } catch (e) {
    clearAuth()
    throw e
  }
}

/** {@code status}: review | flagged | second-review | escalated; {@code scope}: all | mine | team | unassigned. */
export async function fetchQueue(status = 'review', scope = 'all') {
  return apiFetch(`/api/queue?status=${encodeURIComponent(status)}&scope=${encodeURIComponent(scope)}`)
}

export async function fetchFlagged() {
  return fetchQueue('flagged')
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

/**
 * {@code keepalive} lets a decision still in its undo window be sent while the page is closing.
 * Resolves with {@code pendingSecondReview: true} (HTTP 202) when overriding an AUTO_FLAG became a proposal.
 */
export async function updateQueueDecision(id, decision, { keepalive = false, reason } = {}) {
  return apiFetch(`/api/queue/${id}/decision`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(reason ? { decision, reason } : { decision }),
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

// ---------------------------------------------------------------- Phase 23: moderation depth

export async function secondReview(id, confirm, note) {
  return postJson(`/api/queue/${id}/second-review`, { confirm, note })
}

/** No assignee = take it yourself; {@code team} without an assignee routes to that team's pool (seniors only). */
export async function assignItem(id, assignee, team) {
  return postJson(`/api/queue/${id}/assign`, assignee ? { assignee } : team ? { team } : {})
}

export async function unassignItem(id) {
  return postJson(`/api/queue/${id}/unassign`)
}

export async function escalateItem(id, reason) {
  return postJson(`/api/queue/${id}/escalate`, { reason })
}

/** Logs (and counts toward the exposure limit) that the moderator chose to see the media unblurred. */
export async function revealItem(id) {
  return postJson(`/api/queue/${id}/reveal`)
}

export async function fetchItemAudit(id) {
  return apiFetch(`/api/queue/${id}/audit`)
}

export async function fetchModerationMe() {
  return apiFetch('/api/moderation/me')
}

export async function fetchTeams() {
  return apiFetch('/api/moderation/teams')
}

export async function takeBreak() {
  return postJson('/api/moderation/break')
}

export async function fetchAppeals(status = 'open') {
  return apiFetch(`/api/appeals?status=${encodeURIComponent(status)}`)
}

export async function decideAppeal(id, outcome, reason) {
  return postJson(`/api/appeals/${id}/decision`, { outcome, reason })
}

/** Public: the uploader's notice number + PIN. */
export async function fileAppeal({ noticeNumber, pin, statement }) {
  const res = await request('/api/appeals', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ noticeNumber, pin, statement }),
    credentials: 'omit',
  })
  return res.json()
}

/** The PIN travels in a header so it never lands in URLs, history or server logs. */
export async function fetchAppealStatus(noticeNumber, pin) {
  const res = await request(`/api/appeals/${encodeURIComponent(noticeNumber)}/status`, {
    headers: { 'X-Case-Pin': pin },
    credentials: 'omit',
    cache: 'no-store',
  })
  return res.json()
}

/** Public aggregate report; {@code period}: YYYY-MM, YYYY-Qn, YYYY or last-Nd. */
export async function fetchTransparency(period) {
  const res = await request(`/api/transparency?period=${encodeURIComponent(period || 'last-30d')}`, { credentials: 'omit' })
  return res.json()
}

/** Phase 25: demo settings, bundled samples and the current story ({@code enabled: false} when off). */
export async function fetchDemo() {
  return apiFetch('/api/demo', { cache: 'no-store' })
}

/** Seeds the demo story on first use and returns it; {@code fresh} (moderators) seeds another. */
export async function startDemoStory({ fresh = false } = {}) {
  return apiFetch(`/api/demo/story${fresh ? '?fresh=true' : ''}`, { method: 'POST' })
}

export async function resetDemo() {
  return apiFetch('/api/demo/reset', { method: 'POST' })
}

// ---------------------------------------------------------------- Phase 26: investigations

/** {@code status}: open | closed | all; {@code analysisId}: only cases containing that analysis. */
export async function fetchInvestigations({ status = 'open', analysisId } = {}) {
  const q = analysisId != null ? `analysisId=${encodeURIComponent(analysisId)}` : `status=${encodeURIComponent(status)}`
  return apiFetch(`/api/investigations?${q}`, { cache: 'no-store' })
}

export async function fetchInvestigation(caseNumber) {
  return apiFetch(`/api/investigations/${encodeURIComponent(caseNumber)}`, { cache: 'no-store' })
}

export async function openInvestigation(analysisId, title) {
  return postJson('/api/investigations', title ? { analysisId, title } : { analysisId })
}

function caseAction(caseNumber, action, body) {
  return postJson(`/api/investigations/${encodeURIComponent(caseNumber)}/${action}`, body)
}

export async function addInvestigationItem(caseNumber, analysisId) {
  return caseAction(caseNumber, 'items', { analysisId })
}

/** A sighting someone told a moderator about; the account ID is pseudonymised by the server. */
export async function recordSighting(caseNumber, { analysisId, platform, postRef, accountId, observedAt }) {
  return caseAction(caseNumber, 'sightings', { analysisId, platform, postRef, accountId, observedAt })
}

export async function addInvestigationNote(caseNumber, text) {
  return caseAction(caseNumber, 'notes', { text })
}

export async function closeInvestigation(caseNumber, reason) {
  return caseAction(caseNumber, 'close', { reason })
}

export async function reopenInvestigation(caseNumber) {
  return caseAction(caseNumber, 'reopen')
}

// ---------------------------------------------------------------- Phase 27: custody

/** Every custody event with its hashes, and whether the chain verifies. */
export async function fetchCustody(caseNumber) {
  return apiFetch(`/api/investigations/${encodeURIComponent(caseNumber)}/custody`, { cache: 'no-store' })
}

/** Signs a manifest of what the case holds now; returns the preservation with its manifest and signature. */
export async function preserveEvidence(caseNumber, reason) {
  return caseAction(caseNumber, 'preservations', reason ? { reason } : {})
}

export async function verifyPreservation(caseNumber, preservationId) {
  return apiFetch(
    `/api/investigations/${encodeURIComponent(caseNumber)}/preservations/${encodeURIComponent(preservationId)}/verify`,
    { cache: 'no-store' },
  )
}

/** Plain-text manifest exactly as signed, for hashing offline. */
export async function fetchPreservationManifest(caseNumber, preservationId) {
  return apiFetch(
    `/api/investigations/${encodeURIComponent(caseNumber)}/preservations/${encodeURIComponent(preservationId)}/manifest`,
    { cache: 'no-store' },
  )
}

/** {@code postRefs} empty: every linked sighting on that platform in the case. */
export async function requestLegalHold(caseNumber, { platform, postRefs, note }) {
  return caseAction(caseNumber, 'legal-holds', { platform, postRefs, note })
}

export async function acknowledgeLegalHold(caseNumber, holdNumber, partnerReference) {
  return caseAction(caseNumber, `legal-holds/${encodeURIComponent(holdNumber)}/acknowledge`, { partnerReference })
}

export async function releaseLegalHold(caseNumber, holdNumber, reason) {
  return caseAction(caseNumber, `legal-holds/${encodeURIComponent(holdNumber)}/release`, { reason })
}

// ---------------------------------------------------------------- Phase 28: investigator handover

export async function fetchLegalRequests() {
  return apiFetch('/api/legal-requests', { cache: 'no-store' })
}

export async function fetchInvestigatorAccounts() {
  return apiFetch('/api/legal-requests/investigators')
}

/** {@code expiresAt}: ISO instant; the server accepts between an hour and the configured maximum term. */
export async function fileLegalRequest({ authority, orderReference, investigator, caseNumbers, expiresAt, note }) {
  return postJson('/api/legal-requests', { authority, orderReference, investigator, caseNumbers, expiresAt, note })
}

/** {@code action}: approve | reject | revoke. Seniors only, and never the moderator who filed it. */
export async function decideLegalRequest(requestNumber, action, note) {
  return postJson(`/api/legal-requests/${encodeURIComponent(requestNumber)}/${action}`, { note })
}

export async function fetchInvestigatorCases() {
  return apiFetch('/api/investigator/cases', { cache: 'no-store' })
}

/** Recorded in the case's custody log as INVESTIGATOR_VIEWED. */
export async function fetchInvestigatorCase(caseNumber) {
  return apiFetch(`/api/investigator/cases/${encodeURIComponent(caseNumber)}`, { cache: 'no-store' })
}

export async function issueEvidencePackage(caseNumber) {
  return postJson(`/api/investigator/cases/${encodeURIComponent(caseNumber)}/packages`)
}

export async function fetchEvidencePackages() {
  return apiFetch('/api/investigator/packages', { cache: 'no-store' })
}

/** Saves the ZIP; recorded as PACKAGE_DOWNLOADED. Resolves with the hashes to check it against. */
export async function downloadEvidencePackage(packageId) {
  const res = await request(`/api/investigator/packages/${encodeURIComponent(packageId)}/download`, {
    headers: { ...getAuthHeader() },
    cache: 'no-store',
  })
  const blob = await res.blob()
  const filename = dispositionFilename(res.headers.get('content-disposition'), `${packageId}.zip`)
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  return {
    filename,
    sha256: res.headers.get('x-package-sha256'),
    manifestSha256: res.headers.get('x-manifest-sha256'),
    signingKeyId: res.headers.get('x-signing-key-id'),
  }
}

/** Downloads a bundled synthetic sample as a File, ready for any upload form. */
export async function fetchSampleFile(sample) {
  const res = await request(sample.url, { credentials: 'omit' })
  const blob = await res.blob()
  return new File([blob], sample.name, { type: sample.mimeType || blob.type })
}
