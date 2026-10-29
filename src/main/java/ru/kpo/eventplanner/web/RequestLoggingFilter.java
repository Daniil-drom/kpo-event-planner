package ru.kpo.eventplanner.web;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long started = System.nanoTime();
        int status = 500;
        try {
            filterChain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            // Параметры, тело запроса и заголовки могут содержать пароль или cookie.
            LOG.info("http method={} path={} status={} duration_ms={}",
                    request.getMethod(), request.getRequestURI(), status, durationMs);
        }
    }
}
