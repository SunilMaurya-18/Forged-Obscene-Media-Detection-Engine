import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  ArrowRight,
  Brain,
  Download,
  EyeOff,
  Fingerprint,
  Flame,
  Gauge,
  History,
  ImageUp,
  Loader2,
  Lock,
  Repeat2,
  RotateCcw,
  ScanFace,
  ScanSearch,
  Scale,
  Stamp,
  UploadCloud,
  X,
} from 'lucide-react'
import { analyzeImage } from '../api/client'
import { Alert, Button, Card, CardHeader, CopyButton, Kbd, PageHeader, ScoreMeter, buttonClass } from '../components/ui'
import { useStats } from '../context/StatsContext'
import { decisionMeta } from '../lib/decision'
import { cn, dateTime, fileSize } from '../lib/format'
import { MATCH_VIA, downloadBase64, labelledFilename, manifestStatus, shortId } from '../lib/provenance'

const MAX_BYTES = 25 * 1024 * 1024

const PIPELINE = [
  { icon: UploadCloud, label: 'Uploading to the analysis API' },
  { icon: ScanFace, label: 'Forgery detector (EfficientNet-B0)' },
  { icon: EyeOff, label: 'Explicit-content classifier' },
  { icon: Scale, label: 'Decision engine routing' },
  { icon: Stamp, label: 'Embedding signed provenance label' },
]

const HOW_IT_WORKS = [
  {
    icon: ScanFace,
    title: 'Forgery detection',
    body: 'An EfficientNet-B0 model trained on FaceForensics++ scores face manipulation, with a Grad-CAM heatmap showing where it looked.',
  },
  {
    icon: EyeOff,
    title: 'Explicit-content check',
    body: 'A separate classifier scores explicit content. It never shares state with the forgery model.',
  },
  {
    icon: Scale,
    title: 'Automatic routing',
    body: 'Configurable thresholds route every upload to Allow, human Review, or Auto-flag.',
  },
  {
    icon: Stamp,
    title: 'Provenance label',
    body: 'Each result gets a traceable ID and a signed manifest embedded in a labelled copy. Anyone can verify it later.',
  },
  {
    icon: Lock,
    title: 'Privacy by design',
    body: 'Only hashes, the scores, and the decision are stored. The image itself is discarded.',
  },
]

function useImageDimensions(src) {
  const [dims, setDims] = useState(null)
  useEffect(() => {
    if (!src) return
    const img = new Image()
    img.onload = () => setDims({ src, w: img.naturalWidth, h: img.naturalHeight })
    img.src = src
  }, [src])
  return dims?.src === src ? dims : null
}

function Dropzone({ preview, busy, onFile }) {
  const inputRef = useRef(null)
  const [dragging, setDragging] = useState(false)

  const open = () => inputRef.current?.click()

  return (
    <div
      role="button"
      tabIndex={0}
      aria-label="Upload an image"
      onClick={open}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          open()
        }
      }}
      onDragOver={(e) => {
        e.preventDefault()
        setDragging(true)
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(e) => {
        e.preventDefault()
        setDragging(false)
        onFile(e.dataTransfer.files?.[0])
      }}
      className={cn(
        'group relative aspect-[4/3] rounded-2xl border border-dashed overflow-hidden cursor-pointer transition-all duration-300',
        dragging
          ? 'border-brand bg-brand/[0.07] scale-[1.01]'
          : preview
            ? 'border-line bg-checker'
            : 'border-line-strong bg-white/[0.015] hover:border-white/25 hover:bg-white/[0.03]',
      )}
    >
      <input
        ref={inputRef}
        type="file"
        accept="image/*"
        className="hidden"
        onChange={(e) => {
          onFile(e.target.files?.[0])
          e.target.value = ''
        }}
      />

      {preview ? (
        <>
          <img src={preview} alt="Selected upload" className="absolute inset-0 size-full object-contain p-3" />
          {busy && (
            <div className="absolute inset-0 bg-canvas/40">
              <div className="absolute inset-x-0 h-24 -translate-y-full bg-gradient-to-b from-transparent via-brand-2/25 to-transparent animate-scan" />
              <div className="absolute inset-x-0 h-px bg-brand-2 shadow-[0_0_16px_2px_rgb(45_212_238/0.7)] animate-scan" />
              {['top-3 left-3 border-t-2 border-l-2', 'top-3 right-3 border-t-2 border-r-2', 'bottom-3 left-3 border-b-2 border-l-2', 'bottom-3 right-3 border-b-2 border-r-2'].map(
                (pos) => (
                  <span key={pos} className={cn('absolute size-6 border-brand-2/80 rounded-sm', pos)} />
                ),
              )}
            </div>
          )}
          {!busy && (
            <div className="absolute inset-0 grid place-items-center bg-canvas/70 opacity-0 group-hover:opacity-100 transition-opacity">
              <span className="inline-flex items-center gap-2 text-sm font-medium">
                <ImageUp className="size-4" /> Replace image
              </span>
            </div>
          )}
        </>
      ) : (
        <div className="absolute inset-0 grid place-items-center p-6 text-center">
          <div>
            <div className="relative mx-auto mb-5 size-16">
              <div className="absolute inset-0 rounded-2xl bg-brand-gradient opacity-25 blur-xl group-hover:opacity-40 transition-opacity" />
              <div className="relative grid place-items-center size-16 rounded-2xl border border-line-strong bg-surface-2 group-hover:-translate-y-0.5 transition-transform">
                <UploadCloud className="size-7 text-brand" />
              </div>
            </div>
            <p className="font-medium">{dragging ? 'Release to upload' : 'Drop an image here'}</p>
            <p className="text-sm text-muted mt-1.5">
              or <span className="text-brand">browse files</span> · paste with <Kbd>Ctrl</Kbd> <Kbd>V</Kbd>
            </p>
            <p className="text-xs text-subtle mt-4">JPG, PNG or WEBP · up to 25 MB</p>
          </div>
        </div>
      )}
    </div>
  )
}

