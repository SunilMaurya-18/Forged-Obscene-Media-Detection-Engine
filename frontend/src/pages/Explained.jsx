import { Link } from 'react-router-dom'
import {
  ArrowUpRight,
  EyeOff,
  FileLock2,
  FileText,
  Fingerprint,
  Gavel,
  Globe,
  GraduationCap,
  Highlighter,
  MessageCircleQuestion,
  Printer,
  Quote,
  ScanFace,
  Sparkles,
  Tag,
  Timer,
} from 'lucide-react'
import { Card, CardHeader, PageHeader, buttonClass } from '../components/ui'

const PITCH =
  "This checks if a photo — or a still frame from a video — has been faked, flags it if it has, and — because there's now a law requiring platforms to remove this kind of content within a couple of hours — it also keeps a countdown so nothing slips past the legal deadline. Everything it decides comes with a digital seal, so any change made afterward shows up."

const TERMS = [
  {
    term: 'Forgery detection model',
    plain: "Checks if a photo's been faked",
    like: 'A handwriting expert spotting a forged signature',
    icon: ScanFace,
    to: '/analyze',
    see: 'Try it',
  },
  {
    term: 'Explicit content classifier',
    plain: 'Content sensitivity check',
    like: "A separate, independent check — doesn't care who, just what",
    icon: EyeOff,
    to: '/analyze',
    see: 'Try it',
  },
  {
    term: 'Decision engine',
    plain: 'The rulebook that decides what happens next',
    like: 'A referee applying fixed rules, not guessing',
    icon: Gavel,
    to: '/queue',
    see: 'Review queue',
  },
  {
    term: 'SLA / compliance clock',
    plain: 'Legal deadline timer',
    like: 'A delivery app showing "must act within 2 hours"',
    icon: Timer,
    to: '/compliance',
    see: 'Deadline monitor',
  },
  {
    term: 'Provenance ID',
    plain: 'Tamper-evident tag on the file',
    like: "A sealed evidence bag — you can tell if it's been opened",
    icon: Tag,
    to: '/verify',
    see: 'Verify a file',
  },
  {
    term: 'Evidentiary report',
    plain: 'Printable proof report',
    like: 'The forensic report a detective hands to a court',
    icon: FileText,
    to: '/queue',
    see: 'Download one',
  },
  {
    term: 'Signed / tamper-evident report',
    plain: 'A report that proves itself untouched',
    like: 'A wax seal on a letter — break it and everyone knows',
    icon: FileLock2,
    to: '/compliance',
    see: 'Verify a report',
  },
  {
    term: 'Perceptual hash / photo fingerprint',
    plain: "Matches a photo even if it's resized or lightly edited",
    like: 'Like a fingerprint — unique, and still recognizable with a smudge',
    icon: Fingerprint,
    to: '/analyze#prescreen',
    see: 'Check before you post',
  },
  {
    term: 'Cross-dataset generalization',
    plain: "Does it still work on photos it's never seen before",
    like: 'Testing a student on questions not in the textbook',
    icon: GraduationCap,
    to: '/dashboard',
    see: 'See the results',
  },
  {
    term: 'Grad-CAM heatmap',
    plain: 'Highlights the part of the face that looked fake',
    like: 'A highlighter pen marking the suspicious word in a document',
    icon: Highlighter,
    to: '/analyze',
    see: 'Try it',
  },
  {
    term: 'Diffusion-era robustness',
    plain: 'Keeps working as fake-photo tools get newer',
    like: 'An antivirus that updates for new viruses, not just old ones',
    icon: Sparkles,
    to: '/dashboard',
    see: 'See the results',
  },
  {
    term: 'Jurisdiction rules',
    plain: 'Different countries, different legal deadlines',
    like: "Speed limits — the rule depends on which country's road you're on",
    icon: Globe,
    to: '/compliance',
    see: 'See the rules',
  },
]

const FAQ = [
  {
    q: "How do you know it's fake?",
    a: "It looks for tiny, invisible-to-the-eye patterns that AI-generated or edited images leave behind — patterns a camera photo doesn't have.",
  },
  {
    q: 'What if it gets it wrong?',
    a: "Anything the system isn't confident about goes to a human moderator, not straight to an automatic decision. The system only acts alone when it's very sure.",
  },
  {
    q: 'Why does the timer matter?',
    a: 'New laws (in India and the US) require platforms to take this kind of content down within a set number of hours. The timer counts down on every complaint and raises the alarm as the deadline gets close, so nothing is missed by accident.',
  },
  {
    q: 'Is my photo safe with you?',
    a: 'For the most sensitive check — "Check before you post" — your photo never actually leaves your device. Only a unique "fingerprint" of it is sent, similar to how a barcode represents a product without being the product itself. When a photo is uploaded for a full check, it is analysed and then thrown away: only its fingerprint and scores are kept.',
  },
  {
    q: 'Can the report be faked or edited later?',
    a: "Not without it showing — each report is digitally sealed the moment it's created, so any later change to it is detectable.",
  },
]

