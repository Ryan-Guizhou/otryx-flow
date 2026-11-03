package com.peachsoft.otryx.flow.api.security;

import com.peachsoft.otryx.flow.api.auth.AuthController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 密码变更后使旧会话失效。
 *
 * <p>比较登录时写入的凭据版本和数据库中的当前版本。不记录密码。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
public class CredentialVersionFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;

    /** 只读取身份库中的版本号。 */
    public CredentialVersionFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        if (authentication != null && authentication.isAuthenticated() && session != null
                && authentication.getName() != null && !"anonymousUser".equals(authentication.getName())) {
            Object stored = session.getAttribute(AuthController.CREDENTIAL_VERSION);
            Integer current = jdbc.query("SELECT credential_version FROM flow_user WHERE username=?",
                    rs -> rs.next() ? rs.getInt(1) : null, authentication.getName());
            int seen = stored instanceof Integer version ? version : -1;
            if (current != null && current > seen) {
                session.invalidate();
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
