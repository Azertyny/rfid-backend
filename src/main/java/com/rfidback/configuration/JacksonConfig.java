package com.rfidback.configuration;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rfidback.generated.model.Reader;

@Configuration
public class JacksonConfig {

    // Generated models can't be annotated directly: the mix-in omits Reader.apitoken when it is not set
    // (for Opérateurs) instead of serializing "apitoken": null. Other models keep their null fields.
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer readerApiTokenInclusion() {
        return builder -> builder.mixIn(Reader.class, ReaderMixIn.class);
    }

    abstract static class ReaderMixIn {

        @JsonInclude(JsonInclude.Include.NON_NULL)
        abstract String getApitoken();
    }
}
