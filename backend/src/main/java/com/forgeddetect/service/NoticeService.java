package com.forgeddetect.service;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.state.SharedState;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 23: every upload gets a statement-of-reasons notice number ({@code UPL-XXXX-XXXX}) and a PIN,
 * returned once. The uploader uses them to check the decision and to appeal it (DSA Art. 17/20 style).
 * Only the BCrypt hash of the PIN is stored.
 */
@Service
public class NoticeService {

    public static final String PREFIX = "UPL";

    public record Notice(String noticeNumber, String pin) {}

    private final AnalysisResultRepository results;
    private final PasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();
    private final PinAttemptGuard attempts;
    private final String dummyHash;

    public NoticeService(
            AnalysisResultRepository results, PasswordEncoder encoder, CaseProperties properties, SharedState state) {
        this.results = results;
        this.encoder = encoder;
        this.attempts = new PinAttemptGuard(state, "notice", properties::getMaxPinAttempts, properties::getLockout);
        this.dummyHash = encoder.encode("000000");
    }

    /** Assigns a notice number and PIN to {@code item} (caller saves it). */
    public Notice issue(AnalysisResult item) {
        String number = CaseNumbers.generate(PREFIX, random, results::existsByNoticeNumber);
        String pin = CaseNumbers.newPin(random);
        item.setNoticeNumber(number);
        item.setNoticePinHash(encoder.encode(pin));
        return new Notice(number, pin);
    }

    public static String normalize(String raw) {
        return CaseNumbers.normalize(PREFIX, raw);
    }

    /**
     * The item only when the PIN matches. Unknown numbers and wrong PINs look the same, and repeated
     * failures lock the number.
     *
     * @throws PinAttemptGuard.TooManyAttempts while the number is locked
     */
    @Transactional(readOnly = true)
    public Optional<AnalysisResult> verify(String rawNumber, String pin) {
        String number = normalize(rawNumber);
        String key = number != null ? number : String.valueOf(rawNumber).toUpperCase(Locale.ROOT);
        attempts.checkNotLocked(key);
        Optional<AnalysisResult> found = number == null ? Optional.empty() : results.findByNoticeNumber(number);
        boolean ok = CaseNumbers.isPinShaped(pin)
                && encoder.matches(pin, found.map(AnalysisResult::getNoticePinHash).filter(h -> h != null).orElse(dummyHash))
                && found.isPresent()
                && found.get().getNoticePinHash() != null;
        if (!ok) {
            attempts.failure(key);
            return Optional.empty();
        }
        attempts.success(key);
        return found;
    }
}
