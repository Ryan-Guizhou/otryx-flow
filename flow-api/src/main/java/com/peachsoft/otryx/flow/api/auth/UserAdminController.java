package com.peachsoft.otryx.flow.api.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员维护操作员和只读账号。
 *
 * <p>密码只以哈希保存。重置会提升凭据版本，使旧会话在下一次请求时失效。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserAdminController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    /** 身份数据只写 flow-api 自己的用户表。 */
    public UserAdminController(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    /** 创建 OPERATOR 或 VIEWER。不通过此接口新增管理员。 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> create(@RequestBody CreateUser body) {
        if (body == null || body.username() == null || !body.username().matches("[a-zA-Z0-9_.-]{1,64}")
                || body.password() == null || body.password().length() < 12
                || !("OPERATOR".equals(body.role()) || "VIEWER".equals(body.role()))) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_USER"));
        }
        int inserted = jdbc.update("INSERT IGNORE INTO flow_user(username, password_hash, role_name, enabled, "
                        + "failed_attempts, credential_version) VALUES (?,?,?,TRUE,0,1)",
                body.username(), passwords.encode(body.password()), body.role());
        if (inserted == 0) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "USER_EXISTS"));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("username", body.username(), "role", body.role()));
    }

    /** 重置密码并立即使该账号的旧会话失效。 */
    @PostMapping("/{username}/password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> reset(@PathVariable String username, @RequestBody PasswordBody body) {
        if (!username.matches("[a-zA-Z0-9_.-]{1,64}") || body == null || body.password() == null
                || body.password().length() < 12) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_PASSWORD"));
        }
        int updated = jdbc.update("UPDATE flow_user SET password_hash=?, credential_version=credential_version+1, "
                        + "password_changed_at=?, failed_attempts=0, locked_until=NULL WHERE username=?",
                passwords.encode(body.password()), Timestamp.from(Instant.now()), username);
        return updated == 1 ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "USER_NOT_FOUND"));
    }

    /** 禁用或恢复账号。禁用用户不能再登录。 */
    @PostMapping("/{username}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> enabled(@PathVariable String username, @RequestBody EnabledBody body) {
        if (!username.matches("[a-zA-Z0-9_.-]{1,64}") || body == null) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_USER"));
        }
        int updated = jdbc.update("UPDATE flow_user SET enabled=?, credential_version=credential_version+1 "
                + "WHERE username=?", body.enabled(), username);
        return updated == 1 ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "USER_NOT_FOUND"));
    }

    /** 新账号。角色只允许操作员或只读。 */
    public record CreateUser(@NotBlank @Size(max = 64) String username,
            @NotBlank @Size(min = 12, max = 128) String password,
            @Pattern(regexp = "OPERATOR|VIEWER") String role) { }
    /** 管理员重置用的新密码。 */
    public record PasswordBody(@NotBlank @Size(min = 12, max = 128) String password) { }
    /** 是否允许继续登录。 */
    public record EnabledBody(boolean enabled) { }
}
