package com.rfidback.controller;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.rfidback.entity.ReaderEntity;
import com.rfidback.security.ReaderAuthentication;

/** The reader authenticated by its x-api-token on the reader routes (/tags/scan, /tags/registration-reads). */
final class AuthenticatedReader {

    private AuthenticatedReader() {
    }

    static Optional<ReaderEntity> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof ReaderAuthentication readerAuthentication) {
            return Optional.of((ReaderEntity) readerAuthentication.getPrincipal());
        }
        return Optional.empty();
    }

    static ReaderEntity require() {
        return current().orElseThrow(() -> new IllegalStateException("Authenticated reader not found in context"));
    }
}
