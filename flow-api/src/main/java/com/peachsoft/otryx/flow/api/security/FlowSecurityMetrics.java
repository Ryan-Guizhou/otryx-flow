package com.peachsoft.otryx.flow.api.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 发布低基数认证与授权事件计数，不将用户或请求信息写入 Meter 标签。
 *
 * @Author Ryan
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/28 11:45
 */
@Component
public final class FlowSecurityMetrics {

    private static final String LOGIN_FAILURES = "flow.security.login.failures";
    private static final String AUTHENTICATION_FAILURES = "flow.security.authentication.failures";
    private static final String AUTHORIZATION_DENIALS = "flow.security.authorization.denials";
    private static final String CSRF_DENIALS = "flow.security.csrf.denials";

    private final Counter loginFailures;
    private final Counter authenticationFailures;
    private final Counter authorizationDenials;
    private final Counter csrfDenials;

    /**
     * 注册固定名称、无动态标签的安全事件计数器。
     *
     * @param registry 当前 API JVM 的 Meter 注册器
     */
    public FlowSecurityMetrics(MeterRegistry registry) {
        MeterRegistry meterRegistry = Objects.requireNonNull(registry, "registry");
        loginFailures = Counter.builder(LOGIN_FAILURES)
                .description("Number of login attempts rejected due to invalid credentials")
                .register(meterRegistry);
        authenticationFailures = Counter.builder(AUTHENTICATION_FAILURES)
                .description("Number of HTTP requests rejected because authentication is missing or invalid")
                .register(meterRegistry);
        authorizationDenials = Counter.builder(AUTHORIZATION_DENIALS)
                .description("Number of authenticated requests rejected by authorization rules")
                .register(meterRegistry);
        csrfDenials = Counter.builder(CSRF_DENIALS)
                .description("Number of state-changing HTTP requests rejected by CSRF protection")
                .register(meterRegistry);
    }

    /** 记录一次凭据校验失败；不记录用户名、密码或异常消息。 */
    public void recordLoginFailure() {
        loginFailures.increment();
    }

    /** 记录一次未认证请求拒绝。 */
    public void recordAuthenticationFailure() {
        authenticationFailures.increment();
    }

    /** 记录一次已认证请求的授权拒绝。 */
    public void recordAuthorizationDenial() {
        authorizationDenials.increment();
    }

    /** 记录一次 CSRF 校验拒绝。 */
    public void recordCsrfDenial() {
        csrfDenials.increment();
    }
}
