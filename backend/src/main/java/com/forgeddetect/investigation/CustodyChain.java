package com.forgeddetect.investigation;

import com.forgeddetect.signing.Ed25519Keys;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Phase 27: the hash chain over a case's custody log. Every event stores
 * {@code SHA-256(previous hash + "\n" + canonical event)}; the first event chains from {@link #GENESIS}.
 * Changing any stored field of an event breaks its own hash, deleting one leaves a gap in the
 * sequence numbers, and dropping the newest ones no longer reaches the head recorded on the case.
 */
public final class CustodyChain {

    public static final String VERSION = "fmd-custody-event/v1";
    public static final String GENESIS = "0".repeat(64);

    /** One stored event, with the fields that go into its canonical form. */
    public record Entry(
            Long id, Integer seq, String occurredAt, String actor, String type,
            Long analysisId, Long sightingId, String detail, String prevHash, String hash) {

        static Entry of(InvestigationEvent e) {
            return new Entry(e.getId(), e.getSeq(), e.getOccurredAt().toString(), e.getActor(),
                    e.getType() != null ? e.getType().name() : null, e.getAnalysisId(), e.getSightingId(),
                    e.getDetail(), e.getPrevHash(), e.getHash());
        }
    }

    /**
     * A signed commitment to the chain at some length, e.g. a preservation snapshot: the event at
     * {@code length} must still have hash {@code head}.
     */
    public record Anchor(String label, int length, String head) {}

    /** {@code brokenAt}: sequence number of the first bad link (null if valid). */
    public record Result(boolean valid, int length, String head, Integer brokenAt, Long brokenEventId, String problem) {}

    private CustodyChain() {}

    /** Fixed field order, one {@code key=value} per line, UTF-8. Changing this means a new version string. */
    public static String canonical(String caseNumber, Entry e) {
        return VERSION + '\n'
                + "case=" + escape(caseNumber) + '\n'
                + "seq=" + str(e.seq()) + '\n'
                + "occurred_at=" + escape(e.occurredAt()) + '\n'
                + "actor=" + escape(e.actor()) + '\n'
                + "type=" + escape(e.type()) + '\n'
                + "analysis_id=" + str(e.analysisId()) + '\n'
                + "sighting_id=" + str(e.sightingId()) + '\n'
                + "detail=" + escape(e.detail()) + '\n';
    }

    public static String link(String prevHash, String canonical) {
        return HexFormat.of().formatHex(Ed25519Keys.sha256((prevHash + "\n" + canonical).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Recomputes the chain from the events' contents alone (ignoring stored hashes) up to {@code length}.
     *
     * @return the head hash, or null if an event in that range is missing
     */
    public static String recomputeHead(String caseNumber, List<Entry> ordered, int length) {
        String prev = GENESIS;
        int expected = 1;
        for (Entry e : ordered) {
            if (expected > length) {
                break;
            }
            if (e.seq() == null || e.seq() != expected) {
                return null;
            }
            prev = link(prev, canonical(caseNumber, e));
            expected++;
        }
        return expected > length ? prev : null;
    }

    /**
     * @param ordered events by sequence number (unsealed ones last)
     * @param expectedLength and {@code expectedHead}: what the case row says the chain ends at
     */
    public static Result verify(String caseNumber, List<Entry> ordered, Integer expectedLength, String expectedHead, List<Anchor> anchors) {
        String prev = GENESIS;
        int expected = 1;
        Map<Integer, String> hashAt = new HashMap<>();
        for (Entry e : ordered) {
            if (e.seq() == null) {
                return broken(expected - 1, prev, expected, e.id(), "An event (id " + e.id() + ") was added to the log without a place in the chain.");
            }
            if (e.seq() > expected) {
                return broken(expected - 1, prev, expected, null,
                        "Event " + expected + " is missing: it was deleted from the log.");
            }
            if (e.seq() < expected) {
                return broken(expected - 1, prev, e.seq(), e.id(),
                        "Two events claim position " + e.seq() + ": one was inserted or copied into the log.");
            }
            if (!prev.equals(e.prevHash())) {
                return broken(expected - 1, prev, expected, e.id(),
                        "Event " + expected + " no longer points to the event before it: the log was reordered or an event was replaced.");
            }
            String computed = link(prev, canonical(caseNumber, e));
            if (!computed.equals(e.hash())) {
                return broken(expected - 1, prev, expected, e.id(),
                        "Event " + expected + " (" + e.type() + ") was changed after it was recorded: its contents no longer match its hash.");
            }
            hashAt.put(expected, computed);
            prev = computed;
            expected++;
        }
        int length = expected - 1;
        int recorded = expectedLength == null ? 0 : expectedLength;
        if (length < recorded) {
            return broken(length, prev, length + 1, null, "The log ends at event " + length + " but the case recorded " + recorded
                    + ": the newest " + (recorded - length == 1 ? "event was" : (recorded - length) + " events were") + " deleted.");
        }
        if (length > recorded) {
            return broken(length, prev, recorded + 1, null, "The log has " + length + " events but the case recorded " + recorded
                    + ": events were added outside the application.");
        }
        if (!(length == 0 ? GENESIS : prev).equals(expectedHead == null ? GENESIS : expectedHead)) {
            return broken(length, prev, length, null, "The newest event does not match the head recorded on the case.");
        }
        for (Anchor a : anchors) {
            String at = a.length() == 0 ? GENESIS : hashAt.get(a.length());
            if (at == null || !at.equals(a.head())) {
                return broken(length, prev, Math.max(1, a.length()), null, a.label() + " committed to the log at event " + a.length()
                        + ", and the log no longer matches it: events up to there were rewritten after it was signed.");
            }
        }
        return new Result(true, length, length == 0 ? GENESIS : prev, null, null, null);
    }

    private static Result broken(int length, String head, int at, Long eventId, String problem) {
        return new Result(false, length, head, at, eventId, problem);
    }

    static String escape(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }
}
