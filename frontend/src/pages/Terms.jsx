import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import LegalDocument from '../components/LegalDocument'
import { LEGAL } from '../lib/legal'

export default function Terms() {
  const { t } = useTranslation()

  const sections = [
    {
      id: 'acceptance',
      title: 'Agreeing to these terms',
      body: (
        <>
          <p>
            These terms are an agreement between you and {LEGAL.operator} (“we”) for using the Forged Media Detection website,
            reporting and case-status pages, verification pages, moderator tools, and partner API (together, “the service”).
            By using the service you agree to them. If you do not agree, please do not use it.
          </p>
          <p>
            How we handle information is explained in the <Link to="/privacy">Privacy Policy</Link>, which forms part of these
            terms.
          </p>
        </>
      ),
    },
    {
      id: 'service',
      title: 'What the service does',
      body: (
        <ul>
          <li>Scores images for signs of face manipulation and for explicit content, and routes them to allow, human review, or automatic flagging.</li>
          <li>Lets people affected by fake or intimate images report them without an account and track the case.</li>
          <li>Tracks legal takedown deadlines, keeps an audit trail, and produces signed evidence reports.</li>
          <li>Lets anyone check an image against known abusive content on their own device, and verify provenance labels and reports.</li>
        </ul>
      ),
    },
    {
      id: 'accuracy',
      title: 'Results are estimates, not facts',
      body: (
        <>
          <p>
            <strong>Scores and verdicts are probabilities produced by machine-learning models. They can be wrong.</strong> A
            “Likely forged” verdict does not prove an image is fake, and “Looks genuine” does not prove it is real. The models
            have so far been evaluated on research and synthetic data, not established on real-world content, and new image
            generators may evade them.
          </p>
          <p>
            Uncertain cases are sent to a person, and final takedown decisions are made by the platform operator under its own
            policies and the law. Do not rely on a result alone for legal, employment, medical or other important decisions.
            Nothing in the service is legal advice.
          </p>
        </>
      ),
    },
    {
      id: 'use',
      title: 'Acceptable use',
      body: (
        <>
          <p>You may use the service only lawfully and in good faith. You must not:</p>
          <ul>
            <li>use it to create, improve, test or spread deepfakes or intimate images of anyone;</li>
            <li>probe the models to find ways around detection, or try to reverse fingerprints into images;</li>
            <li>file false, malicious or bulk reports, or report on someone’s behalf without their permission;</li>
            <li>upload images you have no lawful reason to hold, except to report them for removal;</li>
            <li>harass, threaten or identify anyone through the service;</li>
            <li>overload, scrape, or get around rate limits, PIN lockouts, access controls or security measures;</li>
            <li>misrepresent a score, label or report, or alter a signed report or labelled file.</li>
          </ul>
          <p>
            If an image shows someone under 18, do not share it further. Report it here and to the authorities (see the{' '}
            <Link to="/privacy#children">Privacy Policy</Link>).
          </p>
        </>
      ),
    },
    {
      id: 'reports',
      title: 'Reports and case tracking',
      body: (
        <ul>
          <li>When you file a report you confirm that the information is true to the best of your knowledge, and that the image shows you or you have permission to act for the person in it.</li>
          <li>Your case number and PIN are the only way to see your case. Keep them private. The PIN is shown once and cannot be recovered.</li>
          <li>Legal deadlines shown on the site are the time limits set by the chosen country’s rules. We work to meet them but cannot guarantee a particular outcome or timing.</li>
          <li>Removing an image from one platform does not remove copies elsewhere. You may also wish to contact the police or a support organisation.</li>
        </ul>
      ),
    },
    {
      id: 'accounts',
      title: 'Moderator accounts',
      body: (
        <p>
          Moderator accounts are issued by the operator. Keep your credentials secret, use them only for your moderation duties,
          and treat everything you see as confidential. Every decision is recorded with your account in the audit trail.
        </p>
      ),
    },
    {
      id: 'api',
      title: 'Partner API',
      body: (
        <ul>
          <li>API keys are issued by the operator. Keep them secret; you are responsible for all use of your key.</li>
          <li>Each key has a plan with a rate limit. Requests over the limit are refused until the limit resets.</li>
          <li>You must tell your own users that their uploads are screened, and follow applicable law when you act on results.</li>
          <li>We may rotate, suspend or revoke a key that is leaked, abused, or used against these terms. Full details are in the API documentation.</li>
        </ul>
      ),
    },
    {
      id: 'evidence',
      title: 'Provenance labels and signed reports',
      body: (
        <p>
          A provenance label records what the service found when it analysed an image. A signed report proves that the recorded
          facts have not changed since signing. Neither proves that the content of an image is true or false. Verification
          results reflect the records held at the time you check.
        </p>
      ),
    },
    {
      id: 'ip',
      title: 'Your content and our software',
      body: (
        <p>
          You keep all rights to the images you submit. You allow us to process each image only for as long as needed to analyse
          it, then it is discarded. The software, design and documentation of the service belong to their authors and are used
          under their licence terms.
        </p>
      ),
    },
    {
      id: 'liability',
      title: 'Disclaimers and liability',
      body: (
        <>
          <p>
            The service is provided “as is” and “as available”, without warranties of accuracy, availability or fitness for a
            particular purpose, to the extent the law allows.
          </p>
          <p>
            To the extent the law allows, we are not liable for indirect or consequential loss, or for loss caused by relying on
            a score or verdict alone. Nothing in these terms limits liability that cannot be limited by law.
          </p>
        </>
      ),
    },
    {
      id: 'termination',
      title: 'Suspension',
      body: <p>We may suspend or restrict access for anyone who breaks these terms or puts others at risk. Reports already filed will still be handled as the law requires.</p>,
    },
    {
      id: 'changes',
      title: 'Changes',
      body: <p>We may update these terms. The effective date above shows when they last changed, and significant changes will be announced on the site before they take effect.</p>,
    },
    {
      id: 'law',
      title: 'Governing law and contact',
      body: (
        <p>
          These terms are governed by {LEGAL.governingLaw}. Questions and grievances go to {LEGAL.grievanceOfficer} at{' '}
          <a href={`mailto:${LEGAL.contactEmail}`}>{LEGAL.contactEmail}</a>.
        </p>
      ),
    },
  ]

  return (
    <LegalDocument
      eyebrow={t('legal.eyebrow')}
      title={t('legal.terms.title')}
      description={t('legal.terms.description')}
      sections={sections}
      other={{ to: '/privacy', label: t('legal.privacy.title') }}
    />
  )
}
