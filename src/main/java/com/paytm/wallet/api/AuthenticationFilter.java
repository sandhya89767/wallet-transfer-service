package com.paytm.wallet.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import java.util.Map;

@Component
public class AuthenticationFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/metrics",
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/actuator/prometheus");

    private final BearerTokens tokens;
    private final ObjectMapper mapper;

    public AuthenticationFilter(BearerTokens tokens, ObjectMapper mapper) {
        this.tokens = tokens;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (PUBLIC_PATHS.contains(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String userId;
        try {
            if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                throw new IllegalArgumentException();
            }
            userId = tokens.verify(authorization.substring(7).trim());
        } catch (IllegalArgumentException exception) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            mapper.writeValue(response.getWriter(), Map.of(
                    "code", "unauthorized", "message", "A valid, unexpired bearer token is required",
                    "correlation_id", MDC.get("correlation_id")));
            return;
        }

        request.setAttribute("userId", userId);
        filterChain.doFilter(request, response);
    }
}