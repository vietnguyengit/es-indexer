package au.org.aodn.esindexer;

import au.org.aodn.esindexer.configuration.GeoNetworkSearchTestConfig;
import au.org.aodn.esindexer.service.VocabServiceImpl;
import au.org.aodn.metadata.geonetwork.utils.CommonUtils;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.util.ResourceUtils;
import org.springframework.web.client.RestClientException;
import org.testcontainers.containers.ComposeContainer;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;

public class BaseTestClass {

    // alternative function for @Retryable annotation when the class is not a spring bean
    public static void persevere(BooleanSupplier action) {
        persevere(10, 2, action);
    }

    public static void persevere(int maxRetries, int delaySecond, BooleanSupplier action) {

        for (int i = 0; i < maxRetries; i++) {
            var isSuccessful = action.getAsBoolean();
            if (isSuccessful) {
                return;
            }
            try {
                Thread.sleep(delaySecond * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    protected final Logger logger = LogManager.getLogger(BaseTestClass.class);

    protected String xsrfToken = null;

    @LocalServerPort
    private int port;

    @Autowired
    protected TestRestTemplate testRestTemplate;

    @Autowired
    @Qualifier("portalElasticTransport")
    protected RestClientTransport transport;

    @Autowired
    @Qualifier("portalElasticsearchClient")
    protected ElasticsearchClient client;

    @Autowired
    protected ComposeContainer dockerComposeContainer;

    @Autowired
    protected VocabServiceImpl vocabService;

    protected void clearElasticIndex(String indexName) throws IOException {
        logger.debug("Clear elastic index");
        try {
            client.deleteByQuery(f -> f
                    .index(indexName)
                    .query(QueryBuilders.matchAll().build()._toQuery())
            );
            // Must all, otherwise index is not rebuild immediately
            client.indices().refresh();
        }
        catch(ElasticsearchException e) {
            // It is ok to ignore exception if the index is not found
        }
    }

    @PostConstruct
    public void init() throws IOException {
        vocabService.populateVocabsData();
    }

    protected HttpEntity<String> getRequestEntity(String body) {
        return getRequestEntity(Optional.empty(), null, body);
    }

    protected HttpEntity<String> getRequestEntity(Optional<Map<String, String>> oh, MediaType contentType, String body) {
        HttpHeaders headers = new HttpHeaders();

        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL, MediaType.TEXT_PLAIN));
        headers.setContentType(contentType == null ? CommonUtils.MEDIA_UTF8_XML : contentType);
        headers.setCacheControl(CacheControl.empty());

        headers.add(HttpHeaders.USER_AGENT, "TestRestTemplate");
        headers.add(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate, br");

        if (xsrfToken != null) {
            // This is very important and is needed to login geonetwork4, the logic is first you need to
            // do a REST call, it will come back with the XSRF-TOKEN, and subsequence call require
            // the following to be set in order to authenticate correctly
            headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + xsrfToken);
            headers.add("X-XSRF-TOKEN", xsrfToken);
        }

        // This is use for test container only, so it is ok to hardcode
        headers.setBasicAuth("admin", "admin");

        oh.ifPresent(stringStringMap -> stringStringMap
                .forEach(headers::add));

        return body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
    }

    protected String getLoginUrl() {
        return String.format("http://%s:%s/geonetwork/srv/eng/info?type=me",
                dockerComposeContainer.getServiceHost(GeoNetworkSearchTestConfig.GN_NAME, GeoNetworkSearchTestConfig.GN_PORT),
                dockerComposeContainer.getServicePort(GeoNetworkSearchTestConfig.GN_NAME, GeoNetworkSearchTestConfig.GN_PORT));

    }

    protected String getIndexUrl(boolean reset) {
        return String.format("http://%s:%s/geonetwork/srv/api/site/index?reset=%s&asynchronous=false",
                dockerComposeContainer.getServiceHost(GeoNetworkSearchTestConfig.GN_NAME, GeoNetworkSearchTestConfig.GN_PORT),
                dockerComposeContainer.getServicePort(GeoNetworkSearchTestConfig.GN_NAME, GeoNetworkSearchTestConfig.GN_PORT),
                reset ? "true" : "false");
    }

    protected String isIndexUrl() {
        return String.format("http://%s:%s/geonetwork/srv/api/site/indexing",
                dockerComposeContainer.getServiceHost(GeoNetworkSearchTestConfig.GN_NAME, GeoNetworkSearchTestConfig.GN_PORT),
                dockerComposeContainer.getServicePort(GeoNetworkSearchTestConfig.GN_NAME, GeoNetworkSearchTestConfig.GN_PORT));

    }

    protected String getPublishUrl(String uuid) {
        return String.format("http://%s:%s/geonetwork/srv/api/records/" + uuid + "/publish",
                dockerComposeContainer.getServiceHost(GeoNetworkSearchTestConfig.GN_NAME, GeoNetworkSearchTestConfig.GN_PORT),
                dockerComposeContainer.getServicePort(GeoNetworkSearchTestConfig.GN_NAME, GeoNetworkSearchTestConfig.GN_PORT));

    }

    protected String getRecordUrl(String uuid) {
        return String.format("http://%s:%s/geonetwork/srv/api/records/" + uuid,
                dockerComposeContainer.getServiceHost(GeoNetworkSearchTestConfig.GN_NAME, GeoNetworkSearchTestConfig.GN_PORT),
                dockerComposeContainer.getServicePort(GeoNetworkSearchTestConfig.GN_NAME, GeoNetworkSearchTestConfig.GN_PORT));

    }

    protected String getGeoNetworkRecordsInsertUrl() {
        String host = String.format("http://%s:%s/geonetwork/srv/api/records?metadataType=METADATA&transformWith=_none_&group=2&uuidProcessing=OVERWRITE&category=",
                dockerComposeContainer.getServiceHost(GeoNetworkSearchTestConfig.GN_NAME, GeoNetworkSearchTestConfig.GN_PORT),
                dockerComposeContainer.getServicePort(GeoNetworkSearchTestConfig.GN_NAME, GeoNetworkSearchTestConfig.GN_PORT));

        logger.info("Geonetwork url on docker is {}", host);
        return host;
    }

    /**
     * Must call to get the XSRF token
     */
    @PostConstruct
    public void login() {
        if (xsrfToken == null) {
            HttpEntity<String> requestEntity = getRequestEntity(Optional.empty(), MediaType.APPLICATION_JSON, null);

            ResponseEntity<String> responseEntity = testRestTemplate
                    .exchange(
                            getLoginUrl(),
                            HttpMethod.POST,
                            requestEntity,
                            String.class
                    );

            if (responseEntity.getStatusCode() == HttpStatus.FORBIDDEN) {

                // This is the behavior of geonetwork that you need to setup the session id, once you make the request
                // with correct username password, it will return with a session id
                String set_cookie = responseEntity.getHeaders().getFirst(HttpHeaders.SET_COOKIE);

                // The string in set_cookie will be like XSRF-TOKEN=xxxxx ; Path=/geonetwork
                xsrfToken = set_cookie.split(";")[0].split("=")[1].trim();
                logger.info("XSRF token {}", xsrfToken);

                HttpEntity<String> re = getRequestEntity(Optional.empty(), MediaType.APPLICATION_JSON, null);

                ResponseEntity<String> answer = testRestTemplate
                        .exchange(
                                getLoginUrl(),
                                HttpMethod.POST,
                                re,
                                String.class
                        );

                assertEquals("Login and get XSRF token", HttpStatus.OK, answer.getStatusCode());

            }
        }
    }

    public void deleteRecord(String... uuids) {
        HttpEntity<String> requestEntity = getRequestEntity(null);

        // retry the request if the server is not ready yet (sometimes will return 403 and can be resolved by retrying )
        persevere(() -> triggerIndexer(requestEntity));

        for (String uuid : uuids) {

            // retry the request if the server is not ready yet (sometimes will return BAD_REQUEST because
            // of the concurrency issue in elastic search, and can be resolved by retrying )
            persevere(() -> delete(uuid, requestEntity));
        }

        // Trigger GeoNetwork reindex with reset=true to ensure its ES index is refreshed after deletions
        // This prevents stale entries from appearing in subsequent test runs
        persevere(() -> triggerIndexer(requestEntity, true));
    }

    protected boolean triggerIndexer(HttpEntity<String> requestEntity) {
        return triggerIndexer(requestEntity, false);
    }

    protected boolean triggerIndexer(HttpEntity<String> requestEntity, boolean reset) {

        // Index the item so that query yield the right result before delete
        ResponseEntity<Void> trigger = testRestTemplate
                .exchange(
                        getIndexUrl(reset),
                        HttpMethod.PUT,
                        requestEntity,
                        Void.class
                );
        if (trigger.getStatusCode().is2xxSuccessful()) {
            logger.info("Triggered indexer successfully");
            return true;
        }
        logger.warn("Serverr not ready yet. Will retry. Status code: {}", trigger.getStatusCode());
        return false;
    }

    private boolean delete(String uuid, HttpEntity<String> requestEntity) {
        logger.info("Deleting GN doc {}", uuid);
        try {
            // Delete by query basically does a search for the objects to delete and
            // then deletes them with version conflict checking. Without a _refresh
            // in between, the search done by _delete_by_query might return the
            // old version of the document, leading to a version conflict when
            // the delete is attempted.

            ResponseEntity<String> response = testRestTemplate
                    .exchange(
                            getRecordUrl(uuid),
                            HttpMethod.DELETE,
                            requestEntity,
                            String.class
                    );
            if (response.getStatusCode().is2xxSuccessful() ||
                    response.getStatusCode() == HttpStatus.NOT_FOUND) {
                logger.info("Deleted GN doc {}", uuid);
                return true;
            }
            logger.warn("Failed to delete. Will retry. Message: {}", response.getBody());
            return false;
        }
        catch(Exception e) {
            return false;
        }
    }

    public static String readResourceFile(String path) throws IOException {
        File f = ResourceUtils.getFile(path);
        return Files.readString(f.toPath(), StandardCharsets.UTF_8);
    }

    public String insertMetadataRecords(String uuid, String path) throws RestClientException, IOException {
        String content = readResourceFile(path);

        HttpEntity<String> requestEntity = getRequestEntity(
                Optional.empty(),
                null,
                content
        );

        ResponseEntity<Map> r = testRestTemplate.exchange(
                getGeoNetworkRecordsInsertUrl(),
                HttpMethod.PUT,
                requestEntity,
                Map.class
        );

        assertEquals("Insert record OK", HttpStatus.CREATED, r.getStatusCode());

        // Index the item so that query yield the right result
        Map<String, Object> param = new HashMap<>();
        param.put("uuid", uuid);

        ResponseEntity<String> responseEntity = testRestTemplate
                .exchange(
                        getPublishUrl(uuid),
                        HttpMethod.PUT,
                        getRequestEntity(Optional.empty(), null, null),
                        String.class,
                        param
                );
        assertEquals("Published OK", HttpStatus.NO_CONTENT, responseEntity.getStatusCode());

        // Index the item so that query yield the right result
        persevere(() -> triggerIndexer(getRequestEntity(null)));

        return content;
    }
}
