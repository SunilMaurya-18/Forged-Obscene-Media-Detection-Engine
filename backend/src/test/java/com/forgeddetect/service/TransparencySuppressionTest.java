package com.forgeddetect.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransparencySuppressionTest {

    @Test
    void smallNonZeroCountsAreWithheldAndListed() {
        Map<String, Object> decisions = new LinkedHashMap<>();
        decisions.put("AUTO_FLAG", 3L);
        decisions.put("ALLOW", 0L);
        decisions.put("REVIEW", 12L);
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("days", 2);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("period", period);
        report.put("decisions", decisions);
        report.put("rate", 0.5);
        report.put("byJurisdiction", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("uploads", 4)))));

        List<String> suppressed = new ArrayList<>();
        TransparencyService.suppress(report, "", 5, suppressed);

        assertThat(decisions).containsEntry("AUTO_FLAG", null).containsEntry("ALLOW", 0L).containsEntry("REVIEW", 12L);
        assertThat(period).containsEntry("days", 2);
        assertThat(report).containsEntry("rate", 0.5);
        assertThat(suppressed).containsExactly("decisions.AUTO_FLAG", "byJurisdiction[0].uploads");
    }
}
