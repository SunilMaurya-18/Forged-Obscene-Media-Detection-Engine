import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import LegalDocument from '../components/LegalDocument'
import { LEGAL } from '../lib/legal'

const AT_A_GLANCE = [
  ['The images you upload', 'Never stored. Checked in memory and discarded.'],
  ['Image fingerprints and scores', 'Stored, so re-uploads can be recognised and decisions audited.'],
  ['Check before you post', 'Only a fingerprint leaves your device. Nothing about the request is stored.'],
  ['Reports you file', 'Case number, a scrambled PIN, and the optional details and contact you choose to give.'],
  ['Tracking, ads, analytics', 'None. No third-party trackers, no advertising or analytics cookies.'],
  ['Selling data', 'Never.'],
]

const STORED = [
  {
    when: 'You check an image (Analyze page, partner API, mock feed)',
    kept: 'Perceptual hash (fingerprint), SHA-256 of the pixels, forgery and explicit scores, routing decision, generation-family estimate, a coarse numeric forensic signature (small grids of numbers that cannot be turned back into the image), provenance ID, timestamps, and for API calls the ID of the partner that sent it.',
  },
  {
    when: 'You file a report (/report)',
    kept: 'Everything above, plus the case number, your PIN as a BCrypt hash (the PIN itself is never stored), whether the image shows you or someone you are helping, that you confirmed consent, the optional details and notes you write, your optional email or phone number, the country you chose, your language, and when you were last notified.',
  },
  {
    when: 'A complaint or takedown deadline applies',
    kept: 'Complaint type and reference, the legal deadline and the rule it came from, status changes (on track, at risk, breached), escalation and resolution times.',
  },
  {
    when: 'A moderator acts',
    kept: 'The decision, when it was made, and the moderator account that made it, in an append-only audit trail.',
  },
  {
    when: 'A partner platform uses the API',
    kept: 'Client name, a SHA-256 hash of the API key and a short prefix (never the key itself), plan, and usage counters.',
  },
]

