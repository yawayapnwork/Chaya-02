package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The published contract (packages/contracts/openapi/v1.yaml) is the OpenAPI document the running application
 * generates from its controllers. This test fails when:
 * <ul>
 *   <li>a controller mapping is missing from the generated document, or the document has one no controller serves;</li>
 *   <li>an operation of the committed contract no longer exists (removing a v1 endpoint is a breaking change);</li>
 *   <li>the committed contract differs from the generated document (regenerate it: see packages/contracts/README.md);</li>
 *   <li>the document's security disagrees with what the filter chain enforces, or a response disagrees with its schema.</li>
 * </ul>
 */
class OpenApiContractTest extends ApiTest {

    static final Path CONTRACT = Path.of("../../packages/contracts/openapi/v1.yaml");
    /** -Dchaya.openapi.write=true rewrites the committed contract from the generated document (additions/changes only). */
    static final boolean WRITE = Boolean.getBoolean("chaya.openapi.write");
    static final String HEADER = """
        # GENERATED from the chaya-api controllers by OpenApiContractTest. Do not edit by hand:
        # change the controllers, DTOs or annotations, then regenerate (packages/contracts/README.md).
        """;
    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");
    private static final ObjectMapper YAML = new YAMLMapper();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;

    private String generatedYaml;
    private JsonNode generated;

    @BeforeEach
    void generate() throws Exception {
        String admin = TestJwt.user(fx.organization(), "admin").token();
        generatedYaml = get("/api/v1/openapi.yaml", admin).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        generated = YAML.readTree(generatedYaml);
    }

    @Test
    void documentIsNotPublic() throws Exception {
        get("/api/v1/openapi", null).andExpect(status().isUnauthorized());
        get("/api/v1/openapi.yaml", null).andExpect(status().isUnauthorized());
    }

    @Test
    void everyControllerMappingIsInTheGeneratedDocumentAndNothingElse() throws Exception {
        Set<String> mapped = controllerMappings();
        Set<String> documented = operations(generated);
        assertThat(difference(mapped, documented)).as("controller mappings missing from the OpenAPI document").isEmpty();
        assertThat(difference(documented, mapped)).as("documented operations no controller serves").isEmpty();
        assertThat(documented).hasSameSizeAs(mapped);
    }

    @Test
    void noOperationOfTheCommittedContractDisappears() throws Exception {
        Set<String> gone = difference(operations(committed()), operations(generated));
        assertThat(gone).as("operations of the committed v1 contract the API no longer serves (a breaking change: "
            + "restore them, or add /api/v2)").isEmpty();
    }

    @Test
    void committedContractIsTheGeneratedDocument() throws Exception {
        if (WRITE) {
            assertThat(difference(operations(committed()), operations(generated))).as("refusing to drop v1 operations").isEmpty();
            Files.writeString(CONTRACT, HEADER + generatedYaml, StandardCharsets.UTF_8);
            return;
        }
        JsonNode committed = committed();
        if (!committed.equals(generated)) {
            List<String> drift = new ArrayList<>();
            diff("", committed, generated, drift);
            fail("packages/contracts/openapi/v1.yaml is not the document the API generates. Regenerate it with "
                + "`mvn verify -Dtest=OpenApiContractTest -Dchaya.openapi.write=true` (packages/contracts/README.md) "
                + "and review the diff. First differences:\n  " + String.join("\n  ", drift.subList(0, Math.min(drift.size(), 40))));
        }
    }

