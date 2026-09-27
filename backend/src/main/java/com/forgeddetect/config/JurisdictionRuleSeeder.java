package com.forgeddetect.config;

import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.JurisdictionRule;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.JurisdictionRuleRepository;
import com.forgeddetect.service.ComplianceProperties;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Phase 19: inserts the built-in rules that are missing. Existing rows are never overwritten, so
 * edits made directly in the database survive restarts.
 */
@Component
@Order(0)
public class JurisdictionRuleSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JurisdictionRuleSeeder.class);

    private static final String LEGACY_LABEL = "India IT Rules 2026";

    private final JurisdictionRuleRepository rules;
    private final AnalysisResultRepository results;
    private final ComplianceProperties properties;

    public JurisdictionRuleSeeder(JurisdictionRuleRepository rules, AnalysisResultRepository results,
            ComplianceProperties properties) {
        this.rules = rules;
        this.results = results;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        int added = 0;
        added += seed("IN", ComplaintType.USER_COMPLAINT, hours(properties.windowFor(ComplaintType.USER_COMPLAINT)),
                "India IT Rules 2026",
                "IT (Intermediary Guidelines and Digital Media Ethics Code) Rules, as amended 2026: "
                        + "synthetic / non-consensual intimate imagery complaint from a user");
        added += seed("IN", ComplaintType.GOVERNMENT_ORDER, hours(properties.windowFor(ComplaintType.GOVERNMENT_ORDER)),
                "India IT Rules 2026",
                "IT (Intermediary Guidelines and Digital Media Ethics Code) Rules, as amended 2026: "
                        + "takedown order from an authorised government agency");
        added += seed("US", ComplaintType.USER_COMPLAINT, 48,
                "US Take It Down Act",
                "TAKE IT DOWN Act (2025): remove non-consensual intimate imagery, including AI-generated "
                        + "depictions, within 48 hours of a valid removal request");
        added += seed(JurisdictionRule.GENERIC, ComplaintType.USER_COMPLAINT, properties.getGenericUserComplaintHours(),
                "Platform policy (generic)",
                "Configurable fallback for jurisdictions without a specific statutory deadline");
        added += seed(JurisdictionRule.GENERIC, ComplaintType.GOVERNMENT_ORDER, properties.getGenericGovernmentOrderHours(),
                "Platform policy (generic)",
                "Configurable fallback for jurisdictions without a specific statutory deadline");
        if (added > 0) {
            log.info("Seeded {} jurisdiction rule(s)", added);
        }
        backfillLegacyComplaints();
    }

    /**
     * Complaints filed before jurisdiction rules existed were all timed on the IT Rules 2026 windows,
     * so they are labelled as IN. Only the label and jurisdiction are filled in; the clock is unchanged.
     */
    private void backfillLegacyComplaints() {
        List<AnalysisResult> legacy = results.findByComplaintTypeIsNotNullAndSlaRuleLabelIsNull();
        for (AnalysisResult item : legacy) {
            if (item.getJurisdiction() == null) {
                item.setJurisdiction("IN");
            }
            item.setSlaRuleLabel(rules.findByJurisdictionAndComplaintType(item.getJurisdiction(), item.getComplaintType())
                    .map(JurisdictionRule::getLabel)
                    .orElse(LEGACY_LABEL));
        }
        if (!legacy.isEmpty()) {
            results.saveAll(legacy);
            log.info("Labelled {} complaint(s) filed before jurisdiction rules existed", legacy.size());
        }
    }

    private int seed(String jurisdiction, ComplaintType type, int hours, String label, String reference) {
        if (rules.existsByJurisdictionAndComplaintType(jurisdiction, type)) {
            return 0;
        }
        rules.save(new JurisdictionRule(jurisdiction, type, hours, label, reference));
        return 1;
    }

    /** Rules are whole hours; a sub-hour configured window rounds up rather than shortening the deadline. */
    private static int hours(Duration window) {
        long minutes = window.toMinutes();
        return (int) Math.max(1, (minutes + 59) / 60);
    }
}
