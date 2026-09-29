package com.forgeddetect.investigation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 28: what an investigator can do. Every case read is checked against an approved, unexpired
 * legal request and recorded in that case's custody chain before anything is returned.
 */
@Service
public class InvestigatorService {

    private final InvestigationService investigations;
    private final InvestigationRepository cases;
    private final LegalRequestService legalRequests;
    private final CustodyLog custody;
    private final Clock clock;

    public InvestigatorService(InvestigationService investigations, InvestigationRepository cases, LegalRequestService legalRequests,
            CustodyLog custody, Clock clock) {
        this.investigations = investigations;
        this.cases = cases;
        this.legalRequests = legalRequests;
        this.custody = custody;
        this.clock = clock;
    }

    /** The cases the investigator's active requests name: number, title and the request, nothing from inside the case. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> cases(String investigator) {
        Instant now = clock.instant();
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (LegalRequest r : legalRequests.active(investigator)) {
            for (Investigation inv : cases.findByIdIn(r.getCaseIds())) {
                out.computeIfAbsent(inv.getCaseNumber(), k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("caseNumber", inv.getCaseNumber());
                    m.put("title", inv.getTitle());
                    m.put("status", inv.getStatus().name());
                    m.put("requests", new ArrayList<Map<String, Object>>());
                    return m;
                });
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> reqs = (List<Map<String, Object>>) out.get(inv.getCaseNumber()).get("requests");
                reqs.add(LegalRequestService.summary(r, now));
            }
        }
        return out.values().stream().sorted(Comparator.comparing(m -> (String) m.get("caseNumber"))).toList();
    }

    @Transactional
    public Map<String, Object> view(String caseNumber, String investigator) {
        LegalRequestService.Authorised auth = legalRequests.authorise(investigator, caseNumber);
        Investigation inv = custody.lock(auth.investigation());
        custody.append(inv, investigator, InvestigationEventType.INVESTIGATOR_VIEWED, null, null,
                "Case read by investigator " + investigator + " under legal request " + auth.request().getRequestNumber() + ".");
        Map<String, Object> body = investigations.investigatorView(inv.getCaseNumber(), investigator, legalRequests.visibleCaseIds(investigator));
        body.put("authorisation", LegalRequestService.summary(auth.request(), clock.instant()));
        body.put("custodyVerification", CustodyLog.verificationView(custody.verify(inv)));
        return body;
    }
}
