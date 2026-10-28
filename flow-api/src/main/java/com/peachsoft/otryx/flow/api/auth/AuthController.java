package com.peachsoft.otryx.flow.api.auth;

import com.peachsoft.otryx.flow.api.security.FlowSecurityMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供 Session REST 认证接口，并记录不含身份标识的登录失败计数。
 *
 * @Author Ryan
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/28 11:45
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contexts;
    private final FlowSecurityMetrics securityMetrics;

    /**
     * 组装数据库认证器、Session 仓储和安全事件计数器。
     *
     * @param authenticationManager Spring Security 认证管理器
     * @param contexts Session 安全上下文仓储
     * @param securityMetrics 低基数安全事件计数器
     */
    public AuthController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository contexts,
            FlowSecurityMetrics securityMetrics) {
        this.authenticationManager = Objects.requireNonNull(authenticationManager, "authenticationManager");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.securityMetrics = Objects.requireNonNull(securityMetrics, "securityMetrics");
    }

    /**
     * 为状态更改请求预先生成 CSRF 令牌。
     *
     * @param token Spring Security 生成的 CSRF 令牌
     * @return 令牌头名称与值
     */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    /**
     * 校验凭据，并将安全上下文保存到 Session。
     *
     * @param credentials 登录凭据
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @return 认证用户或固定错误码；不会返回密码、密码哈希或异常消息
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody LoginPayload credentials,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (credentials == null || credentials.username() == null || credentials.password() == null) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST"));
        }
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(credentials.username(), credentials.password()));
            request.getSession(true);
            request.changeSessionId();
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            return ResponseEntity.ok(toPublicUser(authentication));
        } catch (AuthenticationException invalid) {
            securityMetrics.recordLoginFailure();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "INVALID_CREDENTIALS"));
        }
    }

    /**
     * 返回当前认证用户，不包含密码哈希。
     *
     * @param authentication 当前认证身份
     * @return 对外公开的会话用户模型
     */
    @GetMapping("/me")
    public SessionUser me(Authentication authentication) {
        return toPublicUser(authentication);
    }

    /**
     * 销毁服务端 Session 凭据。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param authentication 当前认证身份
     * @return HTTP 204 空响应
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }

    /**
     * 将认证身份映射为不含敏感凭据的公开模型。
     *
     * @param authentication 已认证身份
     * @return 用户名与角色，不含密码或令牌
     */
    private static SessionUser toPublicUser(Authentication authentication) {
        String role = authentication.getAuthorities().stream()
                .map(granted -> granted.getAuthority())
                .filter(value -> value.startsWith("ROLE_"))
                .map(value -> value.substring(5))
                .findFirst()
                .orElse("USER");
        return new SessionUser(authentication.getName(), role);
    }

    /** 登录请求。 */
    public record LoginPayload(String username, String password) {
    }

    /** 对外返回的安全会话模型。 */
    public record SessionUser(String username, String role) {
    }
}
