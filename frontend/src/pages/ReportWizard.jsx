import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Trans, useTranslation } from 'react-i18next'
import {
  ArrowLeft,
  ArrowRight,
  Check,
  CircleCheck,
  Download,
  Eye,
  EyeOff,
  ImageUp,
  KeyRound,
  Pencil,
  Search,
  Send,
  ShieldCheck,
  UploadCloud,
  User,
  Users,
} from 'lucide-react'
import { fileReport } from '../api/client'
import PrivacyNote from '../components/PrivacyNote'
import { ErrorState, LoadingLine } from '../components/Status'
import { Button, Card, CopyButton, INPUT, PageHeader, buttonClass } from '../components/ui'
import { DETAILS_MAX, REGIONS, composeDetails, contactChannel, formatWhen } from '../lib/cases'
import { errorText } from '../lib/errors'
import { cn, fileSize } from '../lib/format'

const MAX_BYTES = 25 * 1024 * 1024
const STEPS = ['select', 'consent', 'details', 'review']

/** Server validation codes, mapped back to the field (and step) where the reporter can fix them. */
const FIELD_FOR_CODE = {
  file_required: ['file', 0],
  consent_required: ['consent', 1],
  relationship_required: ['relationship', 1],
  relationship_invalid: ['relationship', 1],
  invalid_contact: ['contact', 2],
  details_too_long: ['details', 2],
  invalid_jurisdiction: ['jurisdiction', 2],
}

const EMPTY = { file: null, relationship: '', consent: false, where: '', notes: '', contact: '', jurisdiction: 'IN' }

