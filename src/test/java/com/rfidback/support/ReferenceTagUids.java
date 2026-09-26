package com.rfidback.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.core.io.ClassPathResource;

/**
 * Tag uids for tests (spec 010, research R7). Tests share one database, so each in-list uid is handed out once per
 * test run; an off-list uid is random.
 */
public final class ReferenceTagUids {

    private static final AtomicInteger NEXT = new AtomicInteger();

    private ReferenceTagUids() {
    }

    public static String nextInList() {
        int index = NEXT.getAndIncrement();
        if (index >= Holder.UIDS.size()) {
            throw new IllegalStateException("All %d reference uids are used".formatted(Holder.UIDS.size()));
        }
        return Holder.UIDS.get(index);
    }

    public static String offList() {
        return "OFF-LIST-" + UUID.randomUUID();
    }

    private static final class Holder {

        private static final List<String> UIDS = load();

        private static List<String> load() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new ClassPathResource("tags/rfid_tag_list.csv").getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }
}
