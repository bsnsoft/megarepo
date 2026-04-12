package de.bsnsoft.megarepo.rest.controller;

import de.bsnsoft.megarepo.repository.MetricsService;
import de.bsnsoft.megarepo.repository.SystemMetrics;
import de.bsnsoft.megarepo.repository.ThroughputMetrics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    private static final int DEFAULT_WINDOW_MINUTES = 5;

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping
    public ResponseEntity<SystemMetrics> systemMetrics() {
        return ResponseEntity.ok(metricsService.getSystemMetrics());
    }

    @GetMapping("/throughput")
    public ResponseEntity<ThroughputMetrics> throughput(
            @RequestParam(defaultValue = "" + DEFAULT_WINDOW_MINUTES) int window) {
        if (window < 1) {
            window = DEFAULT_WINDOW_MINUTES;
        }
        if (window > 1440) {
            window = 1440; // cap at 24 hours
        }
        return ResponseEntity.ok(metricsService.getThroughputMetrics(Duration.ofMinutes(window)));
    }
}
