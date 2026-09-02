package com.aetherops.auth.security;

import com.aetherops.auth.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CurrentUserContextFilterTest {

    @Mock private JwtService jwtService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;

    @InjectMocks
    private CurrentUserContextFilter filter;

    @AfterEach
    void cleanup() {
        CurrentUserContext.clear();
    }

    @Test
    void setsCurrentUserContext_fromValidJwt() throws Exception {
        UUID userId = UUID.randomUUID();
        when(request.getHeader("Authorization")).thenReturn("Bearer valid.jwt.token");
        when(jwtService.isTokenValid("valid.jwt.token")).thenReturn(true);
        when(jwtService.isPartialToken("valid.jwt.token")).thenReturn(false);
        when(jwtService.extractUserId("valid.jwt.token")).thenReturn(userId);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(CurrentUserContext.get()).isNull(); // cleared in finally
    }

    @Test
    void skipsCurrentUserContext_whenNoAuthHeader() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(jwtService, never()).isTokenValid(anyString());
    }

    @Test
    void skipsCurrentUserContext_forPartialToken() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer partial.token");
        when(jwtService.isTokenValid("partial.token")).thenReturn(true);
        when(jwtService.isPartialToken("partial.token")).thenReturn(true);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(jwtService, never()).extractUserId(anyString());
    }

    @Test
    void clearsCurrentUserContext_inFinally() throws Exception {
        UUID userId = UUID.randomUUID();
        when(request.getHeader("Authorization")).thenReturn("Bearer valid.jwt.token");
        when(jwtService.isTokenValid("valid.jwt.token")).thenReturn(true);
        when(jwtService.isPartialToken("valid.jwt.token")).thenReturn(false);
        when(jwtService.extractUserId("valid.jwt.token")).thenReturn(userId);
        doAnswer(inv -> {
            assertThat(CurrentUserContext.get()).isEqualTo(userId);
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilterInternal(request, response, chain);

        assertThat(CurrentUserContext.get()).isNull();
    }
}
