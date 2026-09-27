/**
 * Operator details printed in the Terms and Privacy Policy. The defaults describe the demo deployment;
 * whoever runs this for real must replace them (and have both documents reviewed by counsel).
 */
export const LEGAL = {
  operator: import.meta.env.VITE_LEGAL_OPERATOR || 'the operator of this Forged Media Detection deployment',
  contactEmail: import.meta.env.VITE_LEGAL_CONTACT_EMAIL || 'privacy@example.org',
  grievanceOfficer: import.meta.env.VITE_LEGAL_GRIEVANCE_OFFICER || 'the Grievance Officer named by the operator',
  governingLaw: import.meta.env.VITE_LEGAL_GOVERNING_LAW || 'the laws of India',
  effectiveDate: '2026-09-27',
  version: '1.0',
}
