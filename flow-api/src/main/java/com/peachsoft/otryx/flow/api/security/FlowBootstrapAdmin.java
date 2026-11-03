package com.peachsoft.otryx.flow.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 系统首次启动初始化管理员，不自动更新已存在的密码。
 *
 * <p>演示访客只在显式配置密码时创建，角色固定为 DEMO_GUEST。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 11:20
 */
@Component
public class FlowBootstrapAdmin implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;
    private final String demoPassword;

    /** 要求部署时提供管理员凭据，不内置默认密码。演示访客密码为空时不创建该账号。 */
    public FlowBootstrapAdmin(JdbcTemplate jdbc, PasswordEncoder encoder,
            @Value("${flow.bootstrap.admin-username}") String username,
            @Value("${flow.bootstrap.admin-password}") String password,
            @Value("${flow.bootstrap.demo-guest-password:}") String demoPassword) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.username = username;
        this.password = password;
        this.demoPassword = demoPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username == null || !username.matches("[a-zA-Z0-9_.-]{1,64}")
                || password == null || password.length() < 12
                || password.startsWith("REPLACE_WITH_")) {
            throw new IllegalStateException("Valid bootstrap admin credentials are required");
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM flow_user WHERE username = ?",
                Integer.class, username);
        if (count != null && count == 0) {
            // INSERT IGNORE safely tolerates two startup replicas racing on the unique username.
            jdbc.update("INSERT IGNORE INTO flow_user(username, password_hash, role_name, enabled) "
                    + "VALUES (?, ?, 'ADMIN', true)", username, encoder.encode(password));
        }
        if (demoPassword != null && demoPassword.length() >= 12
                && !demoPassword.startsWith("REPLACE_WITH_")) {
            jdbc.update("INSERT IGNORE INTO flow_user(username, password_hash, role_name, enabled) "
                    + "VALUES ('demo', ?, 'DEMO_GUEST', true)", encoder.encode(demoPassword));
        }
    }
}
