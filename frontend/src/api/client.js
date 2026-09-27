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

async function parseError(res) {
  try {
    const data = await res.json()
    return data.error || data.message || res.statusText
  } catch {
    return res.statusText || `HTTP ${res.status}`
  }
}

export async function apiFetch(path, options = {}) {
  const headers = {
    ...(options.headers || {}),
    ...getAuthHeader(),
  }
  const res = await fetch(path, { ...options, headers })
  if (!res.ok) {
    const err = new Error(await parseError(res))
    err.status = res.status
    throw err
  }
  if (res.status === 204) return null
  const ct = res.headers.get('content-type') || ''
  if (ct.includes('application/json')) return res.json()
  return res.text()
}

export async function analyzeImage(file) {
  const body = new FormData()
  body.append('file', file)
  const res = await fetch('/api/analyze', {
    method: 'POST',
    body,
    headers: { ...getAuthHeader() },
  })
  if (!res.ok) {
    const err = new Error(await parseError(res))
    err.status = res.status
    throw err
  }
  return res.json()
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

export async function updateQueueDecision(id, decision) {
  return apiFetch(`/api/queue/${id}/decision`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ decision }),
  })
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

export async function fetchProvenance(provenanceId) {
  return apiFetch(`/api/verify/${encodeURIComponent(provenanceId)}`)
}

export async function verifyUpload(file) {
  const body = new FormData()
  body.append('file', file)
  return apiFetch('/api/verify', { method: 'POST', body })
}

export async function fileComplaint(file, complaintType, reference) {
  const body = new FormData()
  body.append('file', file)
  body.append('complaintType', complaintType)
  if (reference) body.append('reference', reference)
  return apiFetch('/api/compliance/complaints', { method: 'POST', body })
}
