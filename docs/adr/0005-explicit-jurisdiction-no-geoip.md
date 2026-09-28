# 0005. Jurisdiction is stated explicitly, never inferred from geo-IP

- **Status:** Accepted
- **Date:** 2026-09-28 (recorded in Phase 25; jurisdiction-aware SLAs arrived in Phase 19)

## Context

Takedown deadlines depend on law. India's amended IT Rules set short windows for intimate-imagery
complaints and for government orders; the US TAKE IT DOWN Act gives 48 hours; the EU's DSA and the
UK's Online Safety Act set their own expectations; appeals have their own windows. The compliance clock, escalation and transparency
reporting all need to know *which* rules apply to an item.

The tempting shortcut is to look up the requester's IP address. It is wrong here, for several reasons:

- **It measures the wrong thing.** Which law applies depends on the platform's establishment, where
  the victim lives and where the content is served, not on where one HTTP request came from.
- **It fails the people who most need it.** Victims under threat often use VPNs, Tor or shared
  connections. Geo-IP would silently apply the wrong, often slower, rules to them.
- **It is not auditable.** A deadline derived from a lookup database cannot be explained in a
  compliance report or defended to a regulator. Geo-IP databases change and are often wrong at the
  country level for mobile carriers.
- **It is personal data.** Storing or processing IP-derived location is exactly the kind of collection
  the system avoids ([ADR 0001](0001-no-raw-media-persistence.md) applies the same instinct to media).

## Decision

- Jurisdiction is **always an explicit input**: a `jurisdiction` field on complaints, uploads and API
  calls, chosen by the complainant, the moderator or the calling platform. When absent, the operator's
  configured default applies (`COMPLIANCE_DEFAULT_JURISDICTION`, default `IN`).
- The value is normalised (upper-cased ISO 3166 code; `UK` is treated as `GB`) and stored on the item.
  The stored value, not a later lookup, drives every clock.
- Rules resolve in a fixed, explainable order: the exact (jurisdiction, complaint type) row, then `EU`
  for EU member states, then `GENERIC`, then the configured default window. The chosen rule's label is
  recorded on the item (`slaRuleLabel`) and shown in the queue, compliance views and reports.
- No component reads client IP addresses for location. The codebase has no geo-IP library or lookup.

## Consequences

**Easier**
- Every deadline can be explained: "India IT Rules 2026, user complaint, jurisdiction chosen by the
  complainant".
- VPN and Tor users get the rules they ask for.
- Tests and demos are deterministic. The demo story sets `DEMO_JURISDICTION=IN`, whatever the visitor's
  network.

**Harder, and accepted**
- A complainant can pick a jurisdiction with a shorter deadline than the one that strictly applies.
  We accept this: over-serving a victim is better than under-serving one. There is no in-app way to
  change an item's jurisdiction afterwards; a correction today means refiling.
- A platform that forgets to pass `jurisdiction` gets the operator default, which may be wrong for its
  users. Reports and compliance views print the rule used, so a wrong default is visible.
- Adding a jurisdiction means adding rule rows (`JurisdictionRuleSeeder`), not just trusting a lookup.

## Enforced by

- `backend/src/main/java/com/forgeddetect/service/SlaClockService.java` (`normalizeJurisdiction`,
  rule resolution)
- `backend/src/main/java/com/forgeddetect/config/JurisdictionRuleSeeder.java`
- `AnalysisResult.jurisdiction` and `slaRuleLabel` columns
- Frontend jurisdiction selectors on the complaint wizard (`ReportWizard.jsx`) and Compliance
- Compliance and jurisdiction integration tests in the backend suite

## Revisit when

- A regulator requires location-based rules for a specific duty. Even then, the location should come
  from an explicit, audited declaration (for example a user's account country), not from the request IP.
