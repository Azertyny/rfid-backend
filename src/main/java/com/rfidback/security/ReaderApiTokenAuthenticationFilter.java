package com.rfidback.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
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

// Authenticates every request it sees by the reader's x-api-token. Where it runs is decided by the securityMatcher of
// the reader chains in SecurityConfig: reader scans and registration batches (spec 011), and the line kiosk's own
// records (spec 008, research R11).
@Component
@RequiredArgsConstructor
public class ReaderApiTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "x-api-token";

    private final ReaderRepository readerRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

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
        if (!reader.isActive()) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Reader disabled");
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