export default function Privacy() {
  const { t } = useTranslation()

  const sections = [
    {
      id: 'scope',
      title: 'Who we are and what this covers',
      body: (
        <>
          <p>
            This policy explains how {LEGAL.operator} (“we”) handles information when you use the Forged Media Detection
            website, its reporting and case-status pages, the on-device check, public verification, and the partner API.
          </p>
          <p>
            The platform is built around one rule: <strong>we do not keep the images you upload.</strong> We keep only
            fingerprints, scores and the records needed to act on complaints and prove what happened.
          </p>
        </>
      ),
    },
    {
      id: 'glance',
      title: 'At a glance',
      body: (
        <div className="overflow-x-auto rounded-xl border border-line">
          <table className="w-full text-sm">
            <tbody className="divide-y divide-line">
              {AT_A_GLANCE.map(([k, v]) => (
                <tr key={k}>
                  <th scope="row" className="text-left font-medium text-fg px-4 py-3 w-2/5 align-top">
                    {k}
                  </th>
                  <td className="px-4 py-3">{v}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ),
    },
    {
      id: 'collect',
      title: 'What we store, and when',
      body: (
        <>
          <ul>
            {STORED.map(({ when, kept }) => (
              <li key={when}>
                <strong>{when}:</strong> {kept}
              </li>
            ))}
          </ul>
          <p>
            <strong>In your browser</strong> we save your language (<code>fmd_lang</code>) and theme (<code>fmd_theme</code>)
            in local storage. Moderator sign-in is kept in session storage and is cleared when the tab is closed. We set
            no advertising or analytics cookies, and fonts and scripts are served from this site, not from third parties.
          </p>
          <p>
            <strong>Server logs.</strong> Like most websites, the web server and application may record technical logs
            (for example IP address, time, and requested page) for security and troubleshooting. Case notifications are
            logged with the contact detail masked (for example <code>r***@example.com</code>).
          </p>
        </>
      ),
    },
    {
      id: 'never',
      title: 'What we never store',
      body: (
        <ul>
          <li>The image you upload, report, verify or check. It is processed in memory and discarded.</li>
          <li>The labelled copy we give back to you. It is returned to you, not kept.</li>
          <li>An original a moderator attaches to produce a detailed report. It is checked against the stored fingerprint, used once, and discarded.</li>
          <li>Anything from <Link to="/analyze#prescreen">Check before you post</Link>. The image never leaves your device, and the fingerprint it sends is compared and forgotten.</li>
          <li>Your location from your IP address. The legal deadline uses the country you choose, never a guess.</li>
          <li>Your PIN in readable form.</li>
        </ul>
      ),
    },
    {
      id: 'use',
      title: 'Why we use it',
      body: (
        <ul>
          <li><strong>Detecting and routing</strong> forged or explicit images so clear cases are handled automatically and uncertain ones go to a person.</li>
          <li><strong>Acting on complaints within the legal deadline</strong> (for example India’s IT Rules and the US Take It Down Act), which is a legal obligation for the platforms we serve.</li>
          <li><strong>Recognising re-uploads</strong> of content that was already removed.</li>
          <li><strong>Keeping evidence</strong>: the audit trail and signed reports let anyone confirm what was decided and when.</li>
          <li><strong>Telling you about your case</strong>, only if you gave a contact detail, and only about that case.</li>
          <li><strong>Keeping the service secure</strong>: rate limits, PIN lockouts and abuse prevention.</li>
        </ul>
      ),
    },
    {
      id: 'basis',
      title: 'Consent and legal basis',
      body: (
        <>
          <p>
            When you file a report you confirm that the image shows you, or that you are acting for the person in it with
            their permission, and you consent to us processing the report as described here. Contact details and notes are
            optional.
          </p>
          <p>
            Other processing relies on legal obligations (takedown deadlines and record-keeping), on the legitimate interest
            of preventing the spread of harmful manipulated imagery, and, for partner platforms, on their contract with us.
            Where India’s Digital Personal Data Protection Act 2023, the EU/UK GDPR, or similar laws apply, you have the rights
            described in <a href="#rights">Your rights</a>.
          </p>
        </>
      ),
    },
    {
      id: 'share',
      title: 'Who can see it',
      body: (
        <ul>
          <li><strong>Moderators</strong> see scores, decisions, deadlines and a blurred placeholder generated from the fingerprint. They never see your image, and they do not see your contact detail.</li>
          <li><strong>You</strong> can see your case stage, deadline and timeline with your case number and PIN.</li>
          <li><strong>Anyone</strong> with a provenance ID can see that item’s public record on the Verify page: decision, scores, fingerprints and a history of events. Complaint references and moderator names are left out.</li>
          <li><strong>A notification service chosen by the operator</strong> may receive your contact detail and the message, only to deliver a status update you asked for.</li>
          <li><strong>Authorities</strong>, when the law requires it, for example a valid court or government order.</li>
        </ul>
      ),
    },
    {
      id: 'retention',
      title: 'How long we keep it',
      body: (
        <>
          <p>
            Images are discarded as soon as they are processed. Fingerprints, scores, decisions and the audit trail are kept
            so re-uploads can be recognised and decisions can be proven later. This version of the software does not delete
            records automatically; the operator decides the retention period and deletes records when it ends.
          </p>
          <p>Your contact detail is used only for updates about your case. You can ask for it to be removed at any time.</p>
        </>
      ),
    },
    {
      id: 'security',
      title: 'How we protect it',
      body: (
        <ul>
          <li>PINs are stored as BCrypt hashes and API keys as SHA-256 hashes, so neither can be read back.</li>
          <li>After 5 wrong PINs a case is locked for 15 minutes, and an unknown case and a wrong PIN look the same.</li>
          <li>Provenance labels are signed (HMAC-SHA256) and evidence reports are digitally signed (Ed25519).</li>
          <li>The detection service and database are not reachable from the internet.</li>
          <li>No system is perfectly secure. If we learn of a breach affecting you, we will tell you and the relevant authority as the law requires.</li>
        </ul>
      ),
    },
    {
      id: 'rights',
      title: 'Your rights',
      body: (
        <>
          <p>Depending on where you live, you can ask us to:</p>
          <ul>
            <li>tell you what we hold about you and give you a copy;</li>
            <li>correct details that are wrong;</li>
            <li>delete your contact detail and notes, or withdraw your consent;</li>
            <li>stop sending you notifications;</li>
            <li>explain how a decision about your report was made.</li>
          </ul>
          <p>
            Some records, such as the fact that a takedown happened and when, may have to be kept to meet legal obligations.
            If so, we will tell you. Email <a href={`mailto:${LEGAL.contactEmail}`}>{LEGAL.contactEmail}</a> with your case
            number. You can also complain to your data-protection authority (in India, the Data Protection Board).
          </p>
        </>
      ),
    },
    {
      id: 'children',
      title: 'Children',
      body: (
        <p>
          If an image shows someone under 18, please do not share it further. Report it here and also to the authorities:
          in India at <a href="https://cybercrime.gov.in" target="_blank" rel="noreferrer">cybercrime.gov.in</a>, in the US to
          the <a href="https://report.cybertip.org" target="_blank" rel="noreferrer">NCMEC CyberTipline</a>, or to your local
          police.
        </p>
      ),
    },
    {
      id: 'changes',
      title: 'Changes to this policy',
      body: <p>If we change this policy we will update the effective date above and, for significant changes, show a notice on the site before they take effect.</p>,
    },
    {
      id: 'contact',
      title: 'Contact and grievances',
      body: (
        <p>
          Questions, requests and complaints go to {LEGAL.grievanceOfficer} at{' '}
          <a href={`mailto:${LEGAL.contactEmail}`}>{LEGAL.contactEmail}</a>. We acknowledge grievances within 24 hours and
          aim to resolve them within the time set by applicable law.
        </p>
      ),
    },
  ]

  return (
    <LegalDocument
      eyebrow={t('legal.eyebrow')}
      title={t('legal.privacy.title')}
      description={t('legal.privacy.description')}
      sections={sections}
      other={{ to: '/terms', label: t('legal.terms.title') }}
    />
  )
}
