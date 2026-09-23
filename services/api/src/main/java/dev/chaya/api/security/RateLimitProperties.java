package dev.chaya.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-minute request budgets (fixed one-minute windows). Keys: the actor's subject when authenticated, otherwise the
 * client address as seen by the servlet container (behind a reverse proxy, configure server.forward-headers-strategy
 * so this is the real client and not the proxy). Service accounts (workers) are not limited.
 *
 * @param publicLinkExchangePerMinute anonymous POST /public/viewer-token per client address
 * @param anonymousPerMinute          any other anonymous request per client address
 * @param authenticatedPerMinute      any request per authenticated user or public-viewer session
 * @param searchPerMinute             semantic search per authenticated caller (each one runs a model inference)
 */
@ConfigurationProperties("chaya.rate-limit")
public record RateLimitProperties(Boolean enabled, Integer publicLinkExchangePerMinute, Integer anonymousPerMinute,
                                  Integer authenticatedPerMinute, Integer searchPerMinute) {

    public RateLimitProperties {
        enabled = enabled == null || enabled;
        publicLinkExchangePerMinute = publicLinkExchangePerMinute == null ? 10 : publicLinkExchangePerMinute;
        anonymousPerMinute = anonymousPerMinute == null ? 120 : anonymousPerMinute;
        authenticatedPerMinute = authenticatedPerMinute == null ? 1200 : authenticatedPerMinute;
        searchPerMinute = searchPerMinute == null ? 60 : searchPerMinute;
    }
}
