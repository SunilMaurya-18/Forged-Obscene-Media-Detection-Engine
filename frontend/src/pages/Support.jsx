import { Link } from 'react-router-dom'
import { Coffee, HeartHandshake, Phone, ShieldCheck, Users } from 'lucide-react'
import { Card, CardHeader, PageHeader, buttonClass } from '../components/ui'

const HELPLINES = [
  { region: 'India', name: 'Tele-MANAS (free, 24×7, multilingual)', number: '14416', tel: '14416' },
  { region: 'India', name: 'Tele-MANAS toll-free', number: '1-800-891-4416', tel: '18008914416' },
  { region: 'United States', name: '988 Suicide & Crisis Lifeline', number: '988', tel: '988' },
  { region: 'United Kingdom', name: 'Samaritans', number: '116 123', tel: '116123' },
  { region: 'Australia', name: 'Lifeline', number: '13 11 14', tel: '131114' },
]

const HABITS = [
  'Keep media greyscale and blurred unless you need detail to decide. Reveal is per item and logged.',
  'Decide from the scores and heatmap first; look at the image only when they disagree.',
  'Take the break when the counter asks. It resets your session, not your progress.',
  'Talk to your team lead if a case stays with you. Asking for help is part of the job.',
]

/** Phase 23: moderator wellbeing resources, linked from the queue's break screen. */
export default function Support() {
  return (
    <>
      <PageHeader
        eyebrow="Moderator wellbeing"
        title="Support for reviewers"
        description="Reviewing harmful content takes a toll. These resources are here whenever you need them, not only when the break screen appears."
      />
      <div className="grid lg:grid-cols-2 gap-5 max-w-5xl">
        <Card className="p-6">
          <CardHeader icon={Users} title="Your team first" description="The fastest help is usually the closest." />
          <ul className="space-y-2 text-sm text-muted list-disc pl-5">
            <li>Your team lead or senior moderator can take a case off your queue at any time: escalate it with a short reason.</li>
            <li>If your organisation has an Employee Assistance Programme (EAP), it offers free, confidential counselling.</li>
            <li>Debriefs after a difficult shift are normal. Ask for one.</li>
          </ul>
        </Card>

        <Card className="p-6">
          <CardHeader icon={Phone} title="Talk to someone now" description="Free, confidential helplines." />
          <ul className="divide-y divide-line">
            {HELPLINES.map((h) => (
              <li key={h.number} className="flex items-center justify-between gap-3 py-2.5">
                <div className="min-w-0">
                  <p className="text-sm">{h.name}</p>
                  <p className="text-xs text-subtle">{h.region}</p>
                </div>
                <a href={`tel:${h.tel}`} className="font-mono text-sm text-brand hover:underline shrink-0">
                  {h.number}
                </a>
              </li>
            ))}
          </ul>
          <p className="text-xs text-subtle mt-3">If you or someone else is in immediate danger, call your local emergency number.</p>
        </Card>

        <Card className="p-6">
          <CardHeader icon={ShieldCheck} title="Habits that help" />
          <ul className="space-y-2 text-sm text-muted list-disc pl-5">
            {HABITS.map((h) => (
              <li key={h}>{h}</li>
            ))}
          </ul>
        </Card>

        <Card className="p-6">
          <CardHeader icon={Coffee} title="On a break" description="Step away from the screen: stretch, drink water, look at something far away." />
          <p className="text-sm text-muted">
            The queue locks itself for the break so nothing new arrives. When the timer ends, your exposure counter starts again from zero.
          </p>
          <Link to="/queue" className={buttonClass('secondary', 'md', 'mt-4')}>
            <HeartHandshake aria-hidden className="size-4" /> Back to the queue
          </Link>
        </Card>
      </div>
    </>
  )
}
