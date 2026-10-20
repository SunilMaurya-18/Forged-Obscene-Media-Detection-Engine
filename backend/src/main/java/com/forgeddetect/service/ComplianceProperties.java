package com.forgeddetect.service;

import com.forgeddetect.model.ComplaintType;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Takedown SLA settings, bound from {@code compliance.*}. Since Phase 19 the per-jurisdiction
 * deadlines live in the {@code jurisdiction_rules} table; {@code sla.user-complaint} /
 * {@code sla.government-order} seed the IN rules and are the last-resort fallback if no rule matches.
 */
@ConfigurationProperties(prefix = "compliance")
public class ComplianceProperties {

    private final Sla sla = new Sla();
    private final Escalation escalation = new Escalation();

    /** Jurisdiction used when a complaint doesn't name one. Platform configuration, never inferred from IP. */
    private String defaultJurisdiction = "IN";

    /** Hours for the seeded GENERIC rules, the fallback for jurisdictions without their own rule. */
    private int genericUserComplaintHours = 24;

    private int genericGovernmentOrderHours = 24;

    /** Hours for the seeded GENERIC appeal rule (Phase 23). */
    private int genericAppealHours = 72;

    public int getGenericAppealHours() {
        return genericAppealHours;
    }

    public void setGenericAppealHours(int genericAppealHours) {
        this.genericAppealHours = genericAppealHours;
    }

    public String getDefaultJurisdiction() {
        return defaultJurisdiction;
    }

    public void setDefaultJurisdiction(String defaultJurisdiction) {
        this.defaultJurisdiction = defaultJurisdiction;
    }

    public int getGenericUserComplaintHours() {
        return genericUserComplaintHours;
    }

    public void setGenericUserComplaintHours(int genericUserComplaintHours) {
        this.genericUserComplaintHours = genericUserComplaintHours;
    }

    public int getGenericGovernmentOrderHours() {
        return genericGovernmentOrderHours;
    }

    public void setGenericGovernmentOrderHours(int genericGovernmentOrderHours) {
        this.genericGovernmentOrderHours = genericGovernmentOrderHours;
    }

    public Sla getSla() {
        return sla;
    }

    public Escalation getEscalation() {
        return escalation;
    }

    public Duration windowFor(ComplaintType type) {
        return switch (type) {
            case USER_COMPLAINT -> sla.userComplaint;
            case GOVERNMENT_ORDER -> sla.governmentOrder;
            case APPEAL -> sla.appeal;
        };
    }

    public static class Sla {

        private Duration userComplaint = Duration.ofHours(2);
        private Duration governmentOrder = Duration.ofHours(3);

        /** Last-resort appeal window if no (jurisdiction, APPEAL) or GENERIC rule exists. */
        private Duration appeal = Duration.ofHours(72);

        public Duration getAppeal() {
            return appeal;
        }

        public void setAppeal(Duration appeal) {
            this.appeal = appeal;
        }

        /** Remaining time at or below which an open item becomes AT_RISK. */
        private Duration atRiskThreshold = Duration.ofMinutes(30);

        public Duration getUserComplaint() {
            return userComplaint;
        }

        public void setUserComplaint(Duration userComplaint) {
            this.userComplaint = userComplaint;
        }

        public Duration getGovernmentOrder() {
            return governmentOrder;
        }

        public void setGovernmentOrder(Duration governmentOrder) {
            this.governmentOrder = governmentOrder;
        }

        public Duration getAtRiskThreshold() {
            return atRiskThreshold;
        }

        public void setAtRiskThreshold(Duration atRiskThreshold) {
            this.atRiskThreshold = atRiskThreshold;
        }
    }

    public static class Escalation {

        /** Optional; when blank, escalations are only logged. */
        private String webhookUrl = "";

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }
    }
}
