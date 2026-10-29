package com.peachsoft.otryx.flow.api.security;

import com.peachsoft.otryx.flow.api.observability.FlowHttpMetricsFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * 配置同源会话认证、CSRF 防护及 HTTP 安全事件指标。
 *
 * <p>只允许明确开放的认证端点匿名访问；安全事件指标在认证链内按固定结果类别计数。</p>
 *
 * @Author Ryan
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/26 14:25
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class FlowSecurityConfiguration {
    /** 配置业务接口认证、匿名请求 401 响应和 CSRF 验证。 */
    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            SecurityContextRepository contexts) throws Exception {
        return http
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(
                                HttpMethod.GET, "/api/v1/auth/csrf", "/actuator/health", "/actuator/info")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .anyRequest().authenticated())
                .csrf(c -> c.csrfTokenRepository(new CookieCsrfTokenRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(s -> s.securityContextRepository(contexts))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .build();
    }

    /**
     * 在整个 Servlet 过滤链外层统计最终响应状态，包括 Spring Security 提前拒绝的请求。
     *
     * <p>只覆盖 /api/*，不触及 Actuator 或公开管理端口；不访问请求参数或认证凭据。</p>
     *
     * @param meterRegistry 当前应用的指标注册器
     * @return 外层有界 HTTP 响应指标过滤器注册信息
     */
    @Bean
    public FilterRegistrationBean<FlowHttpMetricsFilter> flowHttpMetricsFilter(MeterRegistry meterRegistry) {
        FilterRegistrationBean<FlowHttpMetricsFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new FlowHttpMetricsFilter(meterRegistry));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** 使用 HTTP Session 保存 REST 登录身份。 */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** 密码仅以 BCrypt 哈希保存。 */
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    /** 数据库凭据校验器。 */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
}
