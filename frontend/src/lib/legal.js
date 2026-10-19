/**
 * Operator details printed in the Terms and Privacy Policy, set at build time (VITE_LEGAL_*).
 * Replace the placeholders before launch and have both documents reviewed by counsel.
 */
const env = import.meta.env

export const LEGAL = {
  operator: env.VITE_LEGAL_OPERATOR || 'the operator of this Forged Media Detection deployment',
  contactEmail: env.VITE_LEGAL_CONTACT_EMAIL || 'privacy@example.org',
  grievanceOfficer: env.VITE_LEGAL_GRIEVANCE_OFFICER || 'the Grievance Officer named by the operator',
  governingLaw: env.VITE_LEGAL_GOVERNING_LAW || 'the laws of India',
  effectiveDate: env.VITE_LEGAL_EFFECTIVE_DATE || '2026-09-27',
  version: '1.0',
  configured: Boolean(env.VITE_LEGAL_OPERATOR && env.VITE_LEGAL_CONTACT_EMAIL),
}
