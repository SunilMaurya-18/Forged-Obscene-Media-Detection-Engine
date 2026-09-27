const API_ANALYZE = '/api/analyze'
const CONSOLE_URL = `${location.protocol}//${location.hostname}:3000`

const DECISION_COPY = {
  ALLOW: { chip: 'Allowed' },
  REVIEW: {
    chip: 'In review',
    title: 'Held for review',
    body: 'Hidden from others until a moderator approves it.',
    icon: '<path d="M12 9v4m0 4h.01M10.3 3.9 2.4 17.5A2 2 0 0 0 4.1 20.5h15.8a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"/>',
  },
  AUTO_FLAG: {
    chip: 'Removed',
    title: 'Removed automatically',
    body: 'This media was blocked by automated moderation.',
    icon: '<path d="M12 3 4 6v6c0 4.5 3.2 8.2 8 9 4.8-.8 8-4.5 8-9V6l-8-3Z"/><path d="m9.5 9.5 5 5m0-5-5 5"/>',
  },
}

const SEED_POSTS = [
  { id: 'seed-2', author: 'Sam Rivera', handle: '@sam', caption: 'New studio portrait setup, finally happy with the lighting.', kind: 'portrait', minutesAgo: 42 },
  { id: 'seed-1', author: 'Alex Chen', handle: '@alex', caption: 'Weekend hike. Untouched JPEG straight from my phone.', kind: 'landscape', minutesAgo: 95 },
]

const feedEl = document.getElementById('feed')
const statusEl = document.getElementById('composer-status')
const fileInput = document.getElementById('file')
const captionInput = document.getElementById('caption')
const picker = document.getElementById('picker')
const pickerPreview = document.getElementById('picker-preview')
const pickerEmpty = document.getElementById('picker-empty')
const pickerName = document.getElementById('picker-name')
const postFileBtn = document.getElementById('post-file')
const postManipBtn = document.getElementById('post-manipulated')

document.getElementById('console-link').href = `${CONSOLE_URL}/queue`

function el(tag, className, text) {
  const node = document.createElement(tag)
  if (className) node.className = className
  if (text != null) node.textContent = text
  return node
}

function svg(paths, viewBox = '0 0 24 24') {
  const node = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
  node.setAttribute('viewBox', viewBox)
  node.setAttribute('aria-hidden', 'true')
  node.innerHTML = paths
  return node
}

function setStatus(msg, isError = false) {
  statusEl.hidden = !msg
  statusEl.textContent = msg || ''
  statusEl.classList.toggle('error', isError)
}

function relTime(minutes) {
  if (minutes < 1) return 'now'
  if (minutes < 60) return `${minutes}m`
  return `${Math.round(minutes / 60)}h`
}

function makeSeedImage(kind) {
  const canvas = document.createElement('canvas')
  canvas.width = 800
  canvas.height = 520
  const ctx = canvas.getContext('2d')
  if (kind === 'portrait') {
    const g = ctx.createRadialGradient(400, 180, 40, 400, 260, 520)
    g.addColorStop(0, '#3b4f6b')
    g.addColorStop(1, '#0f1622')
    ctx.fillStyle = g
    ctx.fillRect(0, 0, 800, 520)
    ctx.fillStyle = '#c9a27e'
    ctx.beginPath()
    ctx.arc(400, 210, 92, 0, Math.PI * 2)
    ctx.fill()
    ctx.fillStyle = '#34465e'
    ctx.beginPath()
    ctx.ellipse(400, 520, 210, 170, 0, Math.PI, 0)
    ctx.fill()
  } else {
    const sky = ctx.createLinearGradient(0, 0, 0, 520)
    sky.addColorStop(0, '#f59e7b')
    sky.addColorStop(0.55, '#7c5c8f')
    sky.addColorStop(1, '#1d2440')
    ctx.fillStyle = sky
    ctx.fillRect(0, 0, 800, 520)
    ctx.fillStyle = '#fde68a'
    ctx.beginPath()
    ctx.arc(560, 250, 46, 0, Math.PI * 2)
    ctx.fill()
    const ridge = (color, base, amp, seed) => {
      ctx.fillStyle = color
      ctx.beginPath()
      ctx.moveTo(0, 520)
      for (let x = 0; x <= 800; x += 20) {
        ctx.lineTo(x, base - Math.abs(Math.sin((x + seed) / 90)) * amp - Math.sin(x / 37) * 12)
      }
      ctx.lineTo(800, 520)
      ctx.fill()
    }
    ridge('#3b2f55', 360, 110, 10)
    ridge('#231d38', 420, 80, 140)
    ridge('#141225', 480, 50, 300)
  }
  return canvas.toDataURL('image/jpeg', 0.88)
}

/** High-frequency checker pattern that the demo model tends to score as manipulated. */
function makeManipulatedBlob() {
  const canvas = document.createElement('canvas')
  canvas.width = 224
  canvas.height = 224
  const ctx = canvas.getContext('2d')
  const tile = 8
  for (let y = 0; y < 224; y += tile) {
    for (let x = 0; x < 224; x += tile) {
      const n = (x * 13 + y * 7) % 255
      ctx.fillStyle = `rgb(${n}, ${(n * 3) % 255}, ${255 - n})`
      ctx.fillRect(x, y, tile, tile)
    }
  }
  return new Promise((resolve) => canvas.toBlob((blob) => resolve(blob), 'image/jpeg', 0.92))
}

