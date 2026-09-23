package dev.chaya.api.health;

import org.springframework.http.CacheControl;
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

    /** 503 when DOWN; 200 for UP and DEGRADED, whose body names the impaired component. */
    @GetMapping("/health")
    public ResponseEntity<HealthService.HealthReport> health() {
        HealthService.HealthReport report = healthService.check();
        return ResponseEntity.status(HealthService.DOWN.equals(report.status()) ? 503 : 200)
            .cacheControl(CacheControl.noStore())
            .body(report);
    }
}
