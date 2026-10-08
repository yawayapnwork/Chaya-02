package dev.chaya.api.web;

import dev.chaya.api.health.VersionController;
import dev.chaya.api.security.PublicViewerTokenFilter;
import dev.chaya.api.security.SecurityConfig;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;

/**
 * The OpenAPI document is generated from the controllers; nothing here lists endpoints. What the controllers cannot
 * say themselves is derived from the code that enforces it, so it cannot drift either:
 * <ul>
 *   <li>who may call an operation: {@link PreAuthorize} on the handler or its class (x-chaya-roles), and whether
 *   authentication is needed at all: {@link SecurityConfig#isPublic};</li>
 *   <li>which credentials work: a bearer JWT always; the public-viewer token only where PUBLIC_VIEWER is allowed;</li>
 *   <li>the error responses every operation can produce: the security filters (401, 403, 429), the
 *   exception handler and dependency filter (400, 404, 503), all RFC 9457 problems (schema {@code Problem}).</li>
 * </ul>
 * Errors specific to one operation are declared with {@link ProblemResponse} on its handler.
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER = "bearerAuth";
    static final String VIEWER_TOKEN = "viewerToken";
    static final String PROBLEM = "Problem";
    static final String PROBLEM_JSON = "application/problem+json";
    static final String ROLES_EXTENSION = "x-chaya-roles";

    private static final Pattern ROLE = Pattern.compile("'([A-Z_]+)'");

    @Bean
    OpenAPI chayaOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Chaya 02 API")
                .version(VersionController.API_VERSION)
                .description("""
                    Version 1 of the Chaya 02 HTTP API. Breaking changes require a new /api/v2 document.

                    Generated from the backend's controllers; packages/contracts/openapi/v1.yaml is the committed \
                    output and OpenApiContractTest fails when it drifts.

                    Roles (x-chaya-roles) under /api/v1/venues/{venueId}/ are the roles the caller holds at that venue. \
                    A resource outside the caller's organization or venue answers 404, never 403.

                    Errors are RFC 9457 problems with a stable machine-readable `code`."""))
            .servers(List.of(new Server().url("/").description("The API's own origin")))
            .components(new Components()
                .addSecuritySchemes(BEARER, new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                    .description("Keycloak access token for the chaya-api audience. Organization, roles and venue "
                        + "memberships come from its claims."))
                .addSecuritySchemes(VIEWER_TOKEN, new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name(PublicViewerTokenFilter.HEADER)
                    .description("Short-lived public-viewer token from POST /api/v1/public/viewer-token, bound to one "
                        + "venue; acts as PUBLIC_VIEWER. Never together with a bearer token (400)."))
                .addSchemas(PROBLEM, problemSchema()));
    }

    /** ApiExceptionHandler's body: Spring's ProblemDetail plus the `code` property; filters write the same shape. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema<?> problemSchema() {
        Schema s = new ObjectSchema()
            .description("RFC 9457 problem details")
            .addProperty("type", new StringSchema().format("uri").example("about:blank"))
            .addProperty("title", new StringSchema())
            .addProperty("status", new IntegerSchema().format("int32"))
            .addProperty("detail", new StringSchema())
            .addProperty("instance", new StringSchema().format("uri"))
            .addProperty("code", new StringSchema()
                .description("Stable machine-readable error code, e.g. NOT_FOUND, BAD_REQUEST, VERSION_INCOMPLETE. "
                    + "Absent on Spring's own request-parsing and validation problems."));
        s.setRequired(List.of("status"));
        return s;
    }

    /**
     * Schemas are named after the simple class name, so two DTOs called e.g. Waypoint would silently share one schema
     * and one of them would be documented wrong. Fail generation instead; give one of them @Schema(name = ...).
     */
    @Bean
    ModelConverter uniqueSchemaNames() {
        Map<String, Class<?>> seen = new ConcurrentHashMap<>();
        return (type, context, chain) -> {
            Class<?> raw = Json.mapper().constructType(type.getType()).getRawClass();
            if (raw.getName().startsWith("dev.chaya.api.")) {
                io.swagger.v3.oas.annotations.media.Schema named =
                    raw.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
                String name = named != null && !named.name().isEmpty() ? named.name() : raw.getSimpleName();
                Class<?> other = seen.putIfAbsent(name, raw);
                if (other != null && other != raw) {
                    throw new IllegalStateException("OpenAPI schema name " + name + " is used by both " + other.getName()
                        + " and " + raw.getName() + "; give one of them @Schema(name = ...)");
                }
            }
            return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        };
    }

    /**
     * Stable operation ids (springdoc's default appends _1, _2 in scan order), the handler's allowed roles and its
     * {@link ProblemResponse}s.
     */
    @Bean
    OperationCustomizer chayaOperations() {
        return (operation, handler) -> {
            String controller = handler.getBeanType().getSimpleName().replaceFirst("Controller$", "");
            operation.setOperationId(StringUtils.uncapitalize(controller) + StringUtils.capitalize(handler.getMethod().getName()));
            PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
            if (rule == null) {
                rule = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
            }
            if (rule != null) {
                Set<String> roles = new LinkedHashSet<>();
                Matcher m = ROLE.matcher(rule.value());
                while (m.find()) {
                    roles.add(m.group(1));
                }
                operation.addExtension(ROLES_EXTENSION, List.copyOf(roles));
            }
            for (ProblemResponse p : AnnotatedElementUtils.findMergedRepeatableAnnotations(handler.getMethod(), ProblemResponse.class)) {
                if (operation.getResponses() == null) {
                    operation.setResponses(new ApiResponses());
                }
                operation.getResponses().addApiResponse(Integer.toString(p.status()),
                    new ApiResponse().description(p.description()).content(problemContent()));
            }
            return operation;
        };
    }

    /** Security requirements and the error responses common to every operation, from where they are enforced. */
    @Bean
    OpenApiCustomizer chayaSecurityAndErrors() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, op) ->
                describe(path, HttpMethod.valueOf(method.name()), item, op)));
        };
    }

    @SuppressWarnings("unchecked")
    private static void describe(String path, HttpMethod method, PathItem item, Operation op) {
        boolean secured = !SecurityConfig.isPublic(method, path);
        List<String> roles = op.getExtensions() == null ? null : (List<String>) op.getExtensions().get(ROLES_EXTENSION);
        if (!secured) {
            op.setSecurity(List.of()); // explicitly none
        } else {
            List<SecurityRequirement> security = new ArrayList<>();
            security.add(new SecurityRequirement().addList(BEARER));
            if (roles == null || roles.contains("PUBLIC_VIEWER")) {
                security.add(new SecurityRequirement().addList(VIEWER_TOKEN));
            }
            op.setSecurity(security);
        }

        ApiResponses responses = op.getResponses() == null ? new ApiResponses() : op.getResponses();
        boolean hasInput = op.getRequestBody() != null
            || op.getParameters() != null && !op.getParameters().isEmpty()
            || item.getParameters() != null && !item.getParameters().isEmpty();
        if (hasInput) {
            addDefault(responses, "400", "Malformed request: an unparseable parameter or body, or a failed validation "
                + "(code BAD_REQUEST or an operation-specific code)");
        }
        if (secured) {
            responses.putIfAbsent("401", new ApiResponse().description(
                "Missing, invalid or expired credentials (empty body, WWW-Authenticate header)"));
            addDefault(responses, "403", "The caller's roles do not allow this operation (empty body), or an "
                + "operation-specific refusal (problem with a code)");
        }
        if (path.contains("{")) {
            addDefault(responses, "404", "Not found, or not visible to the caller's organization or venue (code NOT_FOUND)");
        }
        if (!path.startsWith("/api/v1/internal/")) {
            addDefault(responses, "429", "Rate limit exceeded (code RATE_LIMITED); see the Retry-After header");
        }
        addDefault(responses, "503", "A dependency is unavailable (code DATABASE_UNAVAILABLE, STORAGE_UNAVAILABLE or "
            + "AUTHENTICATION_UNAVAILABLE); the request was not completed");
        // springdoc gives an annotated 204 the method's return type; 204 has no body.
        if (responses.get("204") != null) {
            responses.get("204").setContent(null);
        }
        op.setResponses(responses);
    }

    private static void addDefault(ApiResponses responses, String status, String description) {
        responses.putIfAbsent(status, new ApiResponse().description(description).content(problemContent()));
    }

    private static Content problemContent() {
        return new Content().addMediaType(PROBLEM_JSON,
            new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM)));
    }
}
