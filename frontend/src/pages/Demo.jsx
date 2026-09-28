import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import {
  ArrowRight,
  BookOpen,
  Brain,
  Download,
  Eye,
  EyeOff,
  FileBadge,
  FilePlus2,
  Film,
  Fingerprint,
  Flame,
  Gauge,
  Gavel,
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
import { Trans, useTranslation } from 'react-i18next'
import { analyzeMedia } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import ContentCredentials from '../components/ContentCredentials'
import FamilyChip from '../components/FamilyChip'
import FrameStrip from '../components/FrameStrip'
import PrescreenCard from '../components/PrescreenCard'
import PrivacyNote from '../components/PrivacyNote'
import SamplePicker from '../components/SamplePicker'
import { ErrorState, LoadingLine } from '../components/Status'
import { VerdictPanel } from '../components/VerdictBadge'
import { Button, Card, CardHeader, CopyButton, Kbd, PageHeader, ScoreMeter, buttonClass } from '../components/ui'
import { useStats } from '../context/StatsContext'
import { decisionMeta } from '../lib/decision'
import { verdictFor } from '../lib/verdict'
import { FAMILY } from '../lib/family'
import { cn, dateTime, fileSize } from '../lib/format'
import { aggregateLabel, isVideoFile, mediaProblem } from '../lib/media'
import { MATCH_VIA, downloadBase64, labelledFilename, manifestStatus, shortId } from '../lib/provenance'

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
    icon: Film,
    title: 'Video, frame by frame',
    body: 'Clips are sampled at a fixed rate (lowered for long clips so the samples span the whole clip) and every frame is scored in memory. The clip is routed once, on the top-frame average for forgery and the maximum for explicit content.',
  },
  {
    icon: FileBadge,
    title: 'Content Credentials',
    body: 'If the upload carries a C2PA manifest, its signer, generator and edit history are shown next to the scores.',
  },
  {
    icon: Lock,
    title: 'Privacy by design',
    body: 'Only hashes, the scores, and the decision are stored. The image or video itself is discarded.',
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

const VEIL = 'grayscale blur-xl scale-110'

/** Blurred and greyscale until the viewer chooses to see it: the upload may be explicit or an image of them. */
function ShowToggle({ shown, onToggle }) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-pressed={shown}
      aria-label="Show the media in colour"
      className="absolute top-3 right-3 z-10 inline-flex items-center gap-1.5 rounded-lg bg-canvas/80 px-2.5 py-1 text-xs backdrop-blur hover:bg-canvas focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/60"
    >
      {shown ? <EyeOff aria-hidden className="size-3.5" /> : <Eye aria-hidden className="size-3.5" />}
      {shown ? 'Blur' : 'Show'}
    </button>
  )
}

