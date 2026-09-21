package dev.chaya.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import java.util.UUID;

/** Base for tests that call the HTTP API with real (test-signed) tokens. */
abstract class ApiTest extends AbstractIntegrationTest {

    @Autowired protected MockMvc mvc;

    protected ResultActions call(HttpMethod method, String url, String bearer, String jsonBody) throws Exception {
        MockHttpServletRequestBuilder req = MockMvcRequestBuilders.request(method, url);
        if (bearer != null) {
            req.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        if (jsonBody != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(jsonBody);
        }
        return mvc.perform(req);
    }

    protected ResultActions get(String url, String bearer) throws Exception {
        return call(HttpMethod.GET, url, bearer, null);
    }

    protected ResultActions post(String url, String bearer, String json) throws Exception {
        return call(HttpMethod.POST, url, bearer, json);
    }

    protected ResultActions withViewerToken(HttpMethod method, String url, String viewerToken, String json) throws Exception {
        MockHttpServletRequestBuilder req = MockMvcRequestBuilders.request(method, url)
            .header("X-Chaya-Viewer-Token", viewerToken);
        if (json != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(req);
    }

    protected static final String POI_JSON = "{\"label\":\"Exit\",\"x\":1.0,\"y\":2.0,\"z\":0.0,\"tags\":[\"safety\"]}";

    protected int auditCount(UUID org, String action) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE organization_id = :o AND action = :a")
            .param("o", org).param("a", action).query(Integer.class).single();
    }

    protected int auditCount(UUID org, String action, String outcome) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE organization_id = :o AND action = :a AND outcome = :out")
            .param("o", org).param("a", action).param("out", outcome).query(Integer.class).single();
    }
}
