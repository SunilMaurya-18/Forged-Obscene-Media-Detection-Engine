package com.forgeddetect.controller;

import com.forgeddetect.service.AnalysisService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class StatsController {

    private final AnalysisService analysisService;

    public StatsController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return analysisService.stats();
    }
}
