package com.rfidback.configuration;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** The startup fix that lets a conformity change have a reader as author (spec 008 amendment, research R14). */
class ConformityAuthorSchemaUpgradeTest {

    @Test
    void makesAuthorIdNullable_andCanRunAgain() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:schema-upgrade-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        // The table as ddl-auto created it before the amendment.
        jdbcTemplate.execute("create table record_conformity_change (id uuid primary key, author_id uuid not null)");
        ConformityAuthorSchemaUpgrade upgrade = new ConformityAuthorSchemaUpgrade(jdbcTemplate);

        upgrade.run(null);

        assertThatCode(() -> jdbcTemplate.update(
                "insert into record_conformity_change (id, author_id) values (random_uuid(), null)"))
                .doesNotThrowAnyException();
        assertThatCode(() -> upgrade.run(null)).doesNotThrowAnyException();
    }
}
