package com.peachsoft.otryx.flow.api.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 只按固定响应类别记录 HTTP 请求及认证授权拒绝指标。
 *
 * <p>在 Spring Security 过滤器链内部、授权检查之前安装，以覆盖拒绝请求。
 * 不记录路径、用户名、项目标识、请求正文、认证头或异常信息。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/29 17:48
 */
public final class FlowHttpMetricsFilter extends OncePerRequestFilter {

    private static final String RESPONSE_METRIC = "flow.http.responses";
    private static final String STATUS_CLASS_TAG = "status_class";
    private static final String SECURITY_EVENT_TAG = "security_event";
    private static final String NONE = "none";
    private static final String AUTHENTICATION_FAILURE = "authentication_failure";
    private static final String AUTHORIZATION_DENIED = "authorization_denied";

    private final MeterRegistry registry;

    /**
     * 使用应用的指标注册器创建过滤器，不创建网络连接或后台线程。
     *
     * @param registry 应用的 MeterRegistry
     */
    public FlowHttpMetricsFilter(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * 在请求完成后记录最终响应类别；异常传播语义保持不变。
     *
     * <p>指标记录失败不得改变业务响应或掩盖原始 Servlet 异常。</p>
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param filterChain 下一个过滤器
     * @throws ServletException 后续过滤器的 Servlet 异常
     * @throws IOException 后续过滤器的 I/O 异常
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            int status = response.getStatus();
            String statusClass = status >= 100 && status <= 599 ? (status / 100) + "xx" : "unknown";
            String securityEvent = switch (status) {
                case HttpServletResponse.SC_UNAUTHORIZED -> AUTHENTICATION_FAILURE;
                case HttpServletResponse.SC_FORBIDDEN -> AUTHORIZATION_DENIED;
                default -> NONE;
            };
            try {
                Counter.builder(RESPONSE_METRIC)
                        .tag(STATUS_CLASS_TAG, statusClass)
                        .tag(SECURITY_EVENT_TAG, securityEvent)
                        .register(registry)
                        .increment();
            } catch (RuntimeException ignored) {
                // 遥测出口故障不改变 HTTP 响应、认证决策或原始异常。
            }
        }
    }
}
