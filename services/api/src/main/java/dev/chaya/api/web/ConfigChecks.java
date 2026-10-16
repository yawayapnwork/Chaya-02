package dev.chaya.api.web;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Startup checks for configuration values that are URLs (DEPLOYMENT.md, "Environment variables"). A malformed value is
 * refused when the application starts, naming the variable, rather than failing later on the first request with an
 * error that does not mention it. Values are never echoed: some of these variables sit next to secrets in an env file,
 * and an operator who pasted the wrong one should not find it in the logs.
 */
public final class ConfigChecks {

    private ConfigChecks() {}

    /** An absolute http(s) URL with a host, e.g. S3_ENDPOINT=http://minio:9000 or KEYCLOAK_ISSUER_URI. */
    public static URI httpUrl(String variable, String value) {
        URI uri = parse(variable, value);
        if (uri.getScheme() == null || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https")) || uri.getHost() == null) {
            throw new IllegalStateException(variable + " must be an absolute http:// or https:// URL with a host name "
                + "(scheme://host[:port]); the configured value is not");
        }
        return uri;
    }

    /**
     * A browser origin, scheme://host[:port], as CHAYA_ALLOWED_ORIGINS lists them. A trailing slash is dropped; a path,
     * query or fragment is refused, because the browser's Origin header never carries one and such an entry would match
     * no request (every call from the web app would then fail CORS with nothing pointing at this setting).
     */
    public static String origin(String variable, String value) {
        URI uri = httpUrl(variable, value);
        String path = uri.getRawPath();
        if ((path != null && !path.isEmpty() && !path.equals("/")) || uri.getRawQuery() != null || uri.getRawFragment() != null
            || uri.getRawUserInfo() != null) {
            throw new IllegalStateException(variable + " must list origins only (scheme://host[:port], no path); one entry has more");
        }
        return uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT) + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    private static URI parse(String variable, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + " must be set");
        }
        try {
            return new URI(value.strip());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(variable + " is not a valid URL");
        }
    }
}
