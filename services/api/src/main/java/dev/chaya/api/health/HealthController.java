package dev.chaya.api.health;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthService.HealthReport> health() {
        HealthService.HealthReport report = healthService.check();
        return "UP".equals(report.status())
            ? ResponseEntity.ok(report)
            : ResponseEntity.status(503).body(report);
    }
}
