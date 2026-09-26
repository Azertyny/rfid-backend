package com.rfidback.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * The tags bought for the site (spec 010): any other uid is "hors liste". Loaded once at startup from
 * {@code app.tags.reference-list}; a missing, empty or malformed list stops the boot rather than flag every tag.
 */
@Slf4j
@Component
public class ReferenceTagList {

    private static final Pattern UID = Pattern.compile("[0-9A-Fa-f]{24}");
    private static final String BOM = "﻿";

    private final Set<String> uids;

    public ReferenceTagList(@Value("${app.tags.reference-list}") Resource resource) {
        String source = resource.getDescription();
        if (!resource.exists()) {
            throw new IllegalStateException("Reference tag list not found: " + source);
        }
        Set<String> loaded = new HashSet<>();
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
                loaded.add(uid.toUpperCase(Locale.ROOT));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Reference tag list could not be read: " + source, exception);
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("Reference tag list is empty: " + source);
        }
        this.uids = Set.copyOf(loaded);
        log.info("Reference tag list loaded: {} uids from {}", uids.size(), source);
    }

    /** Compared trimmed and upper-cased (spec 010, FR-002); a blank uid is never in the list. */
    public boolean contains(String uid) {
        return StringUtils.hasText(uid) && uids.contains(uid.trim().toUpperCase(Locale.ROOT));
    }

    public boolean isOffList(String uid) {
        return !contains(uid);
    }

    public int size() {
        return uids.size();
    }
}
