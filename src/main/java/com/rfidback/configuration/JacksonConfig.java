package com.rfidback.configuration;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rfidback.generated.model.ConformityChange;
import com.rfidback.generated.model.Reader;

@Configuration
public class JacksonConfig {

    // Generated models can't be annotated directly: the mix-ins omit fields that are not set instead of serializing
    // null. Reader.apitoken is absent for Opérateurs; ConformityChange carries either authorUsername (USER) or
    // authorReaderUid (READER, line kiosk, spec 008 FR-005a). Other models keep their null fields.
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer optionalFieldInclusion() {
        return builder -> builder.mixIn(Reader.class, ReaderMixIn.class)
                .mixIn(ConformityChange.class, ConformityChangeMixIn.class);
    }

    abstract static class ReaderMixIn {

        @JsonInclude(JsonInclude.Include.NON_NULL)
        abstract String getApitoken();
    }

    abstract static class ConformityChangeMixIn {

        @JsonInclude(JsonInclude.Include.NON_NULL)
        abstract String getAuthorUsername();

        @JsonInclude(JsonInclude.Include.NON_NULL)
        abstract String getAuthorReaderUid();
    }
}
