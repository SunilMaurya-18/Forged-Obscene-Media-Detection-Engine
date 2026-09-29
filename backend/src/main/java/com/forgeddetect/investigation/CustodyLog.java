package com.forgeddetect.investigation;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 27: writes and checks the hash-chained custody log of each case. Appends take a row lock on
 * the case, so two events written at once queue up instead of both claiming the same position.
 */
@Component
public class CustodyLog {

    private static final Logger log = LoggerFactory.getLogger(CustodyLog.class);
    static final int MAX_ACTOR = 64;
    static final int MAX_DETAIL = 512;

    private final InvestigationEventRepository events;
    private final InvestigationRepository cases;
    private final PreservationRepository preservations;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CustodyLog(
            InvestigationEventRepository events,
            InvestigationRepository cases,
            PreservationRepository preservations,
            EntityManager em,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.events = events;
        this.cases = cases;
        this.preservations = preservations;
        this.em = em;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /**
     * Reloads the case under a row lock held until the transaction ends. Call before changing the case,
     * so the change and its custody event are made against the latest state.
     */
    public Investigation lock(Investigation inv) {
        em.refresh(inv, LockModeType.PESSIMISTIC_WRITE);
        return inv;
    }

    /** Appends a sealed event and moves the case's custody head. Must run inside a transaction. */
    public InvestigationEvent append(
            Investigation inv, String actor, InvestigationEventType type, Long analysisId, Long sightingId, String detail) {
        em.lock(inv, LockModeType.PESSIMISTIC_WRITE);
        if (inv.getCustodyLength() == null) {
            sealLegacy(inv);
        }
        Optional<InvestigationEvent> last = events.findTopByInvestigationIdAndSeqIsNotNullOrderBySeqDesc(inv.getId());
        int seq = last.map(e -> e.getSeq() + 1).orElse(1);
        String prev = last.map(InvestigationEvent::getHash).orElse(CustodyChain.GENESIS);
        // Stored timestamps keep microseconds, so the hash must be computed over the same precision
        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        InvestigationEvent e = new InvestigationEvent(inv.getId(), at, clip(actor, MAX_ACTOR), type, analysisId, sightingId, clip(detail, MAX_DETAIL));
        String hash = CustodyChain.link(prev, CustodyChain.canonical(inv.getCaseNumber(), entry(e, seq, prev)));
        e.seal(seq, prev, hash);
        events.save(e);
        inv.advanceCustody(seq, hash);
        return e;
    }

    /** Length and newest hash of the chain as recorded on the (locked) case; seals a pre-Phase-27 log first. */
    public CustodyChain.Anchor head(Investigation inv) {
        em.lock(inv, LockModeType.PESSIMISTIC_WRITE);
        if (inv.getCustodyLength() == null) {
            sealLegacy(inv);
        }
        return new CustodyChain.Anchor(inv.getCaseNumber(), inv.getCustodyLength(), inv.getCustodyHead());
    }

    /** Checks the whole chain against the head on the case row and every preservation's signed head. */
    public CustodyChain.Result verify(Investigation inv) {
        List<CustodyChain.Entry> chain = events.findChain(inv.getId()).stream().map(CustodyChain.Entry::of).toList();
        List<CustodyChain.Anchor> anchors = preservations.findByInvestigationIdOrderByPreservedAtAscIdAsc(inv.getId()).stream()
                .map(p -> new CustodyChain.Anchor("Preservation " + p.getPreservationId(), p.getCustodyLength(), p.getCustodyHead()))
                .toList();
        return CustodyChain.verify(inv.getCaseNumber(), chain, inv.getCustodyLength(), inv.getCustodyHead(), anchors);
    }

    /** The head recomputed from event contents alone, up to {@code length}; null if an event in range is gone. */
    public String recomputeHead(Investigation inv, int length) {
        if (length == 0) {
            return CustodyChain.GENESIS;
        }
        List<CustodyChain.Entry> chain = events.findChain(inv.getId()).stream().map(CustodyChain.Entry::of).toList();
        return CustodyChain.recomputeHead(inv.getCaseNumber(), chain, length);
    }

    public List<InvestigationEvent> events(Investigation inv) {
        return events.findChain(inv.getId());
    }

    // ------------------------------------------------------------------ cases from before Phase 27

    /**
     * Seals the logs of cases opened before the chain existed, oldest event first. Only cases that
     * were never sealed qualify: an unsealed event on a sealed case is reported by verification, not
     * absorbed into the chain.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void sealLegacyCases() {
        int sealed = 0;
        for (Investigation candidate : cases.findByCustodyLengthIsNull()) {
            Boolean done = tx.execute(s -> cases.findById(candidate.getId()).map(inv -> {
                lock(inv);
                if (inv.getCustodyLength() != null) {
                    return false;
                }
                sealLegacy(inv);
                return true;
            }).orElse(false));
            if (Boolean.TRUE.equals(done)) {
                sealed++;
            }
        }
        if (sealed > 0) {
            log.info("Sealed the custody log of {} case(s) opened before the hash chain existed", sealed);
        }
    }

    private void sealLegacy(Investigation inv) {
        String prev = CustodyChain.GENESIS;
        int seq = 0;
        for (InvestigationEvent e : events.findByInvestigationIdOrderByOccurredAtAscIdAsc(inv.getId())) {
            seq++;
            String hash = CustodyChain.link(prev, CustodyChain.canonical(inv.getCaseNumber(), entry(e, seq, prev)));
            e.seal(seq, prev, hash);
            prev = hash;
        }
        events.flush();
        inv.advanceCustody(seq, prev);
    }

    private static CustodyChain.Entry entry(InvestigationEvent e, int seq, String prev) {
        return new CustodyChain.Entry(e.getId(), seq, e.getOccurredAt().toString(), e.getActor(),
                e.getType().name(), e.getAnalysisId(), e.getSightingId(), e.getDetail(), prev, null);
    }

    // ------------------------------------------------------------------ views

    public static Map<String, Object> verificationView(CustodyChain.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.valid() ? "VALID" : "BROKEN");
        m.put("length", r.length());
        m.put("head", r.head());
        m.put("brokenAt", r.brokenAt());
        m.put("brokenEventId", r.brokenEventId());
        m.put("message", r.valid()
                ? "All " + r.length() + " custody events chain correctly from the first to the head recorded on the case."
                : r.problem());
        return m;
    }

    public static Map<String, Object> eventView(InvestigationEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", e.getSeq());
        m.put("occurredAt", e.getOccurredAt().toString());
        m.put("actor", e.getActor());
        m.put("type", e.getType() != null ? e.getType().name() : null);
        m.put("analysisId", e.getAnalysisId());
        m.put("sightingId", e.getSightingId());
        m.put("detail", e.getDetail());
        m.put("prevHash", e.getPrevHash());
        m.put("hash", e.getHash());
        return m;
    }

    static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
