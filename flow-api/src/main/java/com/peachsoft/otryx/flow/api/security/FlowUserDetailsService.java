package com.peachsoft.otryx.flow.api.security;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** 用户身份查询仅访问 flow-api 持有的数据库。 */
@Service
public class FlowUserDetailsService implements UserDetailsService {
    private final JdbcTemplate jdbc;

    /** 创建身份查询服务。 */
    public FlowUserDetailsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        List<UserDetails> matched = jdbc.query(
                "SELECT username, password_hash, role_name, enabled FROM flow_user WHERE username = ?",
                (rs, n) -> User.withUsername(rs.getString("username"))
                        .password(rs.getString("password_hash"))
                        .roles(rs.getString("role_name"))
                        .disabled(!rs.getBoolean("enabled")).build(), username);
        if (matched.isEmpty()) { throw new UsernameNotFoundException("Unknown principal"); }
        return matched.getFirst();
    }
}
