package com.forgeddetect.service;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Complaint;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.Decision;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.ComplaintRepository;
import com.forgeddetect.state.SharedState;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 21: self-service reports. Filing reuses the Phase 13 complaint path ({@code USER_COMPLAINT}),
 * so the SLA clock, jurisdiction rules and moderator queues all apply unchanged.
 */
@Service
public class CaseService {

    private static final String PREFIX = "FMD";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{8,15}$");
    private static final int MAX_DETAILS = 1000;

    /** Plain-language progress shown to the reporter; the frontend translates these codes. */
    public enum Stage { IN_REVIEW, REMOVED, NO_ACTION }

    /** Matches the glossary: Likely forged / Needs a closer look / Looks genuine. */
    public enum Verdict { LIKELY_FORGED, NEEDS_REVIEW, LOOKS_GENUINE }

    private final ComplianceService compliance;
    private final SlaClockService slaClock;
    private final ComplaintRepository complaints;
    private final AnalysisResultRepository results;
    private final DecisionThresholdProperties thresholds;
    private final CaseNotificationService notifications;
    private final PasswordEncoder encoder;
    private final CaseProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final PinAttemptGuard attempts;
    private final String dummyHash;

    public CaseService(
            ComplianceService compliance,
            SlaClockService slaClock,
            ComplaintRepository complaints,
            AnalysisResultRepository results,
            DecisionThresholdProperties thresholds,
            CaseNotificationService notifications,
            PasswordEncoder encoder,
            CaseProperties properties,
            Clock clock,
            SharedState state) {
        this.compliance = compliance;
        this.slaClock = slaClock;
        this.complaints = complaints;
        this.results = results;
        this.thresholds = thresholds;
        this.notifications = notifications;
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
        this.dummyHash = encoder.encode("000000");
        this.attempts = new PinAttemptGuard(state, "case", properties::getMaxPinAttempts, properties::getLockout);
    }

    public record Filing(
            byte[] image, String filename, String contentType, String relationship, String details,
            String contact, String locale, String jurisdiction) {}

    public record Created(Complaint complaint, AnalysisResult item, String pin) {}

    /** Validation failure with a machine code and a sentence the wizard can show as-is. */
    public static class InvalidFiling extends IllegalArgumentException {
        private final String code;

        public InvalidFiling(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static class TooManyAttempts extends PinAttemptGuard.TooManyAttempts {
        TooManyAttempts(long retryAfterSeconds) {
            super(retryAfterSeconds);
        }
    }

    /** Validates everything before the (slow) analysis so a bad form never creates a half-filed case. */
    public void validate(Filing f) {
        relationship(f.relationship());
        contact(f.contact());
        if (f.details() != null && f.details().length() > MAX_DETAILS) {
            throw new InvalidFiling("details_too_long", "Details must be at most " + MAX_DETAILS + " characters.");
        }
        try {
            slaClock.normalizeJurisdiction(f.jurisdiction());
        } catch (IllegalArgumentException ex) {
            throw new InvalidFiling("invalid_jurisdiction", ex.getMessage());
        }
    }

    @Transactional
    public Created file(Filing f) {
        return file(f, null);
    }

    /** @param receivedAt when the report reached the platform, if earlier than now; null starts the clock now */
    @Transactional
    public Created file(Filing f, Instant receivedAt) {
        validate(f);
        String caseNumber = newCaseNumber();
        String pin = newPin();
        AnalysisService.AnalyzeOutcome outcome = compliance.fileComplaint(
                f.image(), f.filename(), f.contentType(), ComplaintType.USER_COMPLAINT, caseNumber, f.jurisdiction(), receivedAt);

        Complaint c = new Complaint();
        c.setCaseNumber(caseNumber);
        c.setPinHash(encoder.encode(pin));
        c.setAnalysisId(outcome.result().getId());
        c.setRelationship(relationship(f.relationship()).name());
        c.setDetails(blankToNull(f.details()));
        Contact contact = contact(f.contact());
        if (contact != null) {
            c.setContact(contact.value());
            c.setContactChannel(contact.channel().name());
        }
        c.setLocale("hi".equalsIgnoreCase(f.locale()) ? "hi" : "en");
        c.setCreatedAt(receivedAt != null ? receivedAt : clock.instant());
        Complaint saved = complaints.save(c);
        notifications.statusChanged(saved, outcome.result(), stage(outcome.result()));
        return new Created(saved, outcome.result(), pin);
    }

    public record Status(Complaint complaint, AnalysisResult item) {}

    /**
     * Returns the case only when the PIN matches. Unknown numbers and wrong PINs look identical to the
     * caller, and repeated failures lock the number for {@code complaints.lockout}.
     */
    @Transactional(readOnly = true)
    public Optional<Status> status(String rawCaseNumber, String pin) {
        String number = normalizeCaseNumber(rawCaseNumber);
        String key = number != null ? number : String.valueOf(rawCaseNumber).toUpperCase(Locale.ROOT);
        try {
            attempts.checkNotLocked(key);
        } catch (PinAttemptGuard.TooManyAttempts ex) {
            throw new TooManyAttempts(ex.retryAfterSeconds());
        }

        Optional<Complaint> found = number == null ? Optional.empty() : complaints.findByCaseNumber(number);
        boolean pinOk = CaseNumbers.isPinShaped(pin)
                && encoder.matches(pin, found.map(Complaint::getPinHash).orElse(dummyHash))
                && found.isPresent();
        if (!pinOk) {
            attempts.failure(key);
            return Optional.empty();
        }
        attempts.success(key);
        return found.flatMap(c -> results.findById(c.getAnalysisId()).map(item -> new Status(c, item)));
    }

    public static Stage stage(AnalysisResult item) {
        if (item.getResolvedAt() == null || item.getDecision() == Decision.REVIEW) {
            return Stage.IN_REVIEW;
        }
        return item.getDecision() == Decision.AUTO_FLAG ? Stage.REMOVED : Stage.NO_ACTION;
    }

    public Verdict verdict(AnalysisResult item) {
        double f = item.getForgeryScore();
        if (f >= thresholds.getForgeryAutoFlag()) {
            return Verdict.LIKELY_FORGED;
        }
        return f >= thresholds.getForgeryReview() ? Verdict.NEEDS_REVIEW : Verdict.LOOKS_GENUINE;
    }

    /** Accepts lower case, spaces and missing dashes ("fmd 7k3q 9xpa"); null if it can't be a case number. */
    public static String normalizeCaseNumber(String raw) {
        return CaseNumbers.normalize(PREFIX, raw);
    }

    private String newCaseNumber() {
        return CaseNumbers.generate(PREFIX, random, complaints::existsByCaseNumber);
    }

    private String newPin() {
        return CaseNumbers.newPin(random);
    }

    private static Complaint.Relationship relationship(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidFiling("relationship_required", "Tell us whether the image shows you or someone you're helping.");
        }
        try {
            return Complaint.Relationship.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new InvalidFiling("relationship_invalid", "relationship must be SELF or ON_BEHALF.");
        }
    }

    record Contact(String value, Complaint.Channel channel) {}

    static Contact contact(String raw) {
        String c = blankToNull(raw);
        if (c == null) {
            return null;
        }
        if (EMAIL.matcher(c).matches() && c.length() <= 254) {
            return new Contact(c, Complaint.Channel.EMAIL);
        }
        String phone = c.replaceAll("[\\s()-]", "");
        if (PHONE.matcher(phone).matches()) {
            return new Contact(phone, Complaint.Channel.SMS);
        }
        throw new InvalidFiling("invalid_contact", "Enter a valid email address or phone number, or leave it empty.");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
