package com.peachsoft.otryx.flow.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
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

/** 面向 Vue 前端的 Session REST 认证接口。 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contexts;

    /** 组装数据库认证器与会话存储。 */
    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contexts) {
        this.authenticationManager = authenticationManager; this.contexts = contexts;
    }

    /** 为状态更改请求预先生成 CSRF 令牌。 */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    /** 校验凭据，并将安全上下文保存到 Session。 */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginPayload credentials,
            HttpServletRequest request, HttpServletResponse response) {
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
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "INVALID_CREDENTIALS"));
        }
    }

    /** 返回当前认证用户，不包含密码哈希。 */
    @GetMapping("/me")
    public SessionUser me(Authentication authentication) {
        return toPublicUser(authentication);
    }

    /** 销毁服务端 Session 凭据。 */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }

    private static SessionUser toPublicUser(Authentication authentication) {
        String role = authentication.getAuthorities().stream()
                .map(granted -> granted.getAuthority())
                .filter(value -> value.startsWith("ROLE_"))
                .map(value -> value.substring(5))
                .findFirst().orElse("USER");
        return new SessionUser(authentication.getName(), role);
    }

    /** 登录请求。 */
    public record LoginPayload(String username, String password) { }
    /** 对外返回的安全会话模型。 */
    public record SessionUser(String username, String role) { }
}