function AnalyzingCard() {
  const [step, setStep] = useState(0)
  useEffect(() => {
    const timer = setInterval(() => setStep((s) => Math.min(s + 1, PIPELINE.length - 1)), 650)
    return () => clearInterval(timer)
  }, [])

  return (
    <Card className="p-6 animate-fade-up">
      <CardHeader icon={Brain} title="Running analysis" description="Models are warm, so this usually takes well under a second." />
      <ol className="space-y-2.5">
        {PIPELINE.map(({ icon: Icon, label }, i) => {
          const done = i < step
          const active = i === step
          return (
            <li
              key={label}
              className={cn(
                'flex items-center gap-3 rounded-xl border px-3.5 py-3 text-sm transition-all duration-300',
                active ? 'border-brand/30 bg-brand/[0.06]' : 'border-line',
                !done && !active && 'opacity-45',
              )}
            >
              <Icon className={cn('size-4', active ? 'text-brand' : done ? 'text-emerald-300' : 'text-muted')} />
              <span className="flex-1">{label}</span>
              {active && <Loader2 className="size-4 animate-spin text-brand" />}
              {done && <span className="text-xs text-emerald-300">done</span>}
            </li>
          )
        })}
      </ol>
    </Card>
  )
}

function HowItWorks() {
  return (
    <Card className="p-6 animate-fade-up">
      <CardHeader icon={Gauge} title="How it works" description="Every upload runs through the same pipeline a platform would call." />
      <ul className="space-y-1">
        {HOW_IT_WORKS.map(({ icon: Icon, title, body }, i) => (
          <li key={title} className="flex gap-4 rounded-xl p-3 hover:bg-white/[0.025] transition-colors">
            <span className="relative grid place-items-center size-9 shrink-0 rounded-xl border border-line bg-white/[0.03]">
              <Icon className="size-4 text-brand" />
              <span className="absolute -top-1.5 -right-1.5 grid place-items-center size-4 rounded-full bg-surface-2 border border-line text-[9px] font-mono text-muted">
                {i + 1}
              </span>
            </span>
            <div>
              <p className="text-sm font-medium">{title}</p>
              <p className="text-[13px] text-muted leading-relaxed mt-0.5">{body}</p>
            </div>
          </li>
        ))}
      </ul>
    </Card>
  )
}

function LineageBanner({ source }) {
  const status = source.manifest ? manifestStatus(source.manifest.status) : null
  const Icon = status?.icon || Repeat2
  const tone = status ? cn(status.ring, status.text) : 'border-sky-400/30 bg-sky-400/10 text-sky-300'
  const when = source.analyzedAt ? dateTime(source.analyzedAt) : 'an earlier date'

  let title
  let body
  if (source.via === 'MANIFEST') {
    title = `Provenance label found · ${status.label}`
    body = source.known
      ? `This image was labelled by analysis #${source.analysisId} on ${when} and routed to ${source.decision}. ${status.description}`
      : `It carries a label (${shortId(source.provenanceId)}) that this system has no record of. ${status.description}`
  } else {
    title = 'Seen before'
    body = `No label is embedded, but this matches analysis #${source.analysisId} from ${when} by ${MATCH_VIA[source.via].toLowerCase()}.`
  }

  return (
    <Card className="p-4 animate-fade-up">
      <div className="flex items-start gap-3">
        <span className={cn('grid place-items-center size-9 shrink-0 rounded-xl border', tone)}>
          <Icon className="size-4" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium">{title}</p>
          <p className="text-[13px] text-muted leading-relaxed mt-0.5">{body}</p>
        </div>
        {source.known && (
          <Link to={`/verify/${source.provenanceId}`} className={buttonClass('ghost', 'sm', 'shrink-0')}>
            History <ArrowRight className="size-3.5" />
          </Link>
        )}
      </div>
    </Card>
  )
}

