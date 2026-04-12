package de.bsnsoft.megarepo.rest.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/status")
public class StatusController {

    private final String version;

    public StatusController(
            @Value("${megarepo.version:unknown}") String version) {
        this.version = version;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> check() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "version", version,
                "edition", "MegaRepo"));
    }
}
