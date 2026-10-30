package com.peachsoft.otryx.flow.api.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 校验指标管理端口的 HTTP 安全访问边界。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/29 18:55
 */
class FlowSecurityConfigurationTest {

    @Test
    void allowsOnlyGetOnTheInternalManagementPort() {
        RequestMatcher matcher = FlowSecurityConfiguration.internalPrometheusOnly(9091, 8080);

        MockHttpServletRequest allowed = request("GET", "/actuator/prometheus", 9091);
        assertTrue(matcher.matches(allowed));
        assertFalse(matcher.matches(request("GET", "/actuator/prometheus", 8080)));
        assertFalse(matcher.matches(request("POST", "/actuator/prometheus", 9091)));
        assertFalse(matcher.matches(request("GET", "/actuator/env", 9091)));
        assertFalse(matcher.matches(request("GET", "/actuator/prometheus", 9092)));
        assertFalse(FlowSecurityConfiguration.internalPrometheusOnly(-1, 8080).matches(allowed));
        assertFalse(FlowSecurityConfiguration.internalPrometheusOnly(9091, 9091)
                .matches(request("GET", "/actuator/prometheus", 9091)));
    }

    private static MockHttpServletRequest request(String method, String path, int port) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setLocalPort(port);
        return request;
    }
}
