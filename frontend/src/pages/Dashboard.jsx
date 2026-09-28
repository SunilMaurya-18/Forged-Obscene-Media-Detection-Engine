import { useEffect, useState } from 'react'
import {
  Activity,
  Database,
  Download,
  Gauge,
  Globe2,
  Info,
  LineChart,
  Maximize2,
  Scale,
  ShieldCheck,
  Sparkles,
  Target,
  Timer,
  X,
} from 'lucide-react'
import { Alert, Card, CardHeader, PageHeader, StatTile, buttonClass } from '../components/ui'
import { useStats } from '../context/StatsContext'
import { DECISION_ORDER, decisionMeta } from '../lib/decision'
import { cn, pct, score } from '../lib/format'

const IN_DOMAIN = 'faceforensics++'
const DATASET_LABELS = { 'faceforensics++': 'FaceForensics++', dfdc: 'DFDC', 'celeb-df': 'Celeb-DF' }

const CHARTS = [
  { src: '/generalization_chart.png', title: 'Generalization', caption: 'Accuracy, F1 and AUC: trained on FF++, tested on held-out datasets' },
  { src: '/roc_curve.png', title: 'ROC curves', caption: 'True vs false positive rate per dataset' },
  { src: '/diffusion_robustness.png', title: 'Diffusion-era robustness', caption: 'AUC before and after diffusion-augmented retraining', wide: true },
  { src: '/confusion_matrix.png', title: 'Confusion matrices', caption: 'Predictions at a 0.5 forgery threshold', wide: true },
  { src: '/reliability_diagram.png', title: 'Reliability diagram', caption: 'Predicted P(fake) vs observed fraction fake, before and after temperature scaling' },
  { src: '/robustness_chart.png', title: 'Robustness', caption: 'Decision flip rate and score shift under JPEG, resize, blur and transcode' },
]

const TRANSFORM_LABELS = {
  jpeg_q90: 'JPEG q90',
  jpeg_q70: 'JPEG q70',
  jpeg_q50: 'JPEG q50',
  'rescale_0.5': 'Downscale 0.5×',
  'center_crop_0.8': 'Centre crop 80%',
  'blur_r1.5': 'Blur r1.5',
  screenshot: 'Screenshot',
  social_transcode: 'Social transcode',
}

function StatusPill({ tone, children }) {
  const tones = {
    ok: 'border-emerald-400/30 text-emerald-300',
    warn: 'border-amber-400/30 text-amber-300',
    bad: 'border-rose-400/30 text-rose-300',
  }
  return (
    <span className={cn('inline-flex items-center rounded-full border px-2.5 py-1 text-[11px] whitespace-nowrap', tones[tone])}>
      {children}
    </span>
  )
}

function Calibration({ data }) {
  return (
    <div className="rounded-xl border border-line bg-white/[0.02] p-4">
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm font-medium">Calibration</p>
        {data.degenerate ? <StatusPill tone="warn">Not fitted</StatusPill> : <StatusPill tone="ok">T = {data.temperature}</StatusPill>}
      </div>
      <dl className="grid grid-cols-2 gap-2 mt-3 text-xs">
        <div>
          <dt className="text-subtle">ECE before → after</dt>
          <dd className="font-mono tabular-nums mt-0.5">
            {score(data.ece_before)} → {score(data.ece_after)}
          </dd>
        </div>
        <div>
          <dt className="text-subtle">Brier before → after</dt>
          <dd className="font-mono tabular-nums mt-0.5">
            {score(data.brier_before)} → {score(data.brier_after)}
          </dd>
        </div>
      </dl>
      <p className="text-[11px] text-muted mt-3 leading-relaxed">
        {data.degenerate
          ? 'The validation set is perfectly separable, so no temperature is meaningful. Scores are a ranking, not probabilities, until calibrated on real data.'
          : `Fitted on ${data.n_fit} validation images; ${data.promoted ? 'active in the ML service.' : 'not yet promoted to the ML service.'}`}
      </p>
    </div>
  )
}