function ProvenanceCard({ result, file }) {
  const download = () =>
    downloadBase64(result.labelledImage, result.labelledMimeType, labelledFilename(file?.name, result.labelledMimeType))

  return (
    <Card className="p-6 animate-fade-up [animation-delay:180ms]">
      <CardHeader
        icon={Stamp}
        title="Provenance label"
        description="A signed manifest carrying this ID is embedded in a copy of your image. The copy is returned to you and never stored."
      />
      <dl className="space-y-2.5">
        {[
          { icon: Stamp, label: 'Provenance ID', value: result.provenanceId },
          { icon: Fingerprint, label: 'Perceptual hash (stored instead of the image)', value: result.phash },
        ].map(({ icon: RowIcon, label, value }) => (
          <div key={label} className="flex items-center gap-3 rounded-xl border border-line bg-white/[0.02] px-3.5 py-2.5">
            <RowIcon className="size-4 text-muted shrink-0" />
            <div className="min-w-0 flex-1">
              <dt className="text-xs text-muted">{label}</dt>
              <dd className="font-mono text-sm truncate">{value || '—'}</dd>
            </div>
            {value && <CopyButton value={value} />}
          </div>
        ))}
      </dl>
      <div className="mt-4 flex flex-wrap gap-2">
        {result.labelledImage ? (
          <Button icon={Download} onClick={download} className="flex-1">
            Download labelled image
          </Button>
        ) : (
          <p className="flex-1 self-center text-xs text-subtle">Labelling was unavailable for this image.</p>
        )}
        {result.provenanceId && (
          <Link to={`/verify/${result.provenanceId}`} className={buttonClass('secondary', 'md', 'flex-1')}>
            <History className="size-4" /> Audit trail
          </Link>
        )}
      </div>
    </Card>
  )
}

function ResultView({ result, preview, file, thresholds }) {
  const meta = decisionMeta(result.decision)
  const Icon = meta.icon

  return (
    <div className="space-y-5">
      {result.embeddedSource && <LineageBanner source={result.embeddedSource} />}
      <Card className={cn('p-6 overflow-hidden animate-fade-up', meta.ring)}>
        <div className={cn('absolute inset-x-0 top-0 h-32 bg-gradient-to-b to-transparent pointer-events-none', meta.glow)} />
        <div className="relative flex items-start gap-4">
          <span className={cn('grid place-items-center size-12 shrink-0 rounded-2xl border', meta.ring)}>
            <Icon className={cn('size-6', meta.text)} />
          </span>
          <div className="flex-1 min-w-0">
            <p className="text-xs uppercase tracking-[0.16em] text-muted">Decision</p>
            <p className={cn('text-2xl font-semibold tracking-tight mt-0.5', meta.text)}>{meta.label}</p>
            <p className="text-sm text-muted mt-1.5 leading-relaxed">{meta.description}</p>
            <p className="text-xs text-subtle mt-3 font-mono">
              Analysis #{result.id} · {dateTime(result.createdAt)}
            </p>
          </div>
        </div>
        {result.decision === 'REVIEW' && (
          <Link to="/queue" className={buttonClass('secondary', 'md', 'relative mt-5 w-full')}>
            Open moderation queue <ArrowRight className="size-4" />
          </Link>
        )}
      </Card>

      <Card className="p-6 animate-fade-up [animation-delay:60ms]">
        <CardHeader icon={Gauge} title="Risk scores" description="Two independent models, compared against the live decision thresholds." />
        <div className="space-y-6">
          <ScoreMeter
            label="Forgery"
            icon={ScanFace}
            value={result.forgeryScore}
            review={thresholds.forgeryReview}
            autoFlag={thresholds.forgeryAutoFlag}
          />
          <ScoreMeter
            label="Explicit content"
            icon={EyeOff}
            value={result.explicitScore}
            review={thresholds.explicitReview}
            autoFlag={thresholds.explicitAutoFlag}
          />
        </div>
      </Card>

      {result.heatmap && (
        <Card className="p-6 animate-fade-up [animation-delay:120ms]">
          <CardHeader icon={Flame} title="Explainability" description="Grad-CAM highlights the regions that drove the forgery score." />
          <div className="grid grid-cols-2 gap-3">
            {[
              { src: preview, label: 'Original' },
              { src: `data:image/png;base64,${result.heatmap}`, label: 'Grad-CAM overlay' },
            ].map(({ src, label }) => (
              <figure key={label}>
                <div className="aspect-square rounded-xl overflow-hidden border border-line bg-checker">
                  <img src={src} alt={label} className="size-full object-cover" />
                </div>
                <figcaption className="text-xs text-muted mt-2 text-center">{label}</figcaption>
              </figure>
            ))}
          </div>
          <div className="flex items-center gap-3 mt-4 text-[11px] text-subtle">
            <span>Low</span>
            <span className="h-1.5 flex-1 rounded-full bg-[linear-gradient(90deg,#00008f,#0080ff,#00ffff,#ffff00,#ff8000,#ff0000)] opacity-80" />
            <span>High attention</span>
          </div>
        </Card>
      )}

      <ProvenanceCard result={result} file={file} />
    </div>
  )
}

