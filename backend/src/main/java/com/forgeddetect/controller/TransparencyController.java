package com.forgeddetect.controller;

import com.forgeddetect.service.TransparencyService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Phase 23: public, aggregate-only transparency report. */
@RestController
@RequestMapping("/api/transparency")
public class TransparencyController {

    private final TransparencyService transparency;

    public TransparencyController(TransparencyService transparency) {
        this.transparency = transparency;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> report(@RequestParam(required = false) String period) {
        TransparencyService.Period p;
        try {
            p = transparency.parse(period);
        } catch (IllegalArgumentException | java.time.DateTimeException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_period", "message", ex.getMessage()));
        }
        return ResponseEntity.ok(transparency.report(p));
    }
}