function Robustness({ data }) {
  const rows = Object.entries(data.transforms || {})
  return (
    <div className="rounded-xl border border-line bg-white/[0.02] p-4">
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm font-medium">Robustness</p>
        <StatusPill tone={rows.some(([, t]) => t.flip_rate > 0.05) ? 'bad' : 'ok'}>
          Worst: {TRANSFORM_LABELS[data.worst_transform] || data.worst_transform || '—'}
        </StatusPill>
      </div>
      <ul className="mt-3 space-y-1.5">
        {rows.map(([name, t]) => (
          <li key={name} className="grid grid-cols-[7.5rem_1fr_3rem] items-center gap-2 text-xs">
            <span className="text-muted truncate">{TRANSFORM_LABELS[name] || name}</span>
            <span className="h-1.5 rounded-full bg-white/[0.06] overflow-hidden" aria-hidden="true">
              <span
                className={cn('block h-full rounded-full', t.flip_rate > 0.05 ? 'bg-rose-400' : 'bg-emerald-400')}
                style={{ width: pct(t.flip_rate) }}
              />
            </span>
            <span className="font-mono tabular-nums text-right">{pct(t.flip_rate, 0)}</span>
          </li>
        ))}
      </ul>
      <p className="text-[11px] text-muted mt-3 leading-relaxed">
        Share of {data.n} held-out images whose routing flips from re-encoding alone. Target: at most 5% on social transcode.
      </p>
    </div>
  )
}