function Dropzone({ preview, video, busy, shown, onFile }) {
  const inputRef = useRef(null)
  const [dragging, setDragging] = useState(false)

  const open = () => inputRef.current?.click()

  return (
    <div
      role="button"
      tabIndex={0}
      aria-label="Upload an image or video"
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
        accept="image/*,video/mp4,video/webm,video/quicktime,video/x-matroska,.mp4,.webm,.mov,.mkv,.m4v,.avi"
        className="hidden"
        onChange={(e) => {
          onFile(e.target.files?.[0])
          e.target.value = ''
        }}
      />

      {preview ? (
        <>
          {video ? (
            <video
              src={preview}
              muted
              playsInline
              controls={!busy}
              onClick={(e) => e.stopPropagation()}
              aria-label="Selected video"
              className={cn('absolute inset-0 size-full object-contain p-3 transition', !shown && VEIL)}
            />
          ) : (
            <img src={preview} alt="Selected upload" className={cn('absolute inset-0 size-full object-contain p-3 transition', !shown && VEIL)} />
          )}
          {busy && (
            <div className="absolute inset-0 bg-canvas/40">
              <div className="absolute inset-x-0 h-24 -translate-y-full bg-gradient-to-b from-transparent via-brand-2/25 to-transparent animate-scan" />
              <div className="absolute inset-x-0 h-px bg-brand-2 shadow-[0_0_16px_2px_rgb(239_68_68/0.7)] animate-scan" />
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
                <ImageUp className="size-4" /> Replace file
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
            <p className="font-medium">{dragging ? 'Release to upload' : 'Drop an image or video here'}</p>
            <p className="text-sm text-muted mt-1.5">
              or <span className="text-brand">browse files</span> · paste with <Kbd>Ctrl</Kbd> <Kbd>V</Kbd>
            </p>
            <p className="text-xs text-subtle mt-4">JPG, PNG or WEBP up to 25 MB · MP4, WEBM or MOV up to 50 MB</p>
          </div>
        </div>
      )}
    </div>
  )
}

function AnalyzingCard() {
  const { t } = useTranslation()
  const [step, setStep] = useState(0)
  useEffect(() => {
    const timer = setInterval(() => setStep((s) => Math.min(s + 1, PIPELINE.length - 1)), 650)
    return () => clearInterval(timer)
  }, [])

  return (
    <Card className="p-6 animate-fade-up">
      <CardHeader icon={Brain} title="Running analysis" description="Models are warm, so this usually takes well under a second." />
      <LoadingLine label={t('loading.checking')} className="mb-4" />
      <ol aria-hidden className="space-y-2.5">
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
        description={
          result.labelledImage
            ? 'A signed manifest carrying this ID is embedded in a copy of your image. The copy is returned to you and never stored.'
            : 'This ID and the perceptual hash identify the upload in the public audit trail. The upload itself is never stored.'
        }
      />
      <dl className="space-y-2.5">
        {[
          { icon: Stamp, label: 'Provenance ID', value: result.provenanceId },
          { icon: Fingerprint, label: `Perceptual hash (stored instead of the ${result.mediaType === 'VIDEO' ? 'video' : 'image'})`, value: result.phash },
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
      {result.labelledImage && (
        <p className="mt-3 text-xs text-muted">
          {result.labelledContentCredentials
            ? `The copy also carries a C2PA manifest signed by ${
                result.labelledContentCredentials.signer?.common_name || 'this service'
              }${result.labelledContentCredentials.status === 'TRUSTED' ? '' : ' (a development certificate, not on any trust list)'}.`
            : 'The copy carries the signed XMP label only; C2PA signing is not configured.'}
        </p>
      )}
      <div className="mt-4 flex flex-wrap gap-2">
        {result.labelledImage ? (
          <Button icon={Download} onClick={download} className="flex-1">
            Download labelled image
          </Button>
        ) : (
          <p className="flex-1 self-center text-xs text-subtle">
            {result.mediaType === 'VIDEO'
              ? 'Labelled copies are produced for images only; the video was scored and discarded.'
              : 'Labelling was unavailable for this image.'}
          </p>
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

function DetailSection({ icon: Icon, title, description, children }) {
  return (
    <section className="rounded-xl border border-line bg-white/[0.015] p-5">
      <h3 className="flex items-center gap-2 text-sm font-semibold tracking-tight">
        <Icon aria-hidden className="size-4 text-brand" />
        {title}
      </h3>
      {description && <p className="text-xs text-muted mt-1 mb-4">{description}</p>}
      {children}
    </section>
  )
}

/** Phase 23: the uploader's statement of reasons reference (EU DSA Art. 17/20 style). The PIN is shown once. */
function NoticeCard({ notice, decision }) {
  const { t } = useTranslation()
  return (
    <Card className="p-6 animate-fade-up [animation-delay:120ms]">
      <CardHeader icon={Gavel} title={t('notice.title')} description={t(notice.appealable ? 'notice.appealable' : 'notice.notAppealable')} />
      <dl className="grid sm:grid-cols-2 gap-2.5">
        {[
          { label: t('notice.number'), value: notice.noticeNumber },
          { label: t('notice.pin'), value: notice.pin },
        ].map(({ label, value }) => (
          <div key={label} className="flex items-center gap-3 rounded-xl border border-line bg-white/[0.02] px-3.5 py-2.5">
            <div className="min-w-0 flex-1">
              <dt className="text-xs text-muted">{label}</dt>
              <dd className="font-mono text-sm truncate">{value}</dd>
            </div>
            <CopyButton value={value} />
          </div>
        ))}
      </dl>
      <p className="text-xs text-subtle mt-3">
        <Trans i18nKey="notice.keep" components={{ b: <strong className="text-muted font-semibold" /> }} />
      </p>
      {notice.appealable && decision === 'AUTO_FLAG' && (
        <Link to={`/appeal?n=${encodeURIComponent(notice.noticeNumber)}`} className={buttonClass('secondary', 'md', 'w-full mt-4')}>
          <Gavel aria-hidden className="size-4" /> {t('notice.appeal')}
        </Link>
      )}
    </Card>
  )
}

function ResultView({ result, preview, file, thresholds, shown }) {
  const { t } = useTranslation()
  const { isAuthenticated } = useAuth()
  const decision = decisionMeta(result.decision)
  const video = result.mediaType === 'VIDEO' ? result.video : null

  return (
    <div className="space-y-5">
      {result.embeddedSource && <LineageBanner source={result.embeddedSource} />}
      <VerdictPanel
        verdict={verdictFor(result.forgeryScore, thresholds)}
        next={result.decision}
        meta={`Analysis #${result.id} · ${dateTime(result.createdAt)} · routed to ${decision.label}`}
      >
        <DetailSection icon={Gauge} title="Risk scores" description="Two independent models, compared against the live decision thresholds.">
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
          {result.generationFamily && (
            <div className="mt-6 flex items-start gap-3 rounded-xl border border-line bg-white/[0.02] px-3.5 py-3">
              <div className="min-w-0 flex-1">
                <p className="text-xs text-muted">Generation family</p>
                <p className="text-[13px] text-muted leading-relaxed mt-1">
                  {FAMILY[result.generationFamily]?.description || FAMILY.unknown.description} Best-effort attribution,
                  not a verdict.
                </p>
              </div>
              <FamilyChip family={result.generationFamily} confidence={result.familyConfidence} className="shrink-0 mt-0.5" />
            </div>
          )}
        </DetailSection>

        {video && (
          <DetailSection
            icon={Film}
            title="Frame timeline"
            description={`${video.sampled} frames sampled at ${video.fps} fps${
              video.durationSeconds ? ` from a ${video.durationSeconds.toFixed(1)} s clip` : ''
            }${video.truncated ? ' (clip longer than the sampling cap; later frames were not scored)' : ''}. Each frame was scored in memory and discarded.`}
          >
            <FrameStrip video={video} thresholds={thresholds} />
            <p className="text-xs text-muted mt-3">
              Routed on forgery = {aggregateLabel(video.routing?.forgery, video.k)}, explicit ={' '}
              {aggregateLabel(video.routing?.explicit, video.k)}.
              {video.peak && ` Peak frame #${video.peak.index} at ${video.peak.t}s.`}
            </p>
          </DetailSection>
        )}

        {result.heatmap && (
          <DetailSection
            icon={Flame}
            title="Explainability"
            description={
              video
                ? 'Grad-CAM on the peak frame highlights the regions that drove its forgery score.'
                : 'Grad-CAM highlights the regions that drove the forgery score.'
            }
          >
            <div className={cn('grid gap-3', video ? 'grid-cols-1 max-w-xs mx-auto' : 'grid-cols-2')}>
              {[
                ...(video ? [] : [{ src: preview, label: 'Original' }]),
                {
                  src: `data:image/png;base64,${result.heatmap}`,
                  label: video ? `Grad-CAM, peak frame${video.peak ? ` (${video.peak.t}s)` : ''}` : 'Grad-CAM overlay',
                },
              ].map(({ src, label }) => (
                <figure key={label}>
                  <div className="aspect-square rounded-xl overflow-hidden border border-line bg-checker">
                    <img src={src} alt={label} className={cn('size-full object-cover transition', !shown && VEIL)} />
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
          </DetailSection>
        )}

        <DetailSection icon={FileBadge} title="Content Credentials (C2PA)" description="Read from the upload's embedded C2PA manifest, if any.">
          <ContentCredentials credentials={result.contentCredentials} />
        </DetailSection>
      </VerdictPanel>

      {result.notice && <NoticeCard notice={result.notice} decision={result.decision} />}

      {isAuthenticated ? (
        result.decision === 'REVIEW' && (
          <Link to="/queue" className={buttonClass('secondary', 'md', 'w-full')}>
            Open moderation queue <ArrowRight aria-hidden className="size-4" />
          </Link>
        )
      ) : (
        <Card className="p-4 flex flex-wrap items-center gap-3 animate-fade-up">
          <FilePlus2 aria-hidden className="size-5 text-rose-300 shrink-0" />
          <p className="text-sm flex-1 min-w-48">{t('verdict.reportCta')}</p>
          <Link to="/report" className={buttonClass('secondary', 'sm')}>
            {t('verdict.reportAction')} <ArrowRight aria-hidden className="size-3.5" />
          </Link>
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
  const [shown, setShown] = useState(false)
  const video = isVideoFile(file)
  const dims = useImageDimensions(video ? null : preview)
  const { hash } = useLocation()

  useEffect(() => () => preview && URL.revokeObjectURL(preview), [preview])

  useEffect(() => {
    if (hash) document.getElementById(hash.slice(1))?.scrollIntoView({ behavior: 'smooth' })
  }, [hash])

  const onFile = useCallback((f) => {
    if (!f) return
    const problem = mediaProblem(f, fileSize)
    if (problem) {
      setError(problem)
      return
    }
    setError('')
    setResult(null)
    setShown(false)
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
      setResult(await analyzeMedia(file))
      refreshStats()
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <PageHeader
        eyebrow="Media forensics"
        title="Detect forged and explicit media"
        description="Upload an image or short video to score it for face manipulation and explicit content. The decision engine routes it to Allow, Review, or Auto-flag in real time."
        actions={
          <>
            <Link to="/report" className={buttonClass('primary', 'md')}>
              <FilePlus2 aria-hidden className="size-4" />
              Report an image of you
            </Link>
            <Link to="/explained" className={buttonClass('secondary', 'md')}>
              <BookOpen aria-hidden className="size-4" />
              New here? Plain-English guide
            </Link>
          </>
        }
      />

      <div className="grid lg:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)] gap-6 items-start">
        <Card className="p-5 md:p-6 animate-fade-up lg:sticky lg:top-24">
          <CardHeader
            icon={ImageUp}
            title="Source media"
            description={
              <>
                Use stills or clips from research datasets such as FaceForensics++, DFDC or Celeb-DF, or the synthetic{' '}
                <a href="/sample-clip.mp4" download className="text-brand hover:underline">
                  sample clip
                </a>{' '}
                and{' '}
                <a href="/c2pa-sample.jpg" download className="text-brand hover:underline">
                  C2PA-signed sample
                </a>
                .
              </>
            }
          />
          {!file && <PrivacyNote compact className="mb-4" />}
          <div className="relative">
            <Dropzone preview={preview} video={video} busy={busy} shown={shown} onFile={onFile} />
            {preview && !busy && <ShowToggle shown={shown} onToggle={() => setShown((s) => !s)} />}
          </div>
          {!file && <SamplePicker onPick={onFile} className="mt-4" />}

          {file && (
            <div className="mt-4 flex items-center gap-3 rounded-xl border border-line bg-white/[0.02] px-3.5 py-2.5 animate-fade-up">
              {video ? <Film className="size-4 text-muted shrink-0" /> : <ImageUp className="size-4 text-muted shrink-0" />}
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
                aria-label="Remove file"
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
          <ErrorState error={error} onRetry={file && typeof error !== 'string' ? runAnalyze : undefined} />
          {busy ? (
            <AnalyzingCard />
          ) : result ? (
            <ResultView result={result} preview={preview} file={file} thresholds={thresholds} shown={shown} />
          ) : (
            <HowItWorks />
          )}
        </div>
      </div>

      <div id="prescreen" className="mt-8 scroll-mt-24">
        <PrescreenCard />
      </div>
    </>
  )
}
