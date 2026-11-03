package com.peachsoft.otryx.flow.api.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 用户身份查询仅访问 flow-api 持有的数据库。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
@Service
public class FlowUserDetailsService implements UserDetailsService {
    private final JdbcTemplate jdbc;

    /** 创建身份查询服务。 */
    public FlowUserDetailsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        List<UserDetails> matched = jdbc.query(
                "SELECT username, password_hash, role_name, enabled, locked_until FROM flow_user WHERE username = ?",
                (rs, n) -> {
                    Timestamp locked = rs.getTimestamp("locked_until");
                    boolean lockedNow = locked != null && locked.toInstant().isAfter(Instant.now());
                    return User.withUsername(rs.getString("username"))
                            .password(rs.getString("password_hash"))
                            .roles(rs.getString("role_name"))
                            .disabled(!rs.getBoolean("enabled") || lockedNow).build();
                }, username);
        if (matched.isEmpty()) { throw new UsernameNotFoundException("Unknown principal"); }
        return matched.getFirst();
    }
}