function Stepper({ step }) {
  const { t } = useTranslation()
  return (
    <nav aria-label={t('wizard.progress', { n: step + 1, total: STEPS.length })} className="mb-6">
      <p className="label-caps mb-3">{t('wizard.progress', { n: step + 1, total: STEPS.length })}</p>
      <ol className="flex items-center gap-2 sm:gap-3">
        {STEPS.map((name, i) => {
          const done = i < step
          const current = i === step
          return (
            <li key={name} aria-current={current ? 'step' : undefined} className={cn('flex items-center gap-2 min-w-0', i < STEPS.length - 1 && 'flex-1')}>
              <span
                aria-hidden
                className={cn(
                  'grid place-items-center size-7 shrink-0 rounded-full border text-xs font-semibold transition-colors',
                  done && 'border-brand bg-brand text-canvas',
                  current && 'border-brand text-fg bg-brand/10 shadow-[0_0_0_4px_rgb(217_119_87/0.14)]',
                  !done && !current && 'border-line-strong text-subtle',
                )}
              >
                {done ? <Check className="size-3.5" /> : i + 1}
              </span>
              <span className={cn('truncate text-[13px]', current ? 'text-fg font-medium' : 'text-subtle', !current && 'max-sm:sr-only')}>
                {t(`wizard.steps.${name}`)}
                {done && <span className="sr-only"> ({t('common.done')})</span>}
              </span>
              {i < STEPS.length - 1 && (
                <span aria-hidden className={cn('h-px flex-1 min-w-3', done ? 'bg-brand/60' : 'bg-line-strong')} />
              )}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}

/** Blurred by default: the reporter may not want to see an intimate image of themselves again. */
function Thumb({ src, className }) {
  const { t } = useTranslation()
  const [shown, setShown] = useState(false)
  return (
    <div className={cn('relative overflow-hidden rounded-xl border border-line bg-checker', className)}>
      <img src={src} alt={t('wizard.select.selected')} className={cn('size-full object-contain transition', !shown && 'blur-xl scale-110')} />
      <button
        type="button"
        onClick={() => setShown((s) => !s)}
        aria-pressed={shown}
        className="absolute bottom-2 right-2 inline-flex items-center gap-1.5 rounded-lg bg-canvas/80 px-2.5 py-1 text-xs backdrop-blur hover:bg-canvas"
      >
        {shown ? <EyeOff aria-hidden className="size-3.5" /> : <Eye aria-hidden className="size-3.5" />}
        {shown ? t('wizard.select.hide') : t('wizard.select.show')}
      </button>
    </div>
  )
}

function SelectStep({ form, preview, onFile, error }) {
  const { t } = useTranslation()
  const inputRef = useRef(null)
  const [dragging, setDragging] = useState(false)
  const hintId = useId()

  return (
    <div className="space-y-4">
      {preview ? (
        <div className="flex flex-col sm:flex-row gap-4 sm:items-center">
          <Thumb src={preview} className="w-full sm:w-44 aspect-[4/3] shrink-0" />
          <div className="min-w-0 flex-1">
            <p className="text-sm font-medium truncate">{form.file.name}</p>
            <p className="text-xs text-subtle mt-0.5">{fileSize(form.file.size)}</p>
            <button type="button" onClick={() => inputRef.current?.click()} className={buttonClass('secondary', 'sm', 'mt-3')}>
              <ImageUp aria-hidden className="size-3.5" />
              {t('wizard.select.change')}
            </button>
          </div>
        </div>
      ) : (
        <button
          type="button"
          aria-describedby={hintId}
          onClick={() => inputRef.current?.click()}
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
            'w-full rounded-2xl border border-dashed px-6 py-12 text-center transition',
            dragging ? 'border-brand bg-brand/[0.07]' : 'border-line-strong bg-white/[0.015] hover:border-white/25 hover:bg-white/[0.03]',
          )}
        >
          <UploadCloud aria-hidden className="mx-auto size-8 text-brand mb-3" />
          <span className="block font-medium">
            {t('wizard.select.drop')} <span className="text-brand underline underline-offset-4">{t('wizard.select.browse')}</span>
          </span>
          <span id={hintId} className="block text-xs text-subtle mt-2">
            {t('wizard.select.hint')}
          </span>
        </button>
      )}
      <input
        ref={inputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp,image/*"
        className="sr-only"
        tabIndex={-1}
        aria-hidden
        onChange={(e) => {
          onFile(e.target.files?.[0])
          e.target.value = ''
        }}
      />
      {error && <FieldError>{error}</FieldError>}
    </div>
  )
}

function FieldError({ id, children }) {
  return (
    <p id={id} role="alert" className="flex items-center gap-1.5 text-sm text-rose-300">
      <span aria-hidden className="font-bold">!</span>
      {children}
    </p>
  )
}

function ConsentStep({ form, set, errors }) {
  const { t } = useTranslation()
  const options = [
    { value: 'SELF', icon: User, label: t('wizard.consent.self'), hint: t('wizard.consent.selfHint') },
    { value: 'ON_BEHALF', icon: Users, label: t('wizard.consent.behalf'), hint: t('wizard.consent.behalfHint') },
  ]
  return (
    <div className="space-y-5">
      <fieldset>
        <legend className="sr-only">{t('wizard.consent.heading')}</legend>
        <div className="grid sm:grid-cols-2 gap-3">
          {options.map(({ value, icon: Icon, label, hint }) => {
            const checked = form.relationship === value
            return (
              <label
                key={value}
                className={cn(
                  'relative flex gap-3 rounded-xl border p-4 cursor-pointer transition has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-brand',
                  checked ? 'border-brand/60 bg-brand/[0.08]' : 'border-line hover:bg-white/[0.03]',
                )}
              >
                <input
                  type="radio"
                  name="relationship"
                  value={value}
                  checked={checked}
                  onChange={() => set({ relationship: value })}
                  className="sr-only"
                />
                <Icon aria-hidden className={cn('size-5 shrink-0 mt-0.5', checked ? 'text-brand' : 'text-muted')} />
                <span className="min-w-0">
                  <span className="block text-sm font-medium">{label}</span>
                  <span className="block text-xs text-muted mt-0.5 leading-relaxed">{hint}</span>
                </span>
                <span
                  aria-hidden
                  className={cn(
                    'ml-auto grid place-items-center size-5 shrink-0 rounded-full border',
                    checked ? 'border-brand bg-brand text-canvas' : 'border-line-strong',
                  )}
                >
                  {checked && <Check className="size-3" />}
                </span>
              </label>
            )
          })}
        </div>
        {errors.relationship && <div className="mt-2"><FieldError>{errors.relationship}</FieldError></div>}
      </fieldset>

      <label className="flex gap-3 rounded-xl border border-line bg-white/[0.02] p-4 cursor-pointer has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-brand">
        <input
          type="checkbox"
          checked={form.consent}
          onChange={(e) => set({ consent: e.target.checked })}
          aria-invalid={Boolean(errors.consent)}
          className="mt-0.5 size-4 shrink-0 accent-[var(--color-brand)]"
        />
        <span className="text-sm leading-relaxed">{t('wizard.consent.confirm')}</span>
      </label>
      {errors.consent && <FieldError>{errors.consent}</FieldError>}
    </div>
  )
}

function TextField({ id, label, hint, error, optional, children }) {
  const { t } = useTranslation()
  return (
    <div>
      <label htmlFor={id} className="block text-sm font-medium">
        {label}
        {optional && <span className="ml-1.5 text-xs font-normal text-subtle">({t('common.optional')})</span>}
      </label>
      {hint && (
        <p id={`${id}-hint`} className="text-xs text-subtle mt-0.5">
          {hint}
        </p>
      )}
      <div className="mt-2">{children}</div>
      {error && (
        <div className="mt-1.5">
          <FieldError id={`${id}-error`}>{error}</FieldError>
        </div>
      )}
    </div>
  )
}

function DetailsStep({ form, set, errors }) {
  const { t } = useTranslation()
  const used = composeDetails(form.where, form.notes).length
  const remaining = DETAILS_MAX - used
  const described = (id, err) => [`${id}-hint`, err && `${id}-error`].filter(Boolean).join(' ')

  return (
    <div className="space-y-5">
      <p className="text-sm text-muted">{t('wizard.details.subheading')}</p>
      <TextField id="rw-where" label={t('wizard.details.where')} hint={t('wizard.details.whereHint')} optional>
        <input
          id="rw-where"
          type="text"
          value={form.where}
          maxLength={500}
          onChange={(e) => set({ where: e.target.value })}
          aria-describedby="rw-where-hint"
          className={INPUT}
        />
      </TextField>
      <TextField id="rw-notes" label={t('wizard.details.notes')} hint={t('wizard.details.notesHint')} error={errors.details} optional>
        <textarea
          id="rw-notes"
          rows={4}
          value={form.notes}
          onChange={(e) => set({ notes: e.target.value })}
          aria-invalid={Boolean(errors.details)}
          aria-describedby={described('rw-notes', errors.details) + ' rw-notes-count'}
          className={cn(INPUT, 'resize-y min-h-24')}
        />
        <p id="rw-notes-count" aria-live="polite" className={cn('text-xs mt-1 text-right tabular-nums', remaining < 0 ? 'text-rose-300' : 'text-subtle')}>
          {t('wizard.details.remaining', { count: remaining })}
        </p>
      </TextField>
      <TextField id="rw-contact" label={t('wizard.details.contact')} hint={t('wizard.details.contactHint')} error={errors.contact} optional>
        <input
          id="rw-contact"
          type="text"
          inputMode="email"
          autoComplete="email"
          value={form.contact}
          onChange={(e) => set({ contact: e.target.value })}
          aria-invalid={Boolean(errors.contact)}
          aria-describedby={described('rw-contact', errors.contact)}
          placeholder="name@example.com · +91 98765 43210"
          className={INPUT}
        />
      </TextField>
      <TextField id="rw-region" label={t('wizard.details.jurisdiction')} hint={t('wizard.details.jurisdictionHint')} error={errors.jurisdiction}>
        <select
          id="rw-region"
          value={form.jurisdiction}
          onChange={(e) => set({ jurisdiction: e.target.value })}
          aria-invalid={Boolean(errors.jurisdiction)}
          aria-describedby={described('rw-region', errors.jurisdiction)}
          className={cn(INPUT, 'appearance-auto')}
        >
          {REGIONS.map(({ code, key }) => (
            <option key={code} value={code} className="bg-surface-2">
              {t(`wizard.details.regions.${key}`)}
            </option>
          ))}
        </select>
      </TextField>
    </div>
  )
}

function ReviewStep({ form, preview, goTo }) {
  const { t } = useTranslation()
  const region = REGIONS.find((r) => r.code === form.jurisdiction)
  const rows = [
    { label: t('wizard.review.who'), value: form.relationship === 'SELF' ? t('wizard.consent.self') : t('wizard.consent.behalf'), step: 1 },
    { label: t('wizard.review.where'), value: form.where.trim() || t('common.notGiven'), step: 2 },
    { label: t('wizard.details.notes'), value: form.notes.trim() || t('common.notGiven'), step: 2 },
    { label: t('wizard.review.contact'), value: form.contact.trim() || t('common.notGiven'), step: 2 },
    { label: t('wizard.review.region'), value: t(`wizard.details.regions.${region?.key || 'OTHER'}`), step: 2 },
  ]
  return (
    <div className="space-y-4">
      <div className="flex gap-4 items-center rounded-xl border border-line bg-white/[0.02] p-3">
        <Thumb src={preview} className="w-24 aspect-square shrink-0" />
        <div className="min-w-0 flex-1">
          <p className="text-xs text-muted">{t('wizard.review.image')}</p>
          <p className="text-sm truncate">{form.file.name}</p>
        </div>
        <EditButton label={t('wizard.review.image')} onClick={() => goTo(0)} />
      </div>
      <dl className="divide-y divide-line rounded-xl border border-line">
        {rows.map(({ label, value, step }) => (
          <div key={label} className="flex items-start gap-3 px-4 py-3">
            <dt className="w-36 shrink-0 text-xs text-muted pt-0.5">{label}</dt>
            <dd className="flex-1 min-w-0 text-sm break-words whitespace-pre-line">{value}</dd>
            <EditButton label={label} onClick={() => goTo(step)} />
          </div>
        ))}
      </dl>
      <p className="text-xs text-muted leading-relaxed">
        <Trans
          i18nKey="wizard.review.agree"
          components={{
            terms: <Link to="/terms" target="_blank" rel="noopener" className="text-brand hover:underline" />,
            privacy: <Link to="/privacy" target="_blank" rel="noopener" className="text-brand hover:underline" />,
          }}
        />
      </p>
    </div>
  )
}

function EditButton({ label, onClick }) {
  const { t } = useTranslation()
  return (
    <button type="button" onClick={onClick} className={buttonClass('ghost', 'sm', 'shrink-0')} aria-label={`${t('wizard.review.edit')}: ${label}`}>
      <Pencil aria-hidden className="size-3.5" />
      {t('wizard.review.edit')}
    </button>
  )
}

function DoneView({ created, onAnother }) {
  const { t, i18n } = useTranslation()
  const headingRef = useRef(null)
  useEffect(() => headingRef.current?.focus(), [])

  const save = () => {
    const lines = [
      t('wizard.done.saveFileIntro'),
      '',
      `${t('wizard.done.caseNumber')}: ${created.caseNumber}`,
      `${t('wizard.done.pin')}: ${created.pin}`,
      `${window.location.origin}/status/${created.caseNumber}`,
    ]
    const url = URL.createObjectURL(new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' }))
    const a = Object.assign(document.createElement('a'), { href: url, download: `${created.caseNumber}.txt` })
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <Card className="p-6 md:p-8 animate-fade-up">
      <div className="flex items-start gap-4">
        <span className="grid place-items-center size-12 shrink-0 rounded-2xl border border-emerald-400/30 bg-emerald-400/10">
          <CircleCheck aria-hidden className="size-6 text-emerald-300" />
        </span>
        <div>
          <h2 ref={headingRef} tabIndex={-1} className="text-xl font-semibold tracking-tight focus:outline-none">
            {t('wizard.done.title')}
          </h2>
          <p className="text-sm text-muted mt-1 leading-relaxed">{t('wizard.done.body')}</p>
        </div>
      </div>

      <div className="grid sm:grid-cols-2 gap-3 mt-6">
        {[
          { icon: Search, label: t('wizard.done.caseNumber'), value: created.caseNumber },
          { icon: KeyRound, label: t('wizard.done.pin'), value: created.pin },
        ].map(({ icon: Icon, label, value }) => (
          <div key={label} className="rounded-xl border border-line-strong bg-white/[0.03] px-4 py-3">
            <p className="flex items-center gap-1.5 text-xs text-muted">
              <Icon aria-hidden className="size-3.5" />
              {label}
            </p>
            <div className="flex items-center justify-between gap-2 mt-1">
              <p className="font-mono text-2xl font-semibold tracking-wider select-all" aria-label={`${label}: ${value.split('').join(' ')}`}>
                {value}
              </p>
              <CopyButton value={value} />
            </div>
          </div>
        ))}
      </div>
      <p role="note" className="mt-3 flex items-start gap-2 text-sm text-amber-200">
        <KeyRound aria-hidden className="size-4 mt-0.5 shrink-0" />
        {t('wizard.done.pinWarning')}
      </p>

      <div className="mt-5 space-y-1.5 text-sm text-muted">
        {created.deadline && (
          <p>{t('wizard.done.deadline', { rule: created.rule, hours: created.deadlineHours, time: formatWhen(created.deadline, i18n.resolvedLanguage) })}</p>
        )}
        {created.notifyChannel && <p>{t('wizard.done.notify', { channel: t(`wizard.done.channel.${created.notifyChannel}`) })}</p>}
      </div>

      <div className="mt-6 flex flex-wrap gap-2">
        <Button icon={Download} onClick={save}>
          {t('wizard.done.save')}
        </Button>
        <Link to={`/status/${created.caseNumber}`} className={buttonClass('secondary')}>
          <ShieldCheck aria-hidden className="size-4" />
          {t('wizard.done.checkStatus')}
        </Link>
        <Button variant="ghost" onClick={onAnother}>
          {t('wizard.done.another')}
        </Button>
      </div>
    </Card>
  )
}

export default function ReportWizard() {
  const { t, i18n } = useTranslation()
  const [step, setStep] = useState(0)
  const [form, setForm] = useState(EMPTY)
  const [preview, setPreview] = useState(null)
  const [errors, setErrors] = useState({})
  const [submitError, setSubmitError] = useState(null)
  const [submitting, setSubmitting] = useState(false)
  const [created, setCreated] = useState(null)
  const headingRef = useRef(null)
  const firstRender = useRef(true)

  useEffect(() => () => preview && URL.revokeObjectURL(preview), [preview])

  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false
      return
    }
    headingRef.current?.focus()
  }, [step])

  const set = useCallback((patch) => {
    setForm((f) => ({ ...f, ...patch }))
    setErrors((e) => {
      const next = { ...e }
      Object.keys(patch).forEach((k) => delete next[k === 'notes' || k === 'where' ? 'details' : k])
      return next
    })
  }, [])

  const onFile = useCallback(
    (f) => {
      if (!f) return
      if (!f.type.startsWith('image/')) return setErrors({ file: t('wizard.select.notImage') })
      if (f.size > MAX_BYTES) return setErrors({ file: t('wizard.select.tooBig', { size: fileSize(f.size) }) })
      setErrors({})
      set({ file: f })
      setPreview(URL.createObjectURL(f))
    },
    [set, t],
  )

  const validate = (s) => {
    const e = {}
    if (s === 0 && !form.file) e.file = t('wizard.select.required')
    if (s === 1) {
      if (!form.relationship) e.relationship = t('wizard.consent.chooseOne')
      if (!form.consent) e.consent = t('wizard.consent.confirmRequired')
    }
    if (s === 2) {
      if (contactChannel(form.contact) === null) e.contact = t('errors.codes.invalid_contact')
      if (composeDetails(form.where, form.notes).length > DETAILS_MAX) e.details = t('errors.codes.details_too_long')
    }
    return e
  }

  const next = () => {
    const e = validate(step)
    setErrors(e)
    if (Object.keys(e).length === 0) setStep((s) => Math.min(s + 1, STEPS.length - 1))
  }

  const back = () => {
    setErrors({})
    setStep((s) => Math.max(0, s - 1))
  }

  const submit = async () => {
    for (let s = 0; s < 3; s++) {
      const e = validate(s)
      if (Object.keys(e).length) {
        setErrors(e)
        return setStep(s)
      }
    }
    setSubmitting(true)
    setSubmitError(null)
    try {
      const res = await fileReport({
        file: form.file,
        relationship: form.relationship,
        consent: form.consent,
        details: composeDetails(form.where, form.notes),
        contact: form.contact.trim(),
        jurisdiction: form.jurisdiction,
        locale: i18n.resolvedLanguage,
      })
      setCreated(res)
    } catch (err) {
      const target = FIELD_FOR_CODE[err.code]
      if (target) {
        setErrors({ [target[0]]: errorText(t, err) })
        setStep(target[1])
      } else {
        setSubmitError(err)
      }
    } finally {
      setSubmitting(false)
    }
  }

  const reset = () => {
    setForm(EMPTY)
    setPreview(null)
    setErrors({})
    setSubmitError(null)
    setCreated(null)
    setStep(0)
  }

  const headings = [t('wizard.select.heading'), t('wizard.consent.heading'), t('wizard.details.heading'), t('wizard.review.heading')]

  return (
    <>
      <PageHeader
        eyebrow={t('wizard.eyebrow')}
        title={t('wizard.title')}
        description={t('wizard.description')}
      />

      <div className="grid lg:grid-cols-[minmax(0,1fr)_minmax(0,340px)] gap-6 items-start">
        {created ? (
          <DoneView created={created} onAnother={reset} />
        ) : (
          <Card className="p-5 md:p-7 animate-fade-up">
            <Stepper step={step} />
            <form
              noValidate
              onSubmit={(e) => {
                e.preventDefault()
                if (step < STEPS.length - 1) next()
                else submit()
              }}
            >
              <h2 ref={headingRef} tabIndex={-1} className="text-lg font-semibold tracking-tight mb-5 focus:outline-none">
                {headings[step]}
              </h2>

              {step === 0 && (
                <div className="space-y-4">
                  <PrivacyNote />
                  <SelectStep form={form} preview={preview} onFile={onFile} error={errors.file} />
                </div>
              )}
              {step === 1 && <ConsentStep form={form} set={set} errors={errors} />}
              {step === 2 && <DetailsStep form={form} set={set} errors={errors} />}
              {step === 3 && <ReviewStep form={form} preview={preview} goTo={setStep} />}

              {submitError && <ErrorState error={submitError} onRetry={submit} className="mt-5" />}

              <div className="mt-7 flex items-center justify-between gap-3">
                {step > 0 ? (
                  <Button variant="ghost" icon={ArrowLeft} onClick={back} disabled={submitting}>
                    {t('common.back')}
                  </Button>
                ) : (
                  <span />
                )}
                {submitting ? (
                  <LoadingLine label={t('loading.filing')} />
                ) : step < STEPS.length - 1 ? (
                  <button type="submit" className={buttonClass('primary', 'lg')}>
                    {t('common.continue')}
                    <ArrowRight aria-hidden className="size-4" />
                  </button>
                ) : (
                  <button type="submit" className={buttonClass('primary', 'lg')}>
                    <Send aria-hidden className="size-4" />
                    {t('wizard.review.submit')}
                  </button>
                )}
              </div>
            </form>
          </Card>
        )}

        <aside className="space-y-4 animate-fade-up [animation-delay:80ms]">
          {created ? <PrivacyNote compact /> : null}
          <Card className="p-5">
            <p className="text-sm font-medium">{t('wizard.aside.title')}</p>
            <ol className="mt-3 space-y-3 text-[13px] text-muted leading-relaxed list-decimal pl-4 marker:text-subtle">
              <li>{t('wizard.aside.one')}</li>
              <li>{t('wizard.aside.two')}</li>
              <li>{t('wizard.aside.three')}</li>
            </ol>
            <Link to="/status" className={buttonClass('secondary', 'sm', 'mt-4 w-full')}>
              <Search aria-hidden className="size-3.5" />
              {t('wizard.aside.haveCase')}
            </Link>
          </Card>
        </aside>
      </div>
    </>
  )
}