function buildChip(state, analysis) {
  const chip = el('span', `chip ${state}`)
  chip.append(el('span', 'dot'))
  const label =
    state === 'pending' ? 'Screening…' : state === 'seed' ? 'Not screened' : state === 'error' ? 'Check failed' : DECISION_COPY[state]?.chip || state
  chip.append(document.createTextNode(label))
  if (analysis && state in DECISION_COPY) {
    chip.title = `forgery ${Number(analysis.forgeryScore).toFixed(2)} · explicit ${Number(analysis.explicitScore).toFixed(2)}`
  }
  return chip
}

function renderPost({ id, author, handle, caption, imageUrl, minutesAgo = 0, state, analysis }) {
  const article = el('article', 'post card')
  article.dataset.id = id

  const head = el('div', 'post-head')
  const avatar = el('span', `avatar${handle === '@you' ? ' you' : ''}`, author[0])
  avatar.setAttribute('aria-hidden', 'true')
  const who = el('div', 'post-author')
  who.append(el('strong', null, author), el('span', null, `${handle} · ${relTime(minutesAgo)}`))
  head.append(avatar, who, buildChip(state, analysis))

  const media = el('div', 'post-media')
  const img = el('img')
  img.src = imageUrl
  img.alt = caption
  media.append(img)
  const copy = DECISION_COPY[state]
  if (copy?.title) {
    media.classList.add('withheld')
    const note = el('div', 'withheld-note')
    const inner = el('div')
    const icon = svg(copy.icon)
    icon.style.color = state === 'REVIEW' ? 'var(--review)' : 'var(--flag)'
    inner.append(icon, el('strong', null, copy.title), el('span', null, copy.body))
    note.append(inner)
    media.append(note)
  }

  const foot = el('div', 'post-foot')
  const meta = el(
    'span',
    'meta',
    analysis?.id ? `analysis #${analysis.id} · pHash ${analysis.phash || '—'}` : state === 'pending' ? 'Running /api/analyze…' : '',
  )
  foot.append(meta)
  if (state === 'REVIEW') {
    const link = el('a', null, 'Open queue →')
    link.href = `${CONSOLE_URL}/queue`
    link.target = '_blank'
    link.rel = 'noreferrer'
    link.style.color = 'var(--review)'
    link.style.textDecoration = 'none'
    link.style.flexShrink = '0'
    foot.append(link)
  }

  article.append(head)
  if (caption) article.append(el('p', 'post-caption', caption))
  article.append(media, foot)
  return article
}

async function analyzeFile(file) {
  const body = new FormData()
  body.append('file', file)
  const res = await fetch(API_ANALYZE, { method: 'POST', body })
  if (!res.ok) {
    let msg = `Analysis failed (${res.status})`
    try {
      const data = await res.json()
      msg = data.error || data.message || msg
    } catch {
      /* non-JSON error body */
    }
    throw new Error(msg)
  }
  return res.json()
}

async function publish({ author, handle, caption, file }) {
  const id = `post-${Date.now()}`
  const imageUrl = URL.createObjectURL(file)
  const base = { id, author, handle, caption, imageUrl }
  let node = renderPost({ ...base, state: 'pending' })
  feedEl.prepend(node)
  setStatus('')

  try {
    const analysis = await analyzeFile(file)
    const next = renderPost({ ...base, state: analysis.decision, analysis })
    node.replaceWith(next)
    node = next
    if (analysis.decision === 'REVIEW') setStatus(`Post held for review (analysis #${analysis.id}). A moderator can approve it in the console.`)
    else if (analysis.decision === 'AUTO_FLAG') setStatus(`Post removed automatically (analysis #${analysis.id}).`)
    return analysis
  } catch (err) {
    node.replaceWith(renderPost({ ...base, state: 'error' }))
    setStatus(err.message || 'Post failed', true)
    throw err
  }
}

function resetComposer() {
  fileInput.value = ''
  captionInput.value = ''
  if (pickerPreview.src) URL.revokeObjectURL(pickerPreview.src)
  pickerPreview.hidden = true
  pickerPreview.removeAttribute('src')
  pickerEmpty.hidden = false
  pickerName.hidden = true
}

function showPicked(file) {
  if (!file) return resetComposer()
  if (pickerPreview.src) URL.revokeObjectURL(pickerPreview.src)
  pickerPreview.src = URL.createObjectURL(file)
  pickerPreview.hidden = false
  pickerEmpty.hidden = true
  pickerName.textContent = file.name
  pickerName.hidden = false
}

fileInput.addEventListener('change', () => showPicked(fileInput.files?.[0]))

;['dragenter', 'dragover'].forEach((type) =>
  picker.addEventListener(type, () => picker.classList.add('dragging')),
)
;['dragleave', 'drop'].forEach((type) =>
  picker.addEventListener(type, () => picker.classList.remove('dragging')),
)

async function withBusy(fn) {
  postFileBtn.disabled = true
  postManipBtn.disabled = true
  try {
    await fn()
  } catch {
    /* surfaced via setStatus */
  } finally {
    postFileBtn.disabled = false
    postManipBtn.disabled = false
  }
}

postFileBtn.addEventListener('click', () => {
  const file = fileInput.files?.[0]
  if (!file) {
    setStatus('Add a photo first.', true)
    return
  }
  const caption = captionInput.value.trim() || file.name
  resetComposer()
  withBusy(() => publish({ author: 'You', handle: '@you', caption, file }))
})

postManipBtn.addEventListener('click', () =>
  withBusy(async () => {
    const blob = await makeManipulatedBlob()
    const file = new File([blob], 'manipulated-sample.jpg', { type: 'image/jpeg' })
    await publish({ author: 'Deepfake Bot', handle: '@bot', caption: 'Totally real photo, trust me. (synthetic manipulated sample)', file })
  }),
)

for (const seed of SEED_POSTS) {
  feedEl.append(renderPost({ ...seed, imageUrl: makeSeedImage(seed.kind), state: 'seed' }))
}
