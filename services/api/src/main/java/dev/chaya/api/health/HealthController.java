package dev.chaya.api.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
    @Operation(summary = "Backend health, including real dependency checks")
    @ApiResponse(responseCode = "200", description = "UP, or DEGRADED with the impaired component named")
    @ApiResponse(responseCode = "503", description = "DOWN: a required dependency is unavailable",
        content = @Content(schema = @Schema(implementation = HealthService.HealthReport.class)))
    @GetMapping("/health")
    public ResponseEntity<HealthService.HealthReport> health() {
        HealthService.HealthReport report = healthService.check();
        return ResponseEntity.status(HealthService.DOWN.equals(report.status()) ? 503 : 200)
            .cacheControl(CacheControl.noStore())
            .body(report);
    }
}
