package au.org.aodn.datadiscoveryai.configuration;

import au.org.aodn.datadiscoveryai.service.DataDiscoveryAiService;
import au.org.aodn.datadiscoveryai.service.DataDiscoveryAiServiceImpl;
import au.org.aodn.datadiscoveryai.service.GzipRequestResponseInterceptor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import reactor.netty.http.client.HttpClient;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Slf4j
@AutoConfiguration
@ConditionalOnMissingBean(DataDiscoveryAiAutoConfiguration.class)
public class DataDiscoveryAiAutoConfiguration {
    protected ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Bean
    @ConditionalOnMissingBean(DataDiscoveryAiService.class)
    public DataDiscoveryAiServiceImpl createDataDiscoveryAiService(
            @Value("${datadiscoveryai.host}") String serviceUrl,
            @Value("${datadiscoveryai.baseUrl}") String baseUrl,
            @Value("${datadiscoveryai.apiKey:TEMP}") String apiKey,
            @Value("${datadiscoveryai.internalAiHeaderSecret:TEMP}") String internalKey,
            @Qualifier("dataDiscoveryAiWebClient") WebClient  webClient) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000);
        factory.setReadTimeout(60000);

        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.setInterceptors(Collections.singletonList(new GzipRequestResponseInterceptor(apiKey,internalKey)));

        return new DataDiscoveryAiServiceImpl(serviceUrl, baseUrl, restTemplate, webClient, objectMapper);
    }

    @Bean("dataDiscoveryAiWebClient")
    public WebClient createAIWebClient(@Value("${datadiscoveryai.apiKey:TEMP}") String apiKey, @Value("${datadiscoveryai.internalAiHeaderSecret:TEMP}") String internalHeaderSecret) {
        HttpHeaders defaultHeaders = new HttpHeaders();
        defaultHeaders.add("X-API-Key", apiKey);
        defaultHeaders.add("X-INTERNAL-AI-HEADER-SECRET", internalHeaderSecret);
        defaultHeaders.add(HttpHeaders.CONTENT_TYPE, "application/json");

        // customise httpclient to set timeout and compress
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000)
                .compress(true)
                .doOnConnected(conn ->
                                conn.addHandlerLast(new ReadTimeoutHandler(60, TimeUnit.SECONDS))
                );

        // Use WebClient for SSE
        return WebClient.builder()
                .defaultHeaders(headers -> headers.addAll(defaultHeaders)) // Set default headers
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024)) // increase buffer size to 10MB
                .build();
    }
}
