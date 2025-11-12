package com.rfidback.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.rfidback.entity.ReaderEntity;
import com.rfidback.repository.ReaderRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ReaderApiTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "x-api-token";
    private final RequestMatcher protectedEndpoint = request -> {
        // normalize the request path by removing the context path (if any) and match
        // exact endpoint
        String expected = "/api/tags/scan";
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return expected.equals(path);
    };

    private final ReaderRepository readerRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!protectedEndpoint.matches(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiToken = request.getHeader(HEADER_NAME);
        if (!StringUtils.hasText(apiToken)) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Missing API token");
            return;
        }

        ReaderEntity reader = readerRepository.findByApitoken(apiToken).orElse(null);
        if (reader == null) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid API token");
            return;
        }

        try {
            ReaderAuthentication authentication = new ReaderAuthentication(reader);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
