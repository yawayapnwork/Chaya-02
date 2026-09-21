package dev.chaya.api.publiclink;

import dev.chaya.api.audit.AuditLogWriter;
import dev.chaya.api.audit.AuditLogWriter.ActorType;
import dev.chaya.api.audit.AuditLogWriter.AuditEvent;
import dev.chaya.api.audit.AuditLogWriter.Outcome;
import dev.chaya.api.audit.AuditService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.PublicViewerAuthenticator;
import dev.chaya.api.security.Role;
import dev.chaya.api.security.SecurityProperties;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.web.BadRequestException;
import dev.chaya.api.web.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Venue-scoped public viewer links.
 *
 * <p>Flow: a manager creates a link (long-lived secret, shown once, stored only as a SHA-256 hash).
 * A visitor exchanges the secret for a short-lived opaque access token, again stored only as a hash.
 * The token authenticates as a PUBLIC_VIEWER actor bound to exactly one venue and can only reach
 * endpoints that list PUBLIC_VIEWER. Every token use checks that the link is still unrevoked.
 */
@Service
public class PublicViewerService implements PublicViewerAuthenticator {

    public record CreatedLink(UUID id, String secret, Instant expiresAt) {}

    public record LinkInfo(UUID id, String label, Instant createdAt, Instant expiresAt, Instant revokedAt) {}

    public record ViewerToken(String token, Instant expiresAt, UUID venueId) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final AuditService audit;
    private final AuditLogWriter auditWriter;
    private final SecurityProperties props;

    public PublicViewerService(JdbcClient jdbc, TenantGuard guard, AuditService audit, AuditLogWriter auditWriter,
                               SecurityProperties props) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.auditWriter = auditWriter;
        this.props = props;
    }

    @Transactional
    public CreatedLink createLink(Actor actor, UUID venueId, String label, Duration ttl) {
        guard.requireVenue(actor, venueId);
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(props.publicLinkMaxTtl()) > 0) {
            throw new BadRequestException("ttl must be positive and at most " + props.publicLinkMaxTtl());
        }
        String secret = "chl_" + randomToken();
        Instant expires = Instant.now().plus(ttl).truncatedTo(java.time.temporal.ChronoUnit.MICROS); // Postgres precision
        UUID id = jdbc.sql("INSERT INTO public_viewer_link (organization_id, venue_id, label, secret_hash, created_by, expires_at) "
                + "VALUES (:o, :v, :l, :h, :by, :e) RETURNING id")
            .param("o", actor.organizationId()).param("v", venueId).param("l", label).param("h", hash(secret))
            .param("by", actor.subject()).param("e", Timestamp.from(expires)).query(UUID.class).single();
        // The secret itself is never written to the audit log.
        audit.success(actor, venueId, "public_link.create", "public_viewer_link", id,
            Map.of("expiresAt", expires.toString()));
        return new CreatedLink(id, secret, expires);
    }

    @Transactional(readOnly = true)
    public List<LinkInfo> listLinks(Actor actor, UUID venueId) {
        guard.requireVenue(actor, venueId);
        return jdbc.sql("SELECT id, label, created_at, expires_at, revoked_at FROM public_viewer_link "
                + "WHERE venue_id = :v AND organization_id = :o ORDER BY created_at DESC")
            .param("v", venueId).param("o", actor.organizationId())
            .query((rs, i) -> new LinkInfo(rs.getObject("id", UUID.class), rs.getString("label"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()))
            .list();
    }

    @Transactional
    public void revoke(Actor actor, UUID venueId, UUID linkId) {
        guard.requireVenue(actor, venueId);
        int rows = jdbc.sql("UPDATE public_viewer_link SET revoked_at = now(), revoked_by = :by "
                + "WHERE id = :l AND venue_id = :v AND organization_id = :o AND revoked_at IS NULL")
            .param("by", actor.subject()).param("l", linkId).param("v", venueId).param("o", actor.organizationId())
            .update();
        if (rows == 0) {
            throw new NotFoundException("link not found or already revoked");
        }
        audit.success(actor, venueId, "public_link.revoke", "public_viewer_link", linkId, Map.of());
    }

    /** Public entry point: trade a link secret for a short-lived token. Unknown/expired/revoked look identical. */
    @Transactional
    public ViewerToken exchange(String secret) {
        record LinkRow(UUID id, UUID orgId, UUID venueId, Instant expiresAt) {}
        LinkRow link = jdbc.sql("SELECT id, organization_id, venue_id, expires_at FROM public_viewer_link "
                + "WHERE secret_hash = :h AND revoked_at IS NULL AND expires_at > now()")
            .param("h", hash(secret))
            .query((rs, i) -> new LinkRow(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getObject("venue_id", UUID.class), rs.getTimestamp("expires_at").toInstant()))
            .optional().orElseThrow(() -> new NotFoundException("link not found or expired"));

        Instant expires = Instant.now().plus(props.viewerTokenTtl()).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (expires.isAfter(link.expiresAt())) {
            expires = link.expiresAt();
        }
        String token = "cvt_" + randomToken();
        jdbc.sql("INSERT INTO public_viewer_token (link_id, token_hash, expires_at) VALUES (:l, :h, :e)")
            .param("l", link.id()).param("h", hash(token)).param("e", Timestamp.from(expires)).update();
        auditWriter.record(new AuditEvent(link.orgId(), link.venueId(), "public-link:" + link.id(),
            ActorType.PUBLIC_VIEWER, "public_link.exchange", "public_viewer_link", link.id(), Outcome.SUCCESS, Map.of()));
        return new ViewerToken(token, expires, link.venueId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Actor> authenticate(String rawToken) {
        if (rawToken == null || !rawToken.startsWith("cvt_")) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT l.id, l.organization_id, l.venue_id FROM public_viewer_token t "
                + "JOIN public_viewer_link l ON l.id = t.link_id "
                + "WHERE t.token_hash = :h AND t.expires_at > now() AND l.revoked_at IS NULL AND l.expires_at > now()")
            .param("h", hash(rawToken))
            .query((rs, i) -> new Actor(Actor.Kind.PUBLIC_VIEWER, "public-link:" + rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class), Set.of(rs.getObject("venue_id", UUID.class)),
                Set.of(Role.PUBLIC_VIEWER)))
            .optional();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
