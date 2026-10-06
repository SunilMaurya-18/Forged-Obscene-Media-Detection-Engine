import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CircleCheck, Cpu, Fingerprint, ImageUp, Loader2, OctagonAlert, Send, ShieldCheck, Sparkles } from 'lucide-react'
import { prescreenHash } from '../api/client'
import { useDemo } from '../context/DemoContext'
import { ALGORITHM, hashImageOnDevice } from '../utils/onDeviceHash'
import { ErrorState, LoadingLine } from './Status'
import { Card, CardHeader, buttonClass } from './ui'
import { cn } from '../lib/format'

const DEMO_IMAGE = '/prescreen-demo.png'

function Step({ icon: Icon, label, detail, state }) {
  return (
    <li
      className={cn(
        'flex items-center gap-3 rounded-xl border px-3.5 py-2.5 text-sm transition-all duration-300',
        state === 'active' ? 'border-brand/30 bg-brand/[0.06]' : 'border-line',
        state === 'idle' && 'opacity-45',
      )}
    >
      <Icon aria-hidden className={cn('size-4 shrink-0', state === 'done' ? 'text-emerald-300' : state === 'active' ? 'text-brand' : 'text-muted')} />
      <span className="flex-1">{label}</span>
      {state === 'active' && <Loader2 aria-hidden className="size-4 animate-spin text-brand" />}
      {state === 'done' && detail && <span className="font-mono text-[11px] text-subtle">{detail}</span>}
    </li>
  )
}

/**
 * Phase 18: hash on this device, send only the hash, get match / no-match.
 * The image is read with createImageBitmap into a local canvas; it is never put in a request.
 */
export default function PrescreenCard() {
  const { t } = useTranslation()
  const { enabled: demoEnabled } = useDemo()
  const inputRef = useRef(null)
  const [preview, setPreview] = useState(null)
  const [phase, setPhase] = useState('idle')
  const [hashed, setHashed] = useState(null)
  const [sent, setSent] = useState(null)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => () => preview && URL.revokeObjectURL(preview), [preview])

  const check = async (blob) => {
    setError(null)
    setResult(null)
    setSent(null)
    setHashed(null)
    setPreview(URL.createObjectURL(blob))
    try {
      setPhase('hashing')
      const h = await hashImageOnDevice(blob)
      setHashed(h)
      setPhase('sending')
      const body = { hash: h.hash, algorithm: ALGORITHM }
      setSent(JSON.stringify(body))
      setResult(await prescreenHash(h.hash, ALGORITHM))
      setPhase('done')
    } catch (err) {
      setError(err?.i18nKey ? err : t('prescreen.failed'))
      setPhase('idle')
    }
  }

  const tryDemo = async () => {
    const res = await fetch(DEMO_IMAGE)
    check(await res.blob())
  }

  const stepState = (step) => {
    const order = ['hashing', 'sending', 'done']
    const at = order.indexOf(phase)
    const i = order.indexOf(step)
    if (phase === 'idle') return 'idle'
    return i < at || phase === 'done' ? 'done' : i === at ? 'active' : 'idle'
  }

  return (
    <Card className="p-6 animate-fade-up">
      <CardHeader icon={ShieldCheck} title={t('prescreen.title')} description={t('prescreen.description')} />
      <div className="grid lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)] gap-6">
        <div>
          <div className="relative aspect-[4/3] rounded-2xl border border-dashed border-line-strong bg-checker overflow-hidden">
            {preview ? (
              <img src={preview} alt={t('prescreen.onDevice')} className="absolute inset-0 size-full object-contain p-3" />
            ) : (
              <div className="absolute inset-0 grid place-items-center text-center p-6">
                <div>
                  <Cpu aria-hidden className="size-7 text-brand mx-auto" />
                  <p className="text-sm font-medium mt-3">{t('prescreen.idleTitle')}</p>
                  <p className="text-xs text-subtle mt-1">{t('prescreen.idleHint')}</p>
                </div>
              </div>
            )}
            <span className="absolute left-3 bottom-3 inline-flex items-center gap-1.5 rounded-full bg-black/60 px-2.5 py-1 text-[11px] text-[#fff]/90">
              <Cpu aria-hidden className="size-3" /> {t('prescreen.onDevice')}
            </span>
          </div>
          <div className={cn('mt-4 grid gap-2', demoEnabled && 'grid-cols-2')}>
            <button type="button" onClick={() => inputRef.current?.click()} className={buttonClass('primary', 'md')}>
              <ImageUp aria-hidden className="size-4" /> {t('prescreen.choose')}
            </button>
            {demoEnabled && (
              <button type="button" onClick={tryDemo} className={buttonClass('secondary', 'md')} title={t('prescreen.demoTitle')}>
                <Sparkles aria-hidden className="size-4" /> {t('prescreen.demo')}
              </button>
            )}
          </div>
          <input
            ref={inputRef}
            type="file"
            accept="image/*"
            className="hidden"
            onChange={(e) => {
              const f = e.target.files?.[0]
              e.target.value = ''
              if (f) check(f)
            }}
          />
        </div>

        <div className="space-y-4">
          {phase === 'hashing' && <LoadingLine label={t('loading.hashing')} />}
          <ol className="space-y-2">
            <Step icon={Fingerprint} label={t('prescreen.stepHash')} state={stepState('hashing')} detail={hashed && `${Math.round(hashed.ms)} ms`} />
            <Step icon={Send} label={t('prescreen.stepSend')} state={stepState('sending')} detail={sent && `${new Blob([sent]).size} bytes`} />
            <Step
              icon={ShieldCheck}
              label={t('prescreen.stepResult')}
              state={stepState('done')}
              detail={result && (result.match ? t('prescreen.match') : t('prescreen.noMatch'))}
            />
          </ol>

          <div className="rounded-xl border border-line bg-canvas/60 p-3.5">
            <p className="text-xs text-muted mb-1.5">{t('prescreen.sentLabel')}</p>
            <pre className="font-mono text-[12px] text-fg/90 whitespace-pre-wrap break-all">{sent || t('prescreen.nothingYet')}</pre>
            <p className="text-[11px] text-subtle mt-2">{t('prescreen.bytesSent')}</p>
          </div>

          {result && (
            <div
              role="status"
              className={cn(
                'flex gap-3 rounded-xl border p-4 animate-fade-up',
                result.match ? 'border-rose-400/30 bg-rose-400/[0.08]' : 'border-emerald-400/30 bg-emerald-400/[0.07]',
              )}
            >
              {result.match ? (
                <OctagonAlert aria-hidden className="size-5 text-rose-300 shrink-0" />
              ) : (
                <CircleCheck aria-hidden className="size-5 text-emerald-300 shrink-0" />
              )}
              <div>
                <p className={cn('text-sm font-semibold', result.match ? 'text-rose-200' : 'text-emerald-200')}>
                  {result.match ? t('prescreen.matchTitle') : t('prescreen.noMatchTitle')}
                </p>
                <p className={cn('text-[13px] leading-relaxed mt-0.5', result.match ? 'text-rose-100/80' : 'text-emerald-100/80')}>
                  {result.match ? t('prescreen.matchBody') : t('prescreen.noMatchBody')}
                </p>
              </div>
            </div>
          )}
          <ErrorState error={error} />

          <p className="text-[11px] text-subtle leading-relaxed">{t('prescreen.footnote')}</p>
        </div>
      </div>
    </Card>
  )
}