function Fairness({ data }) {
  const attrs = Object.entries(data.attributes || {})
  return (
    <div className="rounded-xl border border-line bg-white/[0.02] p-4">
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm font-medium">Fairness</p>
        {data.status === 'ok' ? <StatusPill tone="ok">Audited</StatusPill> : <StatusPill tone="warn">Not run</StatusPill>}
      </div>
      {data.status === 'ok' ? (
        <ul className="mt-3 space-y-2 text-xs">
          {attrs.map(([attr, a]) => (
            <li key={attr} className="flex items-center justify-between gap-2">
              <span className="text-muted">{attr.replace(/_/g, ' ')}</span>
              <span className="font-mono tabular-nums">
                {a.disparity ? `FPR gap ${score(a.disparity.fpr.max_minus_min)}` : 'groups too small'}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-[11px] text-muted mt-3 leading-relaxed">{data.reason}</p>
      )}
      <p className="text-[11px] text-subtle mt-3">Groups under {data.min_group_size} images are never reported.</p>
    </div>
  )
}

function ModeratorAgreement({ moderation }) {
  const m = moderation || {}
  const cells = [
    { label: 'Human-reviewed', value: m.reviewed ?? '–', hint: 'Items a moderator decided' },
    { label: 'REVIEW confirmed', value: m.review?.confirmRate != null ? pct(m.review.confirmRate, 0) : '—', hint: `${m.review?.confirmed ?? 0} confirmed · ${m.review?.cleared ?? 0} cleared` },
    { label: 'AUTO_FLAG overturned', value: m.autoFlag?.overturnRate != null ? pct(m.autoFlag.overturnRate, 0) : '—', hint: `${m.autoFlag?.overturned ?? 0} of ${(m.autoFlag?.overturned ?? 0) + (m.autoFlag?.upheld ?? 0)}` },
    { label: 'Precision proxy', value: m.precisionProxy != null ? pct(m.precisionProxy, 0) : '—', hint: 'Model-flagged items a human confirmed' },
  ]
  return (
    <div className="rounded-xl border border-line bg-white/[0.02] p-4">
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm font-medium">Moderator agreement</p>
        <span className="inline-flex items-center gap-1.5 text-[11px] text-emerald-300">
          <span className="size-1.5 rounded-full bg-emerald-400 animate-pulse-soft" /> Live
        </span>
      </div>
      <dl className="grid grid-cols-2 gap-3 mt-3">
        {cells.map((c) => (
          <div key={c.label}>
            <dt className="text-[11px] text-subtle">{c.label}</dt>
            <dd className="text-lg font-semibold tabular-nums">{c.value}</dd>
            <dd className="text-[11px] text-muted">{c.hint}</dd>
          </div>
        ))}
      </dl>
      <p className="text-[11px] text-muted mt-3 leading-relaxed">
        Real moderation traffic, not a benchmark: how often humans agree with the model's routing.
      </p>
    </div>
  )
}

function Credibility({ metrics, stats }) {
  return (
    <Card className="p-6">
      <CardHeader
        icon={Scale}
        title="Credibility checks"
        description="Is a score a probability, does it survive re-encoding, is it fair across groups, and do moderators agree?"
      />
      <div className="grid md:grid-cols-2 xl:grid-cols-4 gap-4">
        {metrics.calibration && <Calibration data={metrics.calibration} />}
        {metrics.robustness && <Robustness data={metrics.robustness} />}
        {metrics.fairness && <Fairness data={metrics.fairness} />}
        <ModeratorAgreement moderation={stats?.moderation} />
      </div>
      {metrics.calibration?.notes && (
        <p className="flex gap-2.5 mt-4 text-xs text-muted leading-relaxed">
          <Info className="size-4 shrink-0 text-amber-300 mt-px" />
          {metrics.calibration.notes} See docs/MODEL_CARD.md.
        </p>
      )}
    </Card>
  )
}

function EraRow({ label, sub, before, after }) {
  const delta = before?.auc != null && after?.auc != null ? after.auc - before.auc : null
  return (
    <tr className="border-b border-line last:border-0">
      <td className="py-3.5 pr-4">
        <p className="font-medium">{label}</p>
        <p className="text-[11px] text-muted mt-0.5">{sub}</p>
      </td>
      {['accuracy', 'precision', 'recall', 'auc'].map((k) => (
        <td key={k} className="py-3.5 px-2 text-right font-mono text-xs tabular-nums">
          <span className="text-subtle">{score(before?.[k])}</span>
          <span className="text-subtle mx-1">→</span>
          <span className="text-fg">{score(after?.[k])}</span>
        </td>
      ))}
      <td
        className={cn(
          'py-3.5 pl-2 text-right font-mono text-xs tabular-nums',
          delta == null ? 'text-subtle' : delta < -0.02 ? 'text-rose-300' : delta > 0.02 ? 'text-emerald-300' : 'text-muted',
        )}
      >
        {delta == null ? '—' : `${delta >= 0 ? '+' : '−'}${Math.abs(delta).toFixed(3)}`}
      </td>
    </tr>
  )
}

function DiffusionEra({ gan, diffusion, notes }) {
  return (
    <Card className="p-6 overflow-hidden">
      <CardHeader
        icon={Sparkles}
        title="GAN-era vs diffusion-era"
        description="The Phase 2 checkpoint (baseline) against the diffusion-augmented retrain, on held-out identities."
        action={
          <span
            className={cn(
              'inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[11px]',
              gan.within_margin ? 'border-emerald-400/30 text-emerald-300' : 'border-rose-400/30 text-rose-300',
            )}
          >
            {gan.within_margin ? 'No GAN-era regression' : 'GAN-era regression'} (±{gan.allowed_regression})
          </span>
        }
      />
      <div className="overflow-x-auto -mx-6 px-6">
        <table className="w-full text-sm min-w-[640px]">
          <thead>
            <tr className="text-left text-xs text-subtle border-b border-line">
              <th className="font-medium py-3 pr-4">Era</th>
              {['Accuracy', 'Precision', 'Recall', 'AUC', 'Δ AUC'].map((h) => (
                <th key={h} className="font-medium py-3 px-2 text-right">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            <EraRow
              label="GAN-era"
              sub={`FF++ · DFDC · Celeb-DF pooled · n=${gan.retrained?.n ?? '–'}`}
              before={gan.baseline}
              after={gan.retrained}
            />
            <EraRow
              label="Diffusion-era"
              sub={`${diffusion.dataset} · n=${diffusion.retrained?.n ?? '–'}`}
              before={diffusion.baseline}
              after={diffusion.retrained}
            />
          </tbody>
        </table>
      </div>
      <p className="flex gap-2.5 mt-4 text-xs text-muted leading-relaxed">
        <Info className="size-4 shrink-0 text-amber-300 mt-px" />
        {notes}
      </p>
    </Card>
  )
}

function Lightbox({ chart, onClose }) {
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={chart.title}
      onClick={onClose}
      className="fixed inset-0 z-50 grid place-items-center bg-black/80 backdrop-blur-sm p-4 animate-fade-up"
    >
      <div className="relative max-w-5xl w-full" onClick={(e) => e.stopPropagation()}>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close"
          className="absolute -top-11 right-0 grid place-items-center size-9 rounded-xl border border-line-strong bg-surface-2 text-muted hover:text-fg transition"
        >
          <X className="size-4" />
        </button>
        <img src={chart.src} alt={chart.title} className="w-full rounded-2xl bg-[#fff] p-3" />
        <p className="text-sm text-muted text-center mt-3">{chart.caption}</p>
      </div>
    </div>
  )
}

function LiveDecisions({ stats }) {
  const total = stats?.total ?? 0
  const counts = stats?.byDecision || {}
  return (
    <Card className="p-6 lg:col-span-2">
      <CardHeader
        icon={Activity}
        title="Live decisions"
        description="Everything routed by the running API since the database was created."
        action={
          <span className="inline-flex items-center gap-1.5 text-[11px] text-emerald-300">
            <span className="size-1.5 rounded-full bg-emerald-400 animate-pulse-soft" /> Live
          </span>
        }
      />
      <div className="flex items-end gap-3 mb-5">
        <p className="text-5xl font-semibold tracking-tight tabular-nums">{stats ? total : '–'}</p>
        <p className="text-sm text-muted pb-1.5">analyses</p>
      </div>

      <div className="flex h-3 rounded-full overflow-hidden bg-white/[0.06] gap-0.5">
        {total > 0 &&
          DECISION_ORDER.map((d) =>
            counts[d] ? (
              <div
                key={d}
                className={cn('h-full transition-all duration-700', decisionMeta(d).bar)}
                style={{ width: `${(counts[d] / total) * 100}%` }}
              />
            ) : null,
          )}
      </div>

      <div className="grid grid-cols-3 gap-3 mt-5">
        {DECISION_ORDER.map((d) => {
          const meta = decisionMeta(d)
          const n = counts[d] ?? 0
          return (
            <div key={d} className="rounded-xl border border-line bg-white/[0.02] px-3.5 py-3">
              <p className="flex items-center gap-2 text-xs text-muted">
                <span className={cn('size-2 rounded-full', meta.dot)} />
                {meta.label}
              </p>
              <p className="text-xl font-semibold tabular-nums mt-1">{n}</p>
              <p className="text-[11px] text-subtle">{total ? pct(n / total, 0) : '0%'}</p>
            </div>
          )
        })}
      </div>
    </Card>
  )
}

function Routing({ stats, thresholds }) {
  const rows = [
    { label: 'Forgery', avg: stats?.avgForgeryScore, review: thresholds.forgeryReview, flag: thresholds.forgeryAutoFlag },
    { label: 'Explicit', avg: stats?.avgExplicitScore, review: thresholds.explicitReview, flag: thresholds.explicitAutoFlag },
  ]
  return (
    <Card className="p-6">
      <CardHeader icon={Gauge} title="Routing thresholds" description="Active decision-engine configuration" />
      <div className="space-y-4">
        {rows.map((r) => (
          <div key={r.label} className="rounded-xl border border-line bg-white/[0.02] p-4">
            <div className="flex items-center justify-between">
              <p className="text-sm font-medium">{r.label}</p>
              <p className="text-xs text-muted">
                avg <span className="font-mono text-fg">{score(r.avg)}</span>
              </p>
            </div>
            <div className="grid grid-cols-2 gap-2 mt-3 text-xs">
              <p className="rounded-lg bg-amber-400/[0.08] text-amber-200 px-2.5 py-1.5">
                Review ≥ <span className="font-mono">{r.review}</span>
              </p>
              <p className="rounded-lg bg-rose-400/[0.08] text-rose-200 px-2.5 py-1.5">
                Flag ≥ <span className="font-mono">{r.flag}</span>
              </p>
            </div>
          </div>
        ))}
      </div>
    </Card>
  )
}

function DatasetTable({ datasets, gaps }) {
  const names = Object.keys(datasets)
  return (
    <Card className="p-6 overflow-hidden">
      <CardHeader
        icon={Database}
        title="Per-dataset performance"
        description="Trained on FaceForensics++ only. DFDC and Celeb-DF are never seen in training."
      />
      <div className="overflow-x-auto -mx-6 px-6">
        <table className="w-full text-sm min-w-[640px]">
          <thead>
            <tr className="text-left text-xs text-subtle border-b border-line">
              <th className="font-medium py-3 pr-4">Dataset</th>
              <th className="font-medium py-3 pr-4 w-[30%]">Accuracy</th>
              {['Precision', 'Recall', 'F1', 'AUC', 'Δ AUC', 'n'].map((h) => (
                <th key={h} className="font-medium py-3 px-2 text-right">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {names.map((name) => {
              const m = datasets[name]
              const inDomain = name === IN_DOMAIN
              const gap = gaps?.[name]?.auc_gap
              return (
                <tr key={name} className="border-b border-line last:border-0 hover:bg-white/[0.015]">
                  <td className="py-4 pr-4">
                    <p className="font-medium">{DATASET_LABELS[name] || name}</p>
                    <p className={cn('text-[11px] mt-0.5', inDomain ? 'text-brand' : 'text-muted')}>
                      {inDomain ? 'In-domain test' : 'Held out'}
                    </p>
                  </td>
                  <td className="py-4 pr-4">
                    <div className="flex items-center gap-3">
                      <div className="h-1.5 flex-1 rounded-full bg-white/[0.06] overflow-hidden">
                        <div className="h-full rounded-full bg-brand-gradient" style={{ width: pct(m.accuracy) }} />
                      </div>
                      <span className="font-mono text-xs tabular-nums w-12 text-right">{pct(m.accuracy)}</span>
                    </div>
                  </td>
                  {[m.precision, m.recall, m.f1, m.auc].map((v, i) => (
                    <td key={i} className="py-4 px-2 text-right font-mono text-xs tabular-nums text-muted">
                      {score(v)}
                    </td>
                  ))}
                  <td
                    className={cn(
                      'py-4 px-2 text-right font-mono text-xs tabular-nums',
                      inDomain || gap == null ? 'text-subtle' : gap > 0.02 ? 'text-rose-300' : 'text-emerald-300',
                    )}
                  >
                    {inDomain || gap == null ? '—' : `${gap > 0 ? '−' : gap < 0 ? '+' : ''}${Math.abs(gap).toFixed(3)}`}
                  </td>
                  <td className="py-4 pl-2 text-right font-mono text-xs tabular-nums text-subtle">{m.n}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </Card>
  )
}

export default function Dashboard() {
  const { stats, thresholds } = useStats()
  const [metrics, setMetrics] = useState(null)
  const [error, setError] = useState('')
  const [zoom, setZoom] = useState(null)

  useEffect(() => {
    fetch('/metrics.json')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(r.statusText))))
      .then(setMetrics)
      .catch(() => setError('Could not load evaluation metrics (metrics.json).'))
  }, [])

  const datasets = metrics?.datasets || {}
  const heldOut = Object.entries(datasets).filter(([n]) => n !== IN_DOMAIN)
  const heldOutAcc = heldOut.length ? heldOut.reduce((s, [, m]) => s + m.accuracy, 0) / heldOut.length : null
  const latency = metrics?.latency
  const fpr = metrics?.false_positive_rate
  const loading = !metrics && !error

  return (
    <>
      <PageHeader
        eyebrow="Evaluation"
        title="Model performance"
        description="Offline cross-dataset evaluation of the forgery detector, alongside live routing from the running API."
        actions={
          <a href="/poster.png" download="forged-media-results-poster.png" className={buttonClass('secondary')}>
            <Download className="size-4" /> Results poster
          </a>
        }
      />

      {error && (
        <div className="mb-6">
          <Alert>{error}</Alert>
        </div>
      )}

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6 animate-fade-up">
        <StatTile
          icon={Target}
          label="In-domain accuracy"
          value={pct(datasets[IN_DOMAIN]?.accuracy)}
          hint={`FaceForensics++ test · n=${datasets[IN_DOMAIN]?.n ?? '–'}`}
          loading={loading}
        />
        <StatTile
          icon={Globe2}
          label="Cross-dataset accuracy"
          value={pct(heldOutAcc)}
          hint="Mean of DFDC and Celeb-DF"
          accent="text-brand-2"
          loading={loading}
        />
        <StatTile
          icon={Timer}
          label="API latency (p50)"
          value={latency ? `${Math.round(latency.p50_ms)} ms` : '—'}
          hint={latency ? `p95 ${Math.round(latency.p95_ms)} ms · POST /api/analyze` : 'Not measured'}
          accent="text-amber-300"
          loading={loading}
        />
        <StatTile
          icon={ShieldCheck}
          label="Clean-set false positives"
          value={pct(fpr?.fpr)}
          hint={fpr ? `${fpr.false_positives}/${fpr.n_clean} unaltered images flagged` : 'Not measured'}
          accent="text-emerald-300"
          loading={loading}
        />
      </div>

      <div className="grid lg:grid-cols-3 gap-6 mb-6 animate-fade-up [animation-delay:60ms]">
        <LiveDecisions stats={stats} />
        <Routing stats={stats} thresholds={thresholds} />
      </div>

      {metrics && (
        <div className="space-y-6 animate-fade-up [animation-delay:120ms]">
          <DatasetTable datasets={datasets} gaps={metrics.generalization_gap} />
          {metrics.gan_era && metrics.diffusion_era && (
            <DiffusionEra gan={metrics.gan_era} diffusion={metrics.diffusion_era} notes={metrics.phase17_notes} />
          )}
          <Credibility metrics={metrics} stats={stats} />

          <div>
            <div className="flex items-center gap-2 mb-4">
              <LineChart className="size-4 text-brand" />
              <h2 className="text-[15px] font-semibold tracking-tight">Evaluation charts</h2>
            </div>
            <div className="grid md:grid-cols-2 gap-6">
              {CHARTS.map((chart) => (
                <button
                  key={chart.src}
                  type="button"
                  onClick={() => setZoom(chart)}
                  className={cn('card group p-4 text-left transition hover:border-line-strong', chart.wide && 'md:col-span-2')}
                >
                  <div className={cn('relative rounded-xl bg-[#fff] p-2 overflow-hidden', !chart.wide && 'h-64 md:h-80')}>
                    <img
                      src={chart.src}
                      alt={chart.title}
                      loading="lazy"
                      className={chart.wide ? 'w-full' : 'size-full object-contain'}
                    />
                    <span className="absolute top-3 right-3 grid place-items-center size-8 rounded-lg bg-black/60 text-[#fff] opacity-0 group-hover:opacity-100 transition">
                      <Maximize2 className="size-4" />
                    </span>
                  </div>
                  <p className="text-sm font-medium mt-4">{chart.title}</p>
                  <p className="text-xs text-muted mt-0.5">{chart.caption}</p>
                </button>
              ))}
            </div>
          </div>

          {metrics.notes && (
            <div className="flex gap-3 rounded-xl border border-line bg-white/[0.02] px-4 py-3.5 text-xs text-muted leading-relaxed">
              <Info className="size-4 shrink-0 text-brand mt-px" />
              {metrics.notes}
            </div>
          )}
        </div>
      )}

      {zoom && <Lightbox chart={zoom} onClose={() => setZoom(null)} />}
    </>
  )
}
