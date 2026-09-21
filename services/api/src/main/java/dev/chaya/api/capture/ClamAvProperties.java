package dev.chaya.api.capture;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("chaya.clamav")
public record ClamAvProperties(boolean enabled, String host, int port) {}
