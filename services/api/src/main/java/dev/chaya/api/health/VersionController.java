package dev.chaya.api.health;

import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class VersionController {

    public static final String API_VERSION = "v1";

    private final BuildProperties build;

    public VersionController(BuildProperties build) {
        this.build = build;
    }

    @GetMapping("/version")
    public VersionInfo version() {
        return new VersionInfo(build.getName(), build.getVersion(), API_VERSION);
    }

    public record VersionInfo(String name, String version, String apiVersion) {}
}
