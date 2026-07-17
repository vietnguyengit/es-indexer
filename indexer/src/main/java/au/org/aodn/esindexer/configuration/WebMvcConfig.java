package au.org.aodn.esindexer.configuration;

import au.org.aodn.esindexer.service.VocabService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;

@Configuration
@EnableCaching
public class WebMvcConfig {

    /**
     * Inner configuration to enable scheduling only for non-batch profiles.
     * This (combined with spring.task.scheduling.enabled=false in batch yaml and !batch profile on VocabIndexScheduler)
     * prevents the cron/scheduler executor threads from starting in batch mode.
     */
    @Configuration
    @Profile("!batch")
    @EnableScheduling
    public static class SchedulingEnabledConfig {
    }

    @Autowired
    protected ObjectMapper indexerObjectMapper;

    @Bean
    public ConcurrentMapCacheManager cacheManager() {
        return new ConcurrentMapCacheManager(
                VocabService.VocabType.Names.AODN_DISCOVERY_PARAMETER_VOCABS,
                VocabService.VocabType.Names.AODN_PLATFORM_VOCABS,
                VocabService.VocabType.Names.AODN_ORGANISATION_VOCABS
        );
    }

    @PostConstruct
    public void init() {
        JavaTimeModule module = new JavaTimeModule();

        // Avoid output date-time string become number
        indexerObjectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        indexerObjectMapper.registerModule(module);
    }

    @Bean("indexerRestTemplate")
    public RestTemplate indexerRestTemplate() {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setSupportedMediaTypes(Arrays.asList(MediaType.parseMediaType("text/plain;charset=utf-8"), MediaType.APPLICATION_OCTET_STREAM));

        // Without timeouts a stalled GeoNetwork connection blocks indexing forever
        // and @Retryable never fires because no exception is thrown
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);

        final RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.getMessageConverters().add(converter);

        return restTemplate;
    }
}
