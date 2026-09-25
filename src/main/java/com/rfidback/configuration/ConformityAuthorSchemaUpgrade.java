package com.rfidback.configuration;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// A conformity change made at the line kiosk has a reader as author and no user (spec 008, research R14), but
// ddl-auto: update never relaxes the NOT NULL it once put on author_id. Idempotent on H2 and PostgreSQL.
// Remove this class once a migration tool (Flyway/Liquibase) manages the schema.
@Slf4j
@Component
@RequiredArgsConstructor
public class ConformityAuthorSchemaUpgrade implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        // A failure must stop startup rather than answer 500 on the first kiosk change.
        jdbcTemplate.execute("ALTER TABLE record_conformity_change ALTER COLUMN author_id DROP NOT NULL");
        log.info("record_conformity_change.author_id is nullable (spec 008, kiosk conformity changes)");
    }
}
