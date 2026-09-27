package com.forgeddetect.service;

import com.forgeddetect.model.ComplaintType;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Takedown SLA windows (IT Rules 2026) and escalation settings, bound from {@code compliance.*}.
 */
@ConfigurationProperties(prefix = "compliance")
public class ComplianceProperties {

    private final Sla sla = new Sla();
    private final Escalation escalation = new Escalation();

    /** Dev/demo only: seed sample complaints at every SLA stage when none exist. */
    private boolean seedDemo = false;

    public Sla getSla() {
        return sla;
    }

    public Escalation getEscalation() {
        return escalation;
    }

    public boolean isSeedDemo() {
        return seedDemo;
    }

    public void setSeedDemo(boolean seedDemo) {
        this.seedDemo = seedDemo;
    }

    public Duration windowFor(ComplaintType type) {
        return switch (type) {
            case USER_COMPLAINT -> sla.userComplaint;
            case GOVERNMENT_ORDER -> sla.governmentOrder;
        };
    }

    public static class Sla {

        private Duration userComplaint = Duration.ofHours(2);
        private Duration governmentOrder = Duration.ofHours(3);

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
