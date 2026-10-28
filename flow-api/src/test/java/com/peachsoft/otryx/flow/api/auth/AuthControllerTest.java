package com.peachsoft.otryx.flow.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.peachsoft.otryx.flow.api.security.FlowSecurityMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * 验证 Session 登录、上下文持久化和低基数凭据失败计数。
 *
 * @Author Ryan
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/28 11:45
 */
class AuthControllerTest {

    private final AuthenticationManager manager = mock(AuthenticationManager.class);
    private final SecurityContextRepository repository = mock(SecurityContextRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final FlowSecurityMetrics securityMetrics = new FlowSecurityMetrics(meterRegistry);
    private final AuthController controller = new AuthController(manager, repository, securityMetrics);

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        meterRegistry.close();
    }

    @Test
    void validLoginCreatesSessionAndPersistsAuthenticatedContext() {
        var authentication = new UsernamePasswordAuthenticationToken(
                "admin",
                "secret",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        when(manager.authenticate(any())).thenReturn(authentication);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        var outcome = controller.login(
                new AuthController.LoginPayload("admin", "secret"),
                request,
                response);

        assertEquals(HttpStatus.OK, outcome.getStatusCode());
        assertEquals(new AuthController.SessionUser("admin", "ADMIN"), outcome.getBody());
        assertNotNull(request.getSession(false));
        assertEquals("admin", SecurityContextHolder.getContext().getAuthentication().getName());
        ArgumentCaptor<SecurityContext> captured = ArgumentCaptor.forClass(SecurityContext.class);
        verify(repository).saveContext(captured.capture(), eq(request), eq(response));
        assertSame(authentication, captured.getValue().getAuthentication());
        assertEquals(0.0, meterRegistry.get("flow.security.login.failures").counter().count());
    }

    @Test
    void invalidCredentialsDoNotPersistSessionOrAuthentication() {
        when(manager.authenticate(any())).thenThrow(new BadCredentialsException("denied"));
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        var result = controller.login(
                new AuthController.LoginPayload("admin", "wrong"),
                request,
                response);

        assertEquals(HttpStatus.UNAUTHORIZED, result.getStatusCode());
        assertNull(request.getSession(false));
        verifyNoInteractions(repository);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(1.0, meterRegistry.get("flow.security.login.failures").counter().count());
        assertEquals(0, meterRegistry.get("flow.security.login.failures").tags().size());
    }

    @Test
    void missingCredentialsFailBeforeAuthenticationManager() {
        var result = controller.login(
                new AuthController.LoginPayload(null, null),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());
        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        verifyNoInteractions(manager, repository);
        assertEquals(0.0, meterRegistry.get("flow.security.login.failures").counter().count());
    }
}
