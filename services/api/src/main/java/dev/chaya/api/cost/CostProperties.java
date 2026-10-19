package dev.chaya.api.cost;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param rates the infrastructure rate card as JSON (env CHAYA_COST_RATES; docs/operations-dashboard.md, "Resources and
 *              cost"), or blank when none is configured. Then nothing is priced and every usage record says why. Parsed
 *              and validated at startup by {@link RateCard#parse}: an invalid card stops the API rather than pricing with it.
 */
@ConfigurationProperties("chaya.cost")
public record CostProperties(String rates) {}
