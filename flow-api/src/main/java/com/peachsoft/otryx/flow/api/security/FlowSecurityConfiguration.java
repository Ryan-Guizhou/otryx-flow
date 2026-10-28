package com.peachsoft.otryx.flow.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * 配置同源 Session 认证、CSRF 防护和低基数安全事件指标。
 *
 * @Author Ryan
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/28 11:45
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class FlowSecurityConfiguration {

    /**
     * 配置业务接口认证、匿名请求响应和 CSRF 校验。
     *
     * @param http Spring Security HTTP 配置器
     * @param contexts Session 安全上下文仓储
     * @param metrics 固定名称的安全事件计数器
     * @return 已配置的安全过滤器链
     * @throws Exception 安全过滤器链构建失败
     */
    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            SecurityContextRepository contexts,
            FlowSecurityMetrics metrics) throws Exception {
        return http
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint((request, response, exception) -> {
                            metrics.recordAuthenticationFailure();
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            if (exception instanceof CsrfException) {
                                metrics.recordCsrfDenial();
                            } else {
                                metrics.recordAuthorizationDenial();
                            }
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                        }))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/auth/csrf",
                                "/actuator/health",
                                "/actuator/info")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(new CookieCsrfTokenRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(securityContext -> securityContext.securityContextRepository(contexts))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .formLogin(form -> form.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .logout(logout -> logout.disable())
                .build();
    }

    /**
     * 使用 HTTP Session 保存 REST 登录身份。
     *
     * @return Session 安全上下文仓储
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * 提供 BCrypt 密码编码器。
     *
     * @return 密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 配置基于数据库用户信息的认证管理器。
     *
     * @param users 用户详情服务
     * @param encoder 密码编码器
     * @return 认证管理器
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
}
