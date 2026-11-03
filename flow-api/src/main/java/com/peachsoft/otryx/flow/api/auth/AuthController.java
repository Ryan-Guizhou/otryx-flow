package com.peachsoft.otryx.flow.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * 面向 Vue 前端的 Session REST 认证接口。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public static final String CREDENTIAL_VERSION = "flow.credentialVersion";
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contexts;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final int lockAfter;
    private final int lockSeconds;

    /** 组装数据库认证器与会话存储。锁定次数和时长来自配置。 */
    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contexts,
            JdbcTemplate jdbc, PasswordEncoder passwords,
            @Value("${flow.auth.lock-after:5}") int lockAfter,
            @Value("${flow.auth.lock-seconds:900}") int lockSeconds) {
        this.authenticationManager = authenticationManager;
        this.contexts = contexts;
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.lockAfter = lockAfter;
        this.lockSeconds = lockSeconds;
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
            rememberCredentialVersion(request, authentication.getName());
            jdbc.update("UPDATE flow_user SET failed_attempts=0, locked_until=NULL WHERE username=?",
                    authentication.getName());
            return ResponseEntity.ok(toPublicUser(authentication));
        } catch (AuthenticationException invalid) {
            recordFailure(credentials.username());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "INVALID_CREDENTIALS"));
        }
    }

    /** 校验旧密码后更新哈希，并立即使当前会话失效。 */
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(Authentication authentication, @RequestBody ChangePassword body,
            HttpServletRequest request, HttpServletResponse response) {
        if (authentication == null || body == null || body.currentPassword() == null || body.nextPassword() == null
                || body.nextPassword().length() < 12) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_PASSWORD"));
        }
        String hash = jdbc.query("SELECT password_hash FROM flow_user WHERE username=?",
                rs -> rs.next() ? rs.getString(1) : null, authentication.getName());
        if (hash == null || !passwords.matches(body.currentPassword(), hash)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "INVALID_CREDENTIALS"));
        }
        jdbc.update("UPDATE flow_user SET password_hash=?, credential_version=credential_version+1, "
                        + "password_changed_at=?, failed_attempts=0, locked_until=NULL WHERE username=?",
                passwords.encode(body.nextPassword()), Timestamp.from(Instant.now()), authentication.getName());
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return ResponseEntity.noContent().build();
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

    private void recordFailure(String username) {
        if (username == null || !username.matches("[a-zA-Z0-9_.-]{1,64}")) return;
        jdbc.update("UPDATE flow_user SET failed_attempts=failed_attempts+1 WHERE username=?", username);
        jdbc.update("UPDATE flow_user SET locked_until=? WHERE username=? AND failed_attempts>=?",
                Timestamp.from(Instant.now().plusSeconds(lockSeconds)), username, lockAfter);
    }

    private void rememberCredentialVersion(HttpServletRequest request, String username) {
        Integer version = jdbc.queryForObject("SELECT credential_version FROM flow_user WHERE username=?",
                Integer.class, username);
        request.getSession().setAttribute(CREDENTIAL_VERSION, version == null ? 0 : version);
    }

    /** 登录请求。 */
    public record LoginPayload(String username, String password) { }
    /** 自己修改密码。新密码至少 12 个字符，响应中不回显。 */
    public record ChangePassword(String currentPassword, String nextPassword) { }
    /** 对外返回的安全会话模型。 */
    public record SessionUser(String username, String role) { }
}
