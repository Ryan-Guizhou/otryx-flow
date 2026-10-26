package com.peachsoft.otryx.flow.api.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 管理员初始化必须强制使用显式凭据，且已有账户不得被覆盖。 */
class FlowBootstrapAdminTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    @Test
    void insecureBootstrapCredentialsFailBeforeDatabaseAccess() {
        var initializer = new FlowBootstrapAdmin(jdbc, encoder, "admin", "short");
        assertThrows(IllegalStateException.class, () -> initializer.run(new DefaultApplicationArguments()));
        verifyNoInteractions(jdbc, encoder);
    }

    @Test
    void existingAdminIsNotRecreatedOrOverwritten() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("admin"))).thenReturn(1);
        var initializer = new FlowBootstrapAdmin(jdbc, encoder, "admin", "safe-password-2026");
        initializer.run(new DefaultApplicationArguments());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(encoder);
    }
}
