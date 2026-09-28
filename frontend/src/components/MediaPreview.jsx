import { useEffect, useRef, useState } from 'react'
import { Eye, EyeOff, Loader2, Paperclip, X } from 'lucide-react'
import { revealItem } from '../api/client'
import { isVideoFile } from '../lib/media'
import { cn } from '../lib/format'
import { useToast } from './Toast'

/** Raw media is never stored, so without a local original the thumbnail is a blurred pattern derived from the pHash. */
function BlurredThumb({ phash }) {
  const seed = parseInt((phash || '8c').slice(0, 4), 16) || 140
  const light = 30 + (seed % 30)
  return (
    <>
      <div
        aria-hidden
        className="absolute -inset-2 blur-lg"
        style={{
          background: `radial-gradient(circle at 30% 30%, hsl(0 0% ${light + 15}%), transparent 60%),
            radial-gradient(circle at 70% 70%, hsl(0 0% ${light - 10}%), transparent 60%),
            hsl(0 0% ${10 + (seed % 8)}%)`,
        }}
      />
      <div className="absolute inset-0 grid place-items-center bg-black/25">
        <EyeOff aria-hidden className="size-5 text-[#fff]/70" />
      </div>
    </>
  )
}

const ICON_BTN =
  'grid place-items-center size-6 rounded-md bg-black/55 text-[#fff] hover:bg-black/75 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand disabled:opacity-50'

/**
 * Phase 23 wellbeing: a moderator can attach the original from their own device (it never leaves the
 * browser). It is shown greyscale and blurred; seeing it clearly is an explicit, audit-logged reveal that
 * counts toward the exposure limit.
 */
export default function MediaPreview({ item, disabled, onWellbeing, onUnauthorized }) {
  const toast = useToast()
  const input = useRef(null)
  const [file, setFile] = useState(null)
  const [url, setUrl] = useState(null)
  const [revealed, setRevealed] = useState(false)
  const [busy, setBusy] = useState(false)
  const video = useRef(null)

  useEffect(() => () => url && URL.revokeObjectURL(url), [url])

  // autoPlay only applies on load, so a video attached blurred has to be started when it's revealed
  useEffect(() => {
    const v = video.current
    if (!v) return
    if (revealed) v.play().catch(() => {})
    else v.pause()
  }, [revealed, url])

  const attach = (f) => {
    setFile(f)
    setRevealed(false)
    setUrl(f ? URL.createObjectURL(f) : null)
  }

  const reveal = async () => {
    if (revealed) return setRevealed(false)
    setBusy(true)
    try {
      const res = await revealItem(item.id)
      setRevealed(true)
      onWellbeing?.(res.wellbeing)
    } catch (err) {
      if (err.status === 401) return onUnauthorized?.()
      toast(err.message || 'Could not reveal this item.', { tone: 'error', duration: 6000 })
    } finally {
      setBusy(false)
    }
  }

  const media = file && isVideoFile(file) ? 'video' : file ? 'image' : null
  const veil = revealed ? '' : 'grayscale blur-md scale-110'

  return (
    <div className="relative size-20 shrink-0 rounded-xl overflow-hidden border border-line bg-surface-2">
      {media === 'image' && <img src={url} alt={`Local original for item ${item.id}`} className={cn('size-full object-cover transition', veil)} />}
      {media === 'video' && (
        <video ref={video} src={url} muted playsInline loop aria-label={`Local original for item ${item.id}`} className={cn('size-full object-cover transition', veil)} />
      )}
      {!media && <BlurredThumb phash={item.phash} />}

      <div className="absolute inset-x-1 bottom-1 flex justify-between gap-1">
        {media ? (
          <>
            <button
              type="button"
              onClick={reveal}
              disabled={disabled || busy}
              aria-pressed={revealed}
              title={revealed ? 'Blur again' : 'Reveal in colour (logged, counts toward your exposure limit)'}
              aria-label={`Reveal item ${item.id} in colour (logged)`}
              className={ICON_BTN}
            >
              {busy ? <Loader2 className="size-3.5 animate-spin" /> : revealed ? <EyeOff className="size-3.5" /> : <Eye className="size-3.5" />}
            </button>
            <button type="button" onClick={() => attach(null)} title="Remove local original" aria-label={`Remove local original for item ${item.id}`} className={ICON_BTN}>
              <X className="size-3.5" />
            </button>
          </>
        ) : (
          <button
            type="button"
            onClick={() => input.current?.click()}
            disabled={disabled}
            title="Attach the original from this device to preview it (greyscale and blurred; never uploaded)"
            aria-label={`Attach a local original for item ${item.id}`}
            className={cn(ICON_BTN, 'ml-auto')}
          >
            <Paperclip className="size-3.5" />
          </button>
        )}
      </div>
      <input
        ref={input}
        type="file"
        accept="image/*,video/*"
        className="hidden"
        onChange={(e) => {
          const f = e.target.files?.[0]
          e.target.value = ''
          if (f) attach(f)
        }}
      />
    </div>
  )
}
