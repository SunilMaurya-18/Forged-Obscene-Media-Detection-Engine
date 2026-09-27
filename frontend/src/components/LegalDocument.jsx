import { useEffect } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Info, Printer } from 'lucide-react'
import { LEGAL } from '../lib/legal'
import { buttonClass, Card, PageHeader } from './ui'

/**
 * Shared layout for the Terms and the Privacy Policy: header, effective date, template notice,
 * a contents list, and numbered sections that can be linked to by id (#collect, #rights, ...).
 */
export default function LegalDocument({ eyebrow, title, description, sections, other }) {
  const { t, i18n } = useTranslation()
  const { hash } = useLocation()

  useEffect(() => {
    if (hash) document.getElementById(hash.slice(1))?.scrollIntoView()
    else window.scrollTo(0, 0)
  }, [hash])

  return (
    <div>
      <PageHeader
        eyebrow={eyebrow}
        title={title}
        description={description}
        actions={
          <button type="button" onClick={() => window.print()} className={buttonClass('secondary', 'sm', 'print:hidden')}>
            <Printer aria-hidden className="size-4" /> {t('legal.print')}
          </button>
        }
      />

      <p className="text-sm text-muted -mt-4 mb-6">
        {t('legal.effective', { date: LEGAL.effectiveDate })} · {t('legal.version', { version: LEGAL.version })}
      </p>

      <div className="space-y-3 mb-10">
        <Card className="p-4 flex gap-3 text-sm border-amber-400/30 bg-amber-400/[0.06]">
          <Info aria-hidden className="size-5 shrink-0 text-amber-300 mt-0.5" />
          <p className="text-muted leading-relaxed">{t('legal.templateNotice')}</p>
        </Card>
        {i18n.resolvedLanguage !== 'en' && (
          <Card lang={i18n.resolvedLanguage} className="p-4 text-sm text-muted">
            {t('legal.englishOnly')}
          </Card>
        )}
      </div>

      <div className="grid lg:grid-cols-[220px_minmax(0,1fr)] gap-10 items-start">
        <nav aria-label={t('legal.contents')} className="lg:sticky lg:top-24 print:hidden">
          <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-subtle mb-3">{t('legal.contents')}</p>
          <ol className="space-y-1.5 text-sm">
            {sections.map((s, i) => (
              <li key={s.id}>
                <a href={`#${s.id}`} className="flex gap-2 text-muted hover:text-fg transition">
                  <span className="tabular-nums text-subtle w-5 shrink-0">{i + 1}.</span>
                  <span>{s.title}</span>
                </a>
              </li>
            ))}
          </ol>
        </nav>

        <article lang="en" className="space-y-10 max-w-3xl">
          {sections.map((s, i) => (
            <section key={s.id} id={s.id} aria-labelledby={`${s.id}-h`} className="scroll-mt-24">
              <h2 id={`${s.id}-h`} className="text-lg font-semibold tracking-tight mb-3">
                <span className="text-subtle tabular-nums mr-2">{i + 1}.</span>
                {s.title}
              </h2>
              <div className="space-y-3 text-[15px] leading-relaxed text-muted [&_strong]:text-fg [&_strong]:font-semibold [&_a]:text-brand [&_a:hover]:underline [&_ul]:list-disc [&_ul]:pl-5 [&_ul]:space-y-1.5 [&_li]:marker:text-subtle">
                {s.body}
              </div>
            </section>
          ))}

          <p className="border-t border-line pt-6 text-sm text-muted">
            {t('legal.seeAlso')}{' '}
            <Link to={other.to} className="text-brand hover:underline">
              {other.label}
            </Link>
          </p>
        </article>
      </div>
    </div>
  )
}
