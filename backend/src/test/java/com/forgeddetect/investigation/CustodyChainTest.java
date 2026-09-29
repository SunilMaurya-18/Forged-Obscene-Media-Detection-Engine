package com.forgeddetect.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 27: every way of editing, removing or adding a custody event breaks verification. */
class CustodyChainTest {

    private static final String CASE = "INV-TEST-2345";

    @Test
    void anIntactChainVerifies() {
        List<CustodyChain.Entry> chain = chain(4);
        CustodyChain.Result r = CustodyChain.verify(CASE, chain, 4, chain.get(3).hash(), List.of());
        assertThat(r.valid()).isTrue();
        assertThat(r.length()).isEqualTo(4);
        assertThat(r.head()).isEqualTo(chain.get(3).hash());
        assertThat(chain.get(0).prevHash()).isEqualTo(CustodyChain.GENESIS);
        assertThat(CustodyChain.recomputeHead(CASE, chain, 4)).isEqualTo(chain.get(3).hash());
        assertThat(CustodyChain.recomputeHead(CASE, chain, 2)).isEqualTo(chain.get(1).hash());
    }

    @Test
    void anEditedEventIsNamed() {
        List<CustodyChain.Entry> chain = chain(4);
        CustodyChain.Entry e = chain.get(1);
        chain.set(1, new CustodyChain.Entry(e.id(), e.seq(), e.occurredAt(), "someone-else", e.type(),
                e.analysisId(), e.sightingId(), e.detail(), e.prevHash(), e.hash()));
        CustodyChain.Result r = CustodyChain.verify(CASE, chain, 4, chain.get(3).hash(), List.of());
        assertThat(r.valid()).isFalse();
        assertThat(r.brokenAt()).isEqualTo(2);
        assertThat(r.brokenEventId()).isEqualTo(e.id());
        assertThat(r.problem()).contains("changed after it was recorded");
    }

    @Test
    void aDeletedEventLeavesAGap() {
        List<CustodyChain.Entry> chain = chain(4);
        chain.remove(2);
        CustodyChain.Result r = CustodyChain.verify(CASE, chain, 4, chain.get(2).hash(), List.of());
        assertThat(r.valid()).isFalse();
        assertThat(r.brokenAt()).isEqualTo(3);
        assertThat(r.problem()).contains("Event 3 is missing");
    }

    @Test
    void droppingTheNewestEventsMissesTheRecordedHead() {
        List<CustodyChain.Entry> chain = chain(4);
        String head = chain.get(3).hash();
        chain.remove(3);
        CustodyChain.Result r = CustodyChain.verify(CASE, chain, 4, head, List.of());
        assertThat(r.valid()).isFalse();
        assertThat(r.brokenAt()).isEqualTo(4);
        assertThat(r.problem()).contains("newest event was deleted");
    }

    @Test
    void extraOrReorderedEventsAreCaught() {
        List<CustodyChain.Entry> chain = chain(3);
        List<CustodyChain.Entry> extra = new ArrayList<>(chain);
        extra.add(new CustodyChain.Entry(99L, null, "2026-09-29T10:00:00Z", "x", "NOTE_ADDED", null, null, "slipped in", null, null));
        assertThat(CustodyChain.verify(CASE, extra, 3, chain.get(2).hash(), List.of()).problem()).contains("without a place in the chain");

        List<CustodyChain.Entry> swapped = new ArrayList<>(chain);
        CustodyChain.Entry a = chain.get(1);
        CustodyChain.Entry b = chain.get(2);
        swapped.set(1, new CustodyChain.Entry(b.id(), 2, b.occurredAt(), b.actor(), b.type(), b.analysisId(), b.sightingId(), b.detail(), b.prevHash(), b.hash()));
        swapped.set(2, new CustodyChain.Entry(a.id(), 3, a.occurredAt(), a.actor(), a.type(), a.analysisId(), a.sightingId(), a.detail(), a.prevHash(), a.hash()));
        CustodyChain.Result r = CustodyChain.verify(CASE, swapped, 3, chain.get(2).hash(), List.of());
        assertThat(r.valid()).isFalse();
        assertThat(r.brokenAt()).isEqualTo(2);
    }

    @Test
    void aFullyRewrittenChainIsCaughtByASignedAnchor() {
        List<CustodyChain.Entry> original = chain(3);
        CustodyChain.Anchor anchor = new CustodyChain.Anchor("Preservation PRS-TEST-2345", 2, original.get(1).hash());

        // Rewrite event 1 and recompute every hash so the chain is internally consistent again
        List<CustodyChain.Entry> rewritten = new ArrayList<>();
        String prev = CustodyChain.GENESIS;
        for (CustodyChain.Entry e : original) {
            String detail = e.seq() == 1 ? "Opened by nobody in particular" : e.detail();
            CustodyChain.Entry draft = new CustodyChain.Entry(e.id(), e.seq(), e.occurredAt(), e.actor(), e.type(), e.analysisId(), e.sightingId(), detail, prev, null);
            String hash = CustodyChain.link(prev, CustodyChain.canonical(CASE, draft));
            rewritten.add(new CustodyChain.Entry(e.id(), e.seq(), e.occurredAt(), e.actor(), e.type(), e.analysisId(), e.sightingId(), detail, prev, hash));
            prev = hash;
        }
        assertThat(CustodyChain.verify(CASE, rewritten, 3, prev, List.of()).valid()).as("consistent without the anchor").isTrue();
        CustodyChain.Result r = CustodyChain.verify(CASE, rewritten, 3, prev, List.of(anchor));
        assertThat(r.valid()).isFalse();
        assertThat(r.problem()).contains("PRS-TEST-2345").contains("rewritten after it was signed");
    }

    @Test
    void theCanonicalFormEscapesNewlinesAndBindsTheCase() {
        CustodyChain.Entry e = new CustodyChain.Entry(1L, 1, "2026-09-29T10:00:00Z", "moderator", "NOTE_ADDED", null, null,
                "line one\nactor=forged", CustodyChain.GENESIS, null);
        String canonical = CustodyChain.canonical(CASE, e);
        assertThat(canonical).startsWith(CustodyChain.VERSION + "\n").contains("detail=line one\\nactor=forged\n");
        assertThat(canonical.lines().filter(l -> l.startsWith("actor=")).count()).isEqualTo(1);
        assertThat(CustodyChain.link(CustodyChain.GENESIS, canonical))
                .isNotEqualTo(CustodyChain.link(CustodyChain.GENESIS, CustodyChain.canonical("INV-OTHR-2345", e)));
    }

    private static List<CustodyChain.Entry> chain(int n) {
        List<CustodyChain.Entry> out = new ArrayList<>();
        String prev = CustodyChain.GENESIS;
        String[] types = {"OPENED", "ITEM_ADDED", "SIGHTING_LINKED", "NOTE_ADDED"};
        for (int i = 1; i <= n; i++) {
            CustodyChain.Entry draft = new CustodyChain.Entry((long) (100 + i), i, "2026-09-29T10:0" + i + ":00.123456Z", "moderator",
                    types[(i - 1) % types.length], i == 2 ? 7L : null, i == 3 ? 11L : null, "event " + i, prev, null);
            String hash = CustodyChain.link(prev, CustodyChain.canonical(CASE, draft));
            out.add(new CustodyChain.Entry(draft.id(), i, draft.occurredAt(), draft.actor(), draft.type(), draft.analysisId(),
                    draft.sightingId(), draft.detail(), prev, hash));
            prev = hash;
        }
        return out;
    }
}
