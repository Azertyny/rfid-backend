package com.rfidback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Spec 010: the shipped list loads and is matched on the last 12 characters (FR-002, SC-002), and a broken one
 * stops the boot (research R2).
 */
class ReferenceTagListTest {

    private static final String SHIPPED = "tags/rfid_tag_list.csv";

    @Test
    void shippedList_containsEveryUidOfTheFile() throws IOException {
        ReferenceTagList list = new ReferenceTagList(new ClassPathResource(SHIPPED));

        List<String> lines = readLines(SHIPPED);
        assertThat(list.size()).isEqualTo(5008);
        assertThat(lines).hasSize(5008).allSatisfy(uid -> assertThat(list.contains(uid)).isTrue());
    }

    @Test
    void shippedList_ignoresCaseAndSurroundingSpaces() throws IOException {
        ReferenceTagList list = new ReferenceTagList(new ClassPathResource(SHIPPED));
        String uid = readLines(SHIPPED).get(0);

        assertThat(list.contains("  " + uid.toLowerCase(Locale.ROOT) + " ")).isTrue();
        assertThat(list.isOffList(uid)).isFalse();
    }

    @Test
    void shippedList_rejectsUnknownAndBlankUids() {
        ReferenceTagList list = new ReferenceTagList(new ClassPathResource(SHIPPED));

        assertThat(list.contains("E2000017221101891400A23G")).isFalse();
        assertThat(list.contains(null)).isFalse();
        assertThat(list.contains("  ")).isFalse();
        assertThat(list.isOffList("E2000017221101891400A23G")).isTrue();
    }

    @Test
    void shippedList_findsEveryUidInTheFormTheReadersSend() throws IOException {
        ReferenceTagList list = new ReferenceTagList(new ClassPathResource(SHIPPED));

        // The readers send 0000 where the file has 2000, in characters 9 to 12 (spec 010, FR-002).
        assertThat(readLines(SHIPPED)).allSatisfy(uid -> {
            assertThat(uid.substring(8, 12)).isEqualTo("2000");
            assertThat(list.contains(uid.substring(0, 8) + "0000" + uid.substring(12))).isTrue();
        });
        assertThat(list.contains("E2806915000040287477C993")).isTrue();
    }

    @Test
    void shippedList_comparesOnlyTheLast12Characters() throws IOException {
        ReferenceTagList list = new ReferenceTagList(new ClassPathResource(SHIPPED));
        String uid = readLines(SHIPPED).get(0);
        String last12 = uid.substring(uid.length() - 12);

        assertThat(list.contains(last12)).isTrue();
        assertThat(list.contains(last12.substring(1))).isFalse();
        assertThat(list.contains("E2806915200040287477FFFF")).isFalse();
    }

    @Test
    void listWithTwoLinesEndingAlike_refusesToLoadAndNamesBothLines() {
        ClassPathResource resource = new ClassPathResource("tags/duplicate-ending-reference-list.csv");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ReferenceTagList(resource));
        assertThat(exception.getMessage()).contains("lines 1 and 2").contains("0287477D48C");
    }

    @Test
    void listWithBomLowerCaseAndCrlf_isLoadedUpperCased() {
        ReferenceTagList list = new ReferenceTagList(new ClassPathResource("tags/bom-lowercase-reference-list.csv"));

        assertThat(list.size()).isEqualTo(2);
        assertThat(list.contains("E2806915200050287477D48C")).isTrue();
        assertThat(list.contains("E2806915200040287477D049")).isTrue();
    }

    @Test
    void emptyList_refusesToLoad() {
        ClassPathResource resource = new ClassPathResource("tags/empty-reference-list.csv");

        assertThrows(IllegalStateException.class, () -> new ReferenceTagList(resource));
    }

    @Test
    void listWithAMalformedLine_refusesToLoadAndNamesTheLine() {
        ClassPathResource resource = new ClassPathResource("tags/bad-line-reference-list.csv");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ReferenceTagList(resource));
        assertThat(exception.getMessage()).contains("line 3").contains("NOT-A-TAG");
    }

    @Test
    void missingList_refusesToLoad() {
        ClassPathResource resource = new ClassPathResource("tags/missing.csv");

        assertThrows(IllegalStateException.class, () -> new ReferenceTagList(resource));
    }

    private static List<String> readLines(String path) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
        }
    }
}