export default function Demo() {
  const { thresholds, refresh: refreshStats } = useStats()
  const [file, setFile] = useState(null)
  const [preview, setPreview] = useState(null)
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const dims = useImageDimensions(preview)

  useEffect(() => () => preview && URL.revokeObjectURL(preview), [preview])

  const onFile = useCallback((f) => {
    if (!f) return
    if (!f.type.startsWith('image/')) {
      setError('That file is not an image. Choose a JPG, PNG or WEBP.')
      return
    }
    if (f.size > MAX_BYTES) {
      setError(`That image is ${fileSize(f.size)}. The limit is 25 MB.`)
      return
    }
    setError('')
    setResult(null)
    setFile(f)
    setPreview(URL.createObjectURL(f))
  }, [])

  useEffect(() => {
    const onPaste = (e) => {
      const item = [...(e.clipboardData?.items || [])].find((i) => i.type.startsWith('image/'))
      if (item) onFile(item.getAsFile())
    }
    window.addEventListener('paste', onPaste)
    return () => window.removeEventListener('paste', onPaste)
  }, [onFile])

  const clear = () => {
    setFile(null)
    setPreview(null)
    setResult(null)
    setError('')
  }

  const runAnalyze = async () => {
    if (!file) return
    setBusy(true)
    setError('')
    setResult(null)
    try {
      setResult(await analyzeImage(file))
      refreshStats()
    } catch (err) {
      setError(err.message || 'Analysis failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <PageHeader
        eyebrow="Media forensics"
        title="Detect forged and explicit media"
        description="Upload an image to score it for face manipulation and explicit content. The decision engine routes it to Allow, Review, or Auto-flag in real time."
      />

      <div className="grid lg:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)] gap-6 items-start">
        <Card className="p-5 md:p-6 animate-fade-up lg:sticky lg:top-24">
          <CardHeader
            icon={ImageUp}
            title="Source image"
            description="Use stills from research datasets such as FaceForensics++, DFDC or Celeb-DF."
          />
          <Dropzone preview={preview} busy={busy} onFile={onFile} />

          {file && (
            <div className="mt-4 flex items-center gap-3 rounded-xl border border-line bg-white/[0.02] px-3.5 py-2.5 animate-fade-up">
              <ImageUp className="size-4 text-muted shrink-0" />
              <div className="min-w-0 flex-1">
                <p className="text-sm truncate">{file.name}</p>
                <p className="text-xs text-subtle">
                  {fileSize(file.size)}
                  {dims && ` · ${dims.w}×${dims.h}`}
                </p>
              </div>
              <button
                type="button"
                onClick={clear}
                disabled={busy}
                aria-label="Remove image"
                className="grid place-items-center size-7 rounded-lg text-muted hover:text-fg hover:bg-white/[0.06] disabled:opacity-40 transition"
              >
                <X className="size-4" />
              </button>
            </div>
          )}

          <div className="mt-4 flex gap-2">
            <Button size="lg" icon={ScanSearch} loading={busy} disabled={!file} onClick={runAnalyze} className="flex-1">
              {busy ? 'Analyzing…' : result ? 'Analyze again' : 'Run analysis'}
            </Button>
            {result && !busy && (
              <Button size="lg" variant="secondary" icon={RotateCcw} onClick={clear} aria-label="Start over" />
            )}
          </div>
        </Card>

        <div className="space-y-5">
          {error && <Alert title="Something went wrong">{error}</Alert>}
          {busy ? (
            <AnalyzingCard />
          ) : result ? (
            <ResultView result={result} preview={preview} file={file} thresholds={thresholds} />
          ) : (
            <HowItWorks />
          )}
        </div>
      </div>
    </>
  )
}
