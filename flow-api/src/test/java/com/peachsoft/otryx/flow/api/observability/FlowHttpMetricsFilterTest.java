package com.peachsoft.otryx.flow.api.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 校验 API 响应分类的计数、敏感信息边界与故障隔离。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/29 17:48
 */
class FlowHttpMetricsFilterTest {

    @Test
    void countsSuccessAuthenticationFailureAuthorizationDenialAndServerError() throws Exception {
        try (SimpleMeterRegistry registry = new SimpleMeterRegistry()) {
            FlowHttpMetricsFilter filter = new FlowHttpMetricsFilter(registry);
            invoke(filter, 200);
            invoke(filter, 401);
            invoke(filter, 403);
            invoke(filter, 503);

            assertEquals(1.0, registry.counter("flow.http.responses",
                    "status_class", "2xx", "security_event", "none").count());
            assertEquals(1.0, registry.counter("flow.http.responses",
                    "status_class", "4xx", "security_event", "authentication_failure").count());
            assertEquals(1.0, registry.counter("flow.http.responses",
                    "status_class", "4xx", "security_event", "authorization_denied").count());
            assertEquals(1.0, registry.counter("flow.http.responses",
                    "status_class", "5xx", "security_event", "none").count());
        }
    }

    @Test
    void neverUsesRequestMetadataForMeterTags() throws Exception {
        try (SimpleMeterRegistry registry = new SimpleMeterRegistry()) {
            FlowHttpMetricsFilter filter = new FlowHttpMetricsFilter(registry);
            for (int i = 0; i < 100; i++) {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects/" + i);
                request.addHeader("Authorization", "Bearer secret-" + i);
                request.setParameter("token", "private-" + i);
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(401));
            }

            assertEquals(1, registry.getMeters().size());
            assertEquals(100.0, registry.counter("flow.http.responses",
                    "status_class", "4xx", "security_event", "authentication_failure").count());
            assertEquals(Set.of("status_class", "security_event"),
                    registry.getMeters().getFirst().getId().getTags().stream()
                            .map(tag -> tag.getKey())
                            .collect(java.util.stream.Collectors.toSet()));
            assertTrue(registry.getMeters().stream().noneMatch(
                    meter -> meter.getId().toString().contains("secret-")));
        }
    }

    @Test
    void propagatesOriginalServletErrorAndCountsFailure() {
        try (SimpleMeterRegistry registry = new SimpleMeterRegistry()) {
            FlowHttpMetricsFilter filter = new FlowHttpMetricsFilter(registry);
            assertThrows(ServletException.class, () -> filter.doFilter(
                    new MockHttpServletRequest(),
                    new MockHttpServletResponse(),
                    (req, res) -> { throw new ServletException("internal sensitive detail"); }));
            assertEquals(1.0, registry.counter("flow.http.responses",
                    "status_class", "5xx", "security_event", "none").count());
        }
    }

    private void invoke(FlowHttpMetricsFilter filter, int status) throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> ((MockHttpServletResponse) res).setStatus(status));
    }
}
