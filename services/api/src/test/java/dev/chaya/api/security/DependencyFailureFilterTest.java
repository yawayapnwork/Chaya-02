package dev.chaya.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Database unavailable (including from inside a security filter) is an explicit 503; other errors are untouched. */
class DependencyFailureFilterTest {

    private MockHttpServletResponse run(RuntimeException toThrow) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        new DependencyFailureFilter().doFilter(new MockHttpServletRequest("GET", "/api/v1/venues/x"), res, (rq, rs) -> {
            throw toThrow;
        });
        return res;
    }

    @Test
    void databaseUnavailableIs503WithACode() throws Exception {
        MockHttpServletResponse res = run(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"));
        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getContentType()).startsWith("application/problem+json");
        assertThat(res.getContentAsString()).contains("\"code\":\"DATABASE_UNAVAILABLE\"").doesNotContain("JDBC");
    }

    @Test
    void theCauseIsFoundWhenWrappedByTheDispatcher() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        new DependencyFailureFilter().doFilter(new MockHttpServletRequest(), res, (rq, rs) -> {
            throw new ServletException("Request processing failed", new CannotGetJdbcConnectionException("x"));
        });
        assertThat(res.getStatus()).isEqualTo(503);
    }

    @Test
    void unrelatedErrorsAreNotDisguised() {
        assertThatThrownBy(() -> run(new IllegalStateException("bug"))).isInstanceOf(IllegalStateException.class);
        assertThat(DependencyFailureFilter.classify(new IllegalArgumentException())).isNull();
    }
}
