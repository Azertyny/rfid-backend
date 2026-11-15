package com.rfidback.configuration;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.rfidback.generated.model.ConformityChange;
import com.rfidback.generated.model.Reader;
import com.rfidback.generated.model.UpdateScanSettings;

@Configuration
public class JacksonConfig {

    // Generated models can't be annotated directly: the mix-ins omit fields that are not set instead of serializing
    // null. Reader.apitoken is absent for Opérateurs; ConformityChange carries either authorUsername (USER) or
    // authorReaderUid (READER, line kiosk, spec 008 FR-005a). Other models keep their null fields.
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer optionalFieldInclusion() {
        return builder -> builder.mixIn(Reader.class, ReaderMixIn.class)
                .mixIn(ConformityChange.class, ConformityChangeMixIn.class)
                .mixIn(UpdateScanSettings.class, UpdateScanSettingsMixIn.class);
    }

    abstract static class ReaderMixIn {

        @JsonInclude(JsonInclude.Include.NON_NULL)
        abstract String getApitoken();
    }

    // A scan delay is a whole number of minutes or seconds (spec 015, FR-013): Jackson would otherwise turn 2.5 into 2
    // and the bean validation would accept it. Only these two fields are strict; other integers keep Jackson's rules.
    abstract static class UpdateScanSettingsMixIn {
        @JsonDeserialize(using = WholeNumber.class)
        abstract void setRereadWindowMinutes(Integer value);

        @JsonDeserialize(using = WholeNumber.class)
        abstract void setCheatWindowSeconds(Integer value);
    }

    static class WholeNumber extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            }
            return parser.getIntValue();
        }
    }

    abstract static class ConformityChangeMixIn {

        @JsonInclude(JsonInclude.Include.NON_NULL)
        abstract String getAuthorUsername();

        @JsonInclude(JsonInclude.Include.NON_NULL)
        abstract String getAuthorReaderUid();
    }
}
