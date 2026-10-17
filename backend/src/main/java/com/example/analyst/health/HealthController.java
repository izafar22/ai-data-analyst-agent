package com.example.analyst.health;

import java.util.Map;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthController {
    private final HealthEndpoint health;

    HealthController(HealthEndpoint health) { this.health = health; }

    @GetMapping("/api/v1/health")
    ResponseEntity<Map<String, String>> health() {
        String status = health.health().getStatus().getCode();
        return ResponseEntity.status("UP".equals(status) ? 200 : 503).body(Map.of("status", status));
    }
}
