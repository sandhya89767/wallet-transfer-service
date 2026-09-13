package com.paytm.wallet.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    public static final String HEADER = "X-Correlation-Id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String correlationId = supplied != null && SAFE_ID.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
        response.setHeader(HEADER, correlationId);
        long started = System.nanoTime();
        MDC.put("correlation_id", correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            log.atInfo().addKeyValue("event", "request.completed")
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000)
                    .log("Request completed");
            MDC.remove("correlation_id");
        }
    }
}