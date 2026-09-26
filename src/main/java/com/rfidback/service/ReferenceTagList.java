package com.rfidback.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * The tags bought for the site (spec 010): any other uid is "hors liste" and never stored. Loaded once at startup
 * from {@code app.tags.reference-list}; a missing, empty or malformed list stops the boot rather than refuse every
 * tag.
 *
 * <p>A tag is identified by the last {@value #KEY_LENGTH} characters of its uid (spec 010, FR-002): the readers send
 * {@code E28069150000…} where the list has {@code E28069152000…}, and every line of the list shares the same start.
 */
@Slf4j
@Component
public class ReferenceTagList {

    private static final Pattern UID = Pattern.compile("[0-9A-Fa-f]{24}");
    private static final String BOM = "﻿";
    static final int KEY_LENGTH = 12;

    /** Last {@value #KEY_LENGTH} characters of each line, upper-cased. */
    private final Set<String> keys;

    public ReferenceTagList(@Value("${app.tags.reference-list}") Resource resource) {
        String source = resource.getDescription();
        if (!resource.exists()) {
            throw new IllegalStateException("Reference tag list not found: " + source);
        }
        // Key → line number, to name both lines when two share an ending.
        Map<String, Integer> loaded = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1 && line.startsWith(BOM)) {
                    line = line.substring(BOM.length());
                }
                String uid = line.trim();
                if (uid.isEmpty()) {
                    continue;
                }
                if (!UID.matcher(uid).matches()) {
                    throw new IllegalStateException(
                            "Reference tag list %s, line %d: '%s' is not a 24-character hexadecimal uid"
                                    .formatted(source, lineNumber, uid));
                }
                Integer previous = loaded.putIfAbsent(key(uid), lineNumber);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Reference tag list %s, lines %d and %d both end with '%s', which identifies a tag"
                                    .formatted(source, previous, lineNumber, key(uid)));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Reference tag list could not be read: " + source, exception);
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("Reference tag list is empty: " + source);
        }
        this.keys = Set.copyOf(loaded.keySet());
        log.info("Reference tag list loaded: {} uids from {}", keys.size(), source);
    }

    /**
     * Compared on the last {@value #KEY_LENGTH} characters, trimmed and upper-cased (spec 010, FR-002); a uid shorter
     * than that, or blank, is never in the list.
     */
    public boolean contains(String uid) {
        if (!StringUtils.hasText(uid)) {
            return false;
        }
        String trimmed = uid.trim();
        return trimmed.length() >= KEY_LENGTH && keys.contains(key(trimmed));
    }

    public boolean isOffList(String uid) {
        return !contains(uid);
    }

    public int size() {
        return keys.size();
    }

    private static String key(String uid) {
        return uid.substring(uid.length() - KEY_LENGTH).toUpperCase(Locale.ROOT);
    }
}
