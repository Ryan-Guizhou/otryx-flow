package com.peachsoft.otryx.flow.api.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import org.mockito.ArgumentCaptor;

/** 校验登录安全上下文显式持久化、会话轮换和拒绝错误凭据。 */
class AuthControllerTest {
    private final AuthenticationManager manager = mock(AuthenticationManager.class);
    private final SecurityContextRepository repository = mock(SecurityContextRepository.class);
    private final AuthController controller = new AuthController(manager, repository);

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validLoginCreatesSessionAndPersistsAuthenticatedContext() {
        var authentication = new UsernamePasswordAuthenticationToken("admin", "secret",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        when(manager.authenticate(any())).thenReturn(authentication);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        var outcome = controller.login(new AuthController.LoginPayload("admin", "secret"), request, response);

        assertEquals(HttpStatus.OK, outcome.getStatusCode());
        assertEquals(new AuthController.SessionUser("admin", "ADMIN"), outcome.getBody());
        assertNotNull(request.getSession(false));
        assertEquals("admin", SecurityContextHolder.getContext().getAuthentication().getName());
        ArgumentCaptor<SecurityContext> captured = ArgumentCaptor.forClass(SecurityContext.class);
        verify(repository).saveContext(captured.capture(), eq(request), eq(response));
        assertSame(authentication, captured.getValue().getAuthentication());
    }

    @Test
    void invalidCredentialsDoNotPersistSessionOrAuthentication() {
        when(manager.authenticate(any())).thenThrow(new BadCredentialsException("denied"));
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        var result = controller.login(new AuthController.LoginPayload("admin", "wrong"), request, response);

        assertEquals(HttpStatus.UNAUTHORIZED, result.getStatusCode());
        assertNull(request.getSession(false));
        verifyNoInteractions(repository);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void missingCredentialsFailBeforeAuthenticationManager() {
        var result = controller.login(new AuthController.LoginPayload(null, null),
                new MockHttpServletRequest(), new MockHttpServletResponse());
        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        verifyNoInteractions(manager, repository);
    }
}
