package au.org.aodn.esindexer.configuration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObjectMapperConfig {
    @Bean("indexerObjectMapper")
    public static ObjectMapper objectMapper() {
        ObjectMapper objectMapper = JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                // Enable pretty printing for JSON output
                .enable(SerializationFeature.INDENT_OUTPUT)
                // Ignore unknown properties during deserialization
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();

        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);

        return objectMapper;
    }
}