function TermCard({ term, plain, like, icon: Icon, to, see, index }) {
  return (
    <Card
      className="group p-5 flex flex-col animate-fade-up transition-colors hover:border-line-strong print:p-2.5 print:break-inside-avoid"
      style={{ animationDelay: `${index * 35}ms` }}
    >
      <div className="flex items-start gap-3 print:gap-2">
        <span className="grid place-items-center size-10 shrink-0 rounded-xl border border-line bg-white/[0.04] text-brand transition-colors group-hover:bg-brand/10 print:size-6 print:rounded-md">
          <Icon className="size-[18px] print:size-3.5" />
        </span>
        <div className="min-w-0">
          <p className="font-mono text-[11px] uppercase tracking-[0.08em] text-subtle print:text-[8.5px]">{term}</p>
          <h3 className="mt-1 text-[16px] font-semibold leading-snug tracking-tight print:mt-0 print:text-[11.5px]">{plain}</h3>
        </div>
      </div>
      <p className="mt-4 flex-1 border-l-2 border-brand/40 pl-3 text-sm leading-relaxed text-muted print:mt-1.5 print:pl-2 print:text-[10.5px] print:leading-snug">
        <span className="text-subtle">Think of it like… </span>
        {like}
      </p>
      <Link
        to={to}
        className="mt-4 inline-flex items-center gap-1 self-start text-xs font-medium text-brand opacity-80 transition hover:opacity-100 print:hidden"
      >
        {see}
        <ArrowUpRight className="size-3.5 transition-transform group-hover:translate-x-0.5 group-hover:-translate-y-0.5" />
      </Link>
    </Card>
  )
}

export default function Explained() {
  return (
    <>
      <PageHeader
        eyebrow="Plain English"
        title="What this does, without the jargon"
        description="For anyone just walking up. Technical terms live in the code and the report — this page is deliberately the short version."
        actions={
          <button type="button" onClick={() => window.print()} className={buttonClass('secondary', 'md', 'print:hidden')}>
            <Printer className="size-4" />
            Print one-pager
          </button>
        }
      />

      <Card className="relative overflow-hidden p-7 md:p-9 animate-fade-up print:p-3.5 print:pl-5">
        <div aria-hidden className="absolute inset-y-0 left-0 w-1 bg-brand-gradient" />
        <div aria-hidden className="absolute -right-24 -top-24 size-72 rounded-full bg-brand/10 blur-3xl print:hidden" />
        <div className="relative flex items-center justify-between gap-4">
          <p className="inline-flex items-center gap-2 text-[11px] font-semibold uppercase tracking-[0.18em] text-brand">
            <Quote className="size-3.5" />
            The 30-second pitch
          </p>
          <span className="hidden sm:inline whitespace-nowrap rounded-full border border-line px-2.5 py-1 text-[11px] text-subtle print:hidden">
            Read aloud · ~30 s
          </span>
        </div>
        <blockquote className="relative mt-5 text-lg md:text-[22px] md:leading-[1.55] font-medium tracking-[-0.01em] text-fg/95 max-w-4xl print:mt-1.5 print:max-w-none print:text-[12.5px] print:leading-normal">
          {PITCH}
        </blockquote>
      </Card>

      <section className="mt-12 print:mt-3">
        <div className="mb-5 print:mb-1.5">
          <h2 className="text-[15px] font-semibold tracking-tight print:text-[12.5px]">What each term actually means</h2>
          <p className="text-sm text-muted mt-0.5 print:hidden">
            The words on the poster, the plain version, and something familiar it works like.
          </p>
        </div>
        <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-4 print:grid-cols-3 print:gap-1.5">
          {TERMS.map((t, i) => (
            <TermCard key={t.term} {...t} index={i} />
          ))}
        </div>
      </section>

      <section className="mt-12 print:mt-3 print:break-inside-avoid">
        <Card className="p-6 print:p-3.5">
          <CardHeader icon={MessageCircleQuestion} title="Quick answers, in plain language" description="The questions people ask most at the stand." />
          <div className="grid md:grid-cols-2 gap-x-8 gap-y-6 print:grid-cols-2 print:gap-x-5 print:gap-y-2">
            {FAQ.map(({ q, a }) => (
              <div key={q} className="print:break-inside-avoid">
                <h3 className="text-[15px] font-semibold tracking-tight print:text-[11.5px]">“{q}”</h3>
                <p className="mt-1.5 text-sm leading-relaxed text-muted print:mt-0.5 print:text-[10.5px] print:leading-snug">{a}</p>
              </div>
            ))}
          </div>
        </Card>
      </section>

      <p className="mt-8 text-xs text-subtle print:mt-2 print:text-[9px]">
        Full technical detail lives in <span className="font-mono">IMPLEMENTATION_PLAN.md</span>, the{' '}
        <span className="font-mono">README.md</span> and the phase files. The same page is in the repo as{' '}
        <span className="font-mono">ONE_PAGER.md</span>.
      </p>
    </>
  )
}
