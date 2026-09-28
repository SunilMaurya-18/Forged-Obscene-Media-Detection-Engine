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
        added += seed(JurisdictionRule.GENERIC, ComplaintType.APPEAL, properties.getGenericAppealHours(),
                "Platform policy (generic)",
                "Configurable deadline for deciding an uploader's appeal against a takedown (internal complaint handling)");
        added += seedPhase23Jurisdictions();
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

    /**
     * Phase 23: EU, UK and Australia. None of these laws sets a fixed hour limit for every notice, so
     * the hours are platform targets and every label says "pending legal review" until counsel signs off.
     */
    private int seedPhase23Jurisdictions() {
        int added = 0;
        added += seed("EU", ComplaintType.USER_COMPLAINT, 24, "EU DSA (pending legal review)",
                "Regulation (EU) 2022/2065 (Digital Services Act) Art. 16: act on notices in a timely, diligent, "
                        + "non-arbitrary manner. No fixed statutory hours; 24h is a platform target.");
        added += seed("EU", ComplaintType.GOVERNMENT_ORDER, 24, "EU DSA (pending legal review)",
                "Digital Services Act Art. 9: order from a national judicial or administrative authority to act "
                        + "against illegal content; report the effect given without undue delay. 24h platform target.");
        added += seed("GB", ComplaintType.USER_COMPLAINT, 24, "UK Online Safety Act (pending legal review)",
                "Online Safety Act 2023: swiftly take down illegal content, including intimate image abuse, once "
                        + "alerted to it. No fixed statutory hours; 24h is a platform target.");
        added += seed("GB", ComplaintType.GOVERNMENT_ORDER, 24, "UK Online Safety Act (pending legal review)",
                "Online Safety Act 2023: requirements from Ofcom as the online safety regulator. No fixed "
                        + "statutory hours for takedown; 24h is a platform target.");
        added += seed("AU", ComplaintType.USER_COMPLAINT, 24, "Australia eSafety (pending legal review)",
                "Online Safety Act 2021 (Cth) image-based abuse scheme: reports can be escalated to the eSafety "
                        + "Commissioner. 24h platform target.");
        added += seed("AU", ComplaintType.GOVERNMENT_ORDER, 24, "Australia eSafety (pending legal review)",
                "Online Safety Act 2021 (Cth): removal notice from the eSafety Commissioner for intimate images, "
                        + "to be complied with within 24 hours.");
        added += seedAppealRules();
        return added;
    }

    /**
     * Appeal (internal complaint) windows per jurisdiction. Most of these laws require complaints to be
     * handled promptly or "without undue delay" rather than in fixed hours, so the hours are platform
     * targets and every label says "pending legal review".
     */
    private int seedAppealRules() {
        int added = 0;
        added += seed("EU", ComplaintType.APPEAL, 72, "EU DSA Art. 20 (pending legal review)",
                "Digital Services Act Art. 20: internal complaint-handling decided in a timely, non-discriminatory, "
                        + "diligent and non-arbitrary manner, not solely by automated means. 72h platform target.");
        added += seed("GB", ComplaintType.APPEAL, 72, "UK Online Safety Act (pending legal review)",
                "Online Safety Act 2023: complaints procedures, including appeals by users whose content was taken "
                        + "down, must provide for appropriate action. 72h platform target.");
        added += seed("AU", ComplaintType.APPEAL, 72, "Australia eSafety (pending legal review)",
                "Online Safety Act 2021 (Cth) and Basic Online Safety Expectations: complaints mechanism for removal "
                        + "decisions. 72h platform target.");
        added += seed("IN", ComplaintType.APPEAL, 72, "India IT Rules 2026 (pending legal review)",
                "IT Rules 2021 as amended, Rule 3(2): grievance officer acknowledges within 24h and disposes of "
                        + "complaints within 15 days; 72h platform target for takedown appeals.");
        added += seed("US", ComplaintType.APPEAL, 72, "US platform policy (pending legal review)",
                "No federal statutory deadline for uploader appeals; the TAKE IT DOWN Act covers removal only. "
                        + "72h platform target.");
        return added;
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