    /** Calls every documented operation without credentials: 401 exactly where the document says credentials are needed. */
    @Test
    void securityRequirementsMatchTheFilterChain() throws Exception {
        List<String> wrong = new ArrayList<>();
        forEachOperation(generated, (method, path, op) -> {
            boolean documentedPublic = op.has("security") && op.get("security").isEmpty();
            int status = mvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf(method), concrete(path)))
                .andReturn().getResponse().getStatus();
            if (documentedPublic == (status == 401)) {
                wrong.add(method + " " + path + ": documented " + (documentedPublic ? "public" : "secured")
                    + ", unauthenticated call answered " + status);
            }
            if (!documentedPublic) {
                for (String code : List.of("401", "403")) {
                    if (!op.path("responses").has(code)) {
                        wrong.add(method + " " + path + ": secured but no " + code + " response");
                    }
                }
            }
            if (!op.path("responses").has("503")) {
                wrong.add(method + " " + path + ": no 503 response");
            }
        });
        assertThat(wrong).isEmpty();
    }

    /** One success response per operation, no body on 204, and every error an RFC 9457 problem (health's 503 aside). */
    @Test
    void everyOperationDocumentsItsSuccessAndProblemResponses() throws Exception {
        List<String> wrong = new ArrayList<>();
        forEachOperation(generated, (method, path, op) -> {
            boolean success = false;
            Iterator<Map.Entry<String, JsonNode>> responses = op.path("responses").fields();
            while (responses.hasNext()) {
                Map.Entry<String, JsonNode> r = responses.next();
                String code = r.getKey();
                JsonNode content = r.getValue().path("content");
                success |= code.startsWith("2");
                if (code.equals("204") && !content.isMissingNode()) {
                    wrong.add(method + " " + path + ": 204 with a body");
                }
                boolean problem = content.has("application/problem+json") && content.size() == 1;
                boolean exempt = code.equals("401") || path.equals("/api/v1/health") && code.equals("503");
                if (code.matches("[45]\\d\\d") && !exempt && !problem) {
                    wrong.add(method + " " + path + ": " + code + " is not a problem response");
                }
            }
            if (!success) {
                wrong.add(method + " " + path + ": no success response");
            }
        });
        assertThat(wrong).isEmpty();
    }

    /** The viewer token is offered exactly where PUBLIC_VIEWER may call, and every operation declares its roles. */
    @Test
    void viewerTokenIsOfferedOnlyWherePublicViewersAreAllowed() throws Exception {
        List<String> wrong = new ArrayList<>();
        forEachOperation(generated, (method, path, op) -> {
            if (op.has("security") && op.get("security").isEmpty()) {
                return;
            }
            JsonNode roles = op.get("x-chaya-roles");
            if (roles == null || roles.isEmpty()) {
                wrong.add(method + " " + path + ": secured without x-chaya-roles");
                return;
            }
            boolean publicViewer = false;
            for (JsonNode r : roles) {
                publicViewer |= r.asText().equals("PUBLIC_VIEWER");
            }
            boolean viewerToken = op.get("security").toString().contains("viewerToken");
            if (publicViewer != viewerToken) {
                wrong.add(method + " " + path + ": roles " + roles + " but viewer token " + (viewerToken ? "offered" : "not offered"));
            }
        });
        assertThat(wrong).isEmpty();
    }

    /** Real responses carry exactly the properties their documented schemas name, and every required one. */
    @Test
    void responsesMatchTheirSchemas() throws Exception {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        fx.floor(org, venue, 0);
        String admin = TestJwt.user(org, "admin").venues(venue).token();
        Map<String, String> calls = new TreeMap<>(Map.of(
            "/api/v1/version", "/api/v1/version",
            "/api/v1/health", "/api/v1/health",
            "/api/v1/venues", "/api/v1/venues",
            "/api/v1/venues/{venueId}", "/api/v1/venues/" + venue,
            "/api/v1/venues/{venueId}/floors", "/api/v1/venues/" + venue + "/floors",
            "/api/v1/venues/{venueId}/ops/overview", "/api/v1/venues/" + venue + "/ops/overview",
            "/api/v1/venues/{venueId}/ops/access", "/api/v1/venues/" + venue + "/ops/access"));
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, String> c : calls.entrySet()) {
            var response = mvc.perform(MockMvcRequestBuilders.get(c.getValue()).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andReturn().getResponse();
            JsonNode schema = generated.path("paths").path(c.getKey()).path("get").path("responses")
                .path(Integer.toString(response.getStatus())).path("content").path("application/json").path("schema");
            if (schema.isMissingNode()) {
                wrong.add("GET " + c.getKey() + ": answered " + response.getStatus() + ", which has no documented JSON schema");
                continue;
            }
            conforms("GET " + c.getKey(), JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8)), schema, wrong);
        }
        assertThat(wrong).isEmpty();
    }

    // --- helpers ---

    private JsonNode committed() throws Exception {
        return YAML.readTree(Files.readString(CONTRACT, StandardCharsets.UTF_8));
    }

    private Set<String> controllerMappings() {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, org.springframework.web.method.HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            if (!e.getValue().getBeanType().getPackageName().startsWith("dev.chaya.api")) {
                continue; // springdoc's own endpoint, Boot's /error
            }
            Set<org.springframework.web.bind.annotation.RequestMethod> methods = e.getKey().getMethodsCondition().getMethods();
            assertThat(methods).as("mapping without an HTTP method: " + e.getKey()).isNotEmpty();
            for (String pattern : e.getKey().getPatternValues()) {
                methods.forEach(m -> out.add(m.name() + " " + pattern));
            }
        }
        return out;
    }

    interface OperationVisitor {
        void visit(String method, String path, JsonNode operation) throws Exception;
    }

    private static void forEachOperation(JsonNode spec, OperationVisitor v) throws Exception {
        Iterator<Map.Entry<String, JsonNode>> paths = spec.path("paths").fields();
        while (paths.hasNext()) {
            Map.Entry<String, JsonNode> p = paths.next();
            Iterator<Map.Entry<String, JsonNode>> ops = p.getValue().fields();
            while (ops.hasNext()) {
                Map.Entry<String, JsonNode> op = ops.next();
                if (HTTP_METHODS.contains(op.getKey())) {
                    v.visit(op.getKey().toUpperCase(), p.getKey(), op.getValue());
                }
            }
        }
    }

    private static Set<String> operations(JsonNode spec) throws Exception {
        Set<String> out = new TreeSet<>();
        forEachOperation(spec, (method, path, op) -> out.add(method + " " + path));
        return out;
    }

    private static Set<String> difference(Set<String> a, Set<String> b) {
        Set<String> d = new TreeSet<>(a);
        d.removeAll(b);
        return d;
    }

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}]+)}");

    /** A request path for a template: random ids, a part number, an artifact kind. */
    private static String concrete(String template) {
        Matcher m = PATH_VARIABLE.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = switch (m.group(1)) {
                case "partNumber" -> "1";
                case "kind" -> "SPLAT";
                default -> UUID.randomUUID().toString();
            };
            m.appendReplacement(sb, value);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private JsonNode resolve(JsonNode schema) {
        while (schema.has("$ref")) {
            String name = schema.get("$ref").asText().substring("#/components/schemas/".length());
            schema = generated.path("components").path("schemas").path(name);
        }
        return schema;
    }

    private void conforms(String where, JsonNode value, JsonNode schema, List<String> wrong) {
        schema = resolve(schema);
        if (value.isNull()) {
            return;
        }
        if (value.isArray()) {
            if (!"array".equals(schema.path("type").asText())) {
                wrong.add(where + ": array, documented as " + schema.path("type").asText());
                return;
            }
            for (JsonNode item : value) {
                conforms(where + "[]", item, schema.path("items"), wrong);
            }
            return;
        }
        if (value.isObject() && schema.has("properties")) {
            JsonNode props = schema.get("properties");
            value.fieldNames().forEachRemaining(f -> {
                if (!props.has(f)) {
                    wrong.add(where + "." + f + ": returned but not in the schema");
                } else {
                    conforms(where + "." + f, value.get(f), props.get(f), wrong);
                }
            });
            for (JsonNode required : schema.path("required")) {
                if (!value.has(required.asText())) {
                    wrong.add(where + "." + required.asText() + ": required by the schema but not returned");
                }
            }
        }
    }

    private static void diff(String at, JsonNode a, JsonNode b, List<String> out) {
        if (a.equals(b)) {
            return;
        }
        if (a.isObject() && b.isObject()) {
            Set<String> keys = new TreeSet<>();
            a.fieldNames().forEachRemaining(keys::add);
            b.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                if (!a.has(k)) {
                    out.add("+ " + at + "/" + k);
                } else if (!b.has(k)) {
                    out.add("- " + at + "/" + k);
                } else {
                    diff(at + "/" + k, a.get(k), b.get(k), out);
                }
            }
        } else {
            out.add("~ " + at);
        }
    }
}
