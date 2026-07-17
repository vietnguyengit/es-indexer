package au.org.aodn.cloudoptimized.service;

import au.org.aodn.cloudoptimized.enums.GeoJsonProperty;
import au.org.aodn.cloudoptimized.model.*;
import au.org.aodn.cloudoptimized.model.geojson.FeatureCollectionGeoJson;
import au.org.aodn.cloudoptimized.model.geojson.FeatureGeoJson;
import au.org.aodn.cloudoptimized.model.geojson.PointGeoJson;
import au.org.aodn.metadata.geonetwork.exception.MetadataNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.annotation.Backoff;

import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;

@Slf4j
public class DataAccessServiceImpl implements DataAccessService {

    protected final String accessEndPoint;
    protected final RestTemplate restTemplate;
    protected final WebClient webClient;
    protected final ObjectMapper objectMapper;
    protected ExecutorService executorService;

    private final static int MAX_RETRY_ATTEMPT = 100;  //times
    private final static int RETRY_DELAY_SECOND = 10; // second

    public DataAccessServiceImpl(String serverUrl, String baseUrl, RestTemplate restTemplate, WebClient webClient, ObjectMapper objectMapper) {
        this.accessEndPoint = serverUrl + baseUrl;
        this.restTemplate = restTemplate;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        int numThreads = Runtime.getRuntime().availableProcessors();
        this.executorService = Executors.newFixedThreadPool(numThreads);
    }

    protected String getDataAccessEndpoint() {
        return this.accessEndPoint;
    }

    protected HttpEntity<String> getRequestEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }

    protected FeatureCollectionGeoJson toFeatureCollection(String uuid, String key, Map<? extends CloudOptimizedEntry, Long> data) {

        // Because the data provider provides data by month, so assume all the dates
        // in data here are all the same. So just get the first date to set
        String dateToSet = null;
        var features = new ArrayList<FeatureGeoJson>();
        for (var entry: data.entrySet()) {
            var feature = new FeatureGeoJson(new PointGeoJson(entry.getKey().getLongitude(), entry.getKey().getLatitude()));
            feature.addProperty(GeoJsonProperty.COUNT.getValue(), entry.getValue());
            feature.addProperty(GeoJsonProperty.DATE.getValue(), entry.getKey().getTime().toString());
            features.add(feature);

            if (dateToSet == null) {
                dateToSet = entry.getKey().getTime().toString();
            } else {
                // if the date is not the same, there must be something wrong in dataprovider. throw exception
                if (!dateToSet.equals(entry.getKey().getTime().toString())) {
                    throw new IllegalArgumentException("All the dates in the data must be the same");
                }
            }
        }

        FeatureCollectionGeoJson featureCollection = new FeatureCollectionGeoJson();
        featureCollection.setFeatures(features);
        featureCollection.addProperty(GeoJsonProperty.COLLECTION.getValue(), uuid);
        featureCollection.addProperty(GeoJsonProperty.KEY.getValue(), key);
        featureCollection.addProperty(GeoJsonProperty.DATE.getValue(), dateToSet);

        return featureCollection;
    }

    protected boolean isSafeId(String id) {
        return id.matches("^[a-zA-Z0-9-_]+$");
    }

    @Override
    @Retryable(
            retryFor = { HttpServerErrorException.ServiceUnavailable.class, ResourceAccessException.class },
            maxAttempts = MAX_RETRY_ATTEMPT,
            backoff = @Backoff(delay = RETRY_DELAY_SECOND * 1000)
    )
    public Map<String, MetadataEntity> getMetadataByUuid(String uuid) {

        // Validate path argument
        if(isSafeId(uuid)) {
            // Sometimes the server is down due to SPOT instance or software update.
            waitTillServiceUp();

            HttpEntity<String> request = getRequestEntity();

            String url = UriComponentsBuilder
                    .fromUriString(getDataAccessEndpoint() + "/metadata/{uuid}")
                    .buildAndExpand(uuid)
                    .toUriString();

            ResponseEntity<Map<String, MetadataEntity>> responseEntity = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    request,
                    new ParameterizedTypeReference<>() {
                    },
                    Map.of()
            );
            if (responseEntity.getStatusCode().is2xxSuccessful()) {
                return responseEntity.getBody();
            } else {
                return null;
            }
        }
        else {
            log.warn("Id not in correct format {}", uuid);
            return null;
        }
    }

    @Override
    @Retryable(
            retryFor = { HttpServerErrorException.ServiceUnavailable.class, ResourceAccessException.class },
            maxAttempts = MAX_RETRY_ATTEMPT,
            backoff = @Backoff(delay = RETRY_DELAY_SECOND * 1000)
    )
    public Map<String, Map<String, MetadataEntity>> getAllMetadata() {

        try {
            HttpEntity<String> request = getRequestEntity();
            String url = UriComponentsBuilder
                    .fromUriString(getDataAccessEndpoint() + "/metadata")
                    .toUriString();

            ResponseEntity<Map<String, Map<String, MetadataEntity>>> responseEntity = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    request,
                    new ParameterizedTypeReference<>() {
                    },
                    Map.of()
            );
            if (responseEntity.getStatusCode().is2xxSuccessful()) {
                return responseEntity.getBody();
            } else {
                return Map.of();
            }
        } catch (HttpServerErrorException.ServiceUnavailable ex) {
                // Caught when FastAPI returns 503 (api_status is False)
                String errorJson = ex.getResponseBodyAsString();
                // errorJson contains: {"detail":"API is not ready. Metadata initialization is still in progress."}
                log.error("FastAPI is not ready yet: {}", errorJson);
                throw ex;
            }
        }

    @Override
    public HealthStatus getHealthStatus() {
        HttpEntity<String> request = getRequestEntity();
        String url = UriComponentsBuilder
                .fromUriString(getDataAccessEndpoint() + "/health")
                .toUriString();

        ResponseEntity<Map<String, String>> responseEntity = restTemplate.exchange(
                url,
                HttpMethod.GET,
                request,
                new ParameterizedTypeReference<>() {
                },
                Map.of()
        );

        return (responseEntity.getStatusCode().is2xxSuccessful() && responseEntity.getBody() != null) ?
            HealthStatus.fromValue(responseEntity.getBody().get("status")) :
            HealthStatus.UNKNOWN;
    }

    @Override
    public Optional<String> getNotebookLink(String uuid) {
        try {
            HttpEntity<String> request = getRequestEntity();

            String url = UriComponentsBuilder
                    .fromUriString(getDataAccessEndpoint() + "/data/{uuid}/notebook_url")
                    .buildAndExpand(uuid)
                    .toUriString();

            ResponseEntity<String> responseEntity = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    request,
                    new ParameterizedTypeReference<>() {
                    },
                    Map.of()
            );

            if (responseEntity.getStatusCode().is2xxSuccessful()) {
                if (responseEntity.getBody() != null && !responseEntity.getBody().isEmpty()) {
                    // The response is a JSON string (e.g., "https://..."), so we need to deserialize it
                    // to remove the enclosing quotes and handle any escape characters properly
                    String notebookUrl = objectMapper.readValue(responseEntity.getBody(), String.class);
                    return Optional.of(notebookUrl);
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * This function is a temp solution for very heavy requests of Parquet May be
     * removed in the future.
     */
    private List<? extends CloudOptimizedEntry> getIndexingDatasetByDays(final String uuid, String key, final LocalDate startDate, final LocalDate endDate, final List<MetadataFields> fields) {
        log.debug("Fetching for UUID: {} in {} -> {}", uuid, startDate, endDate);
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("uuid", uuid);
            LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);

            String url = UriComponentsBuilder.fromUriString(getDataAccessEndpoint() + "/data/{uuid}/{key}")
                    .queryParam("f", "sse/json")
                    .queryParam("start_date", startDate)
                    .queryParam("end_date", endDateTime)
                    .queryParam("columns", fields)
                    .buildAndExpand(uuid, key)
                    .toUriString();

            final List<CloudOptimizedEntryReducePrecision> allEventData = new ArrayList<>();
            CountDownLatch countDownLatch = new CountDownLatch(1);
            var dateRange = startDate + " to " + endDate;

            // Use defer to allow retry with the same argument, with f=sse/json argument above, we signal
            // server to return result with server side event.
            Flux.defer(
                            () -> webClient.get()
                                    .uri(url, params)
                                    .accept(MediaType.TEXT_EVENT_STREAM)
                                    .retrieve()
                                    .bodyToFlux(String.class)
                    )
                    .filter(data -> data.contains("data")) // Filter for data: lines
                    .map(data -> {
                        try {
                            // Deserialize raw event into SSEEvent
                            return objectMapper.readValue(data, new TypeReference<SSEEvent<List<CloudOptimizedEntryReducePrecision>>>() {});
                        } catch (JsonProcessingException e) {
                            log.error("Failed to parse SSE event: {}, Error: {}", data.substring(0, Math.min(100, data.length())), e.getMessage());
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    // For debug purpose, track SSE request
                    .doOnNext(event -> log.debug("SSE event received for {}, status={}, message={}, request_id={}", dateRange, event.getStatus(), event.getMessage(), event.getRequestId()))
                    // Ignore other status, just process the complete event, the other event is used to keep the connection alive
                    .filter(event -> "completed".equals(event.getStatus()))
                    // Give a random number so that not all retry happens the same time when previous failed
                    .retryWhen(Retry.fixedDelay(MAX_RETRY_ATTEMPT, Duration.ofSeconds(RETRY_DELAY_SECOND))
                            .doBeforeRetry(signal -> log.info("Retrying {} due to: {}", dateRange, signal.failure().getMessage())))
                    .subscribe(
                            event -> {
                                String message = event.getMessage() != null ? event.getMessage() : "null";
                                log.debug("Process event message {} : {}", dateRange, message);

                                if (event.getData() != null) {
                                    // Merge data as event comes, this reduced the memory need to hold the string
                                    allEventData.addAll(event.getData());
                                } else {
                                    log.warn("Received completed event with null data for date range: {}", dateRange);
                                }

                                // Check if this is the end
                                String[] sm = message.trim().split("/");
                                if (sm.length == 2 && sm[1].equalsIgnoreCase("end")) {
                                    log.debug("Completion condition met for {}: {}, releasing latch", dateRange, message);
                                    countDownLatch.countDown();
                                }
                            },
                            error -> {
                                log.error("Fatal error in SSE stream for dateRange {}: {}", dateRange, error.getMessage(), error);
                                countDownLatch.countDown(); // Release latch on fatal error
                            },
                            () -> {
                                log.debug("SSE stream completed for dateRange: {}", dateRange);
                                countDownLatch.countDown();
                            }
                    );

            countDownLatch.await();

            log.debug("Aggregate data for {}", dateRange);
            if (!allEventData.isEmpty()) {
                return allEventData;
            } else {
                log.info("No data found from DataAccess Service for UUID: {} in {} -> {}", uuid, startDate, endDate);
            }

        } catch (Exception e) {
            // Do nothing just return empty list
            log.info("Unable to find cloud optimized data with UUID: {} in S3 for {} -> {}", uuid, startDate, endDate, e);
            log.warn("error message is: {}", e.getMessage());
        }
        return null;
    }

    @Override
    @Retryable(
            retryFor = { HttpServerErrorException.ServiceUnavailable.class, ResourceAccessException.class },
            maxAttempts = MAX_RETRY_ATTEMPT,
            backoff = @Backoff(delay = RETRY_DELAY_SECOND * 1000)
    )
    public FeatureCollectionGeoJson getIndexingDatasetByMonth(final String uuid, String key, final YearMonth yearMonth, final List<MetadataFields> fields) {

        var startDate = yearMonth.atDay(1);
        var endDate = yearMonth.atEndOfMonth();

        // currently, we force to get data in the same year to simplify the logic
        if (startDate.getYear() != endDate.getYear()) {
            throw new IllegalArgumentException("Start date and end date must be in the same year");
        }

        List<CloudOptimizedEntry> eventDataList = new ArrayList<>();
        final int dateRangeLength = 11; // days
        var currentStartDate = startDate;

        // Use managed ExecutorService for parallel execution (cleaned up via @PreDestroy)
        List<java.util.concurrent.Future<List<? extends CloudOptimizedEntry>>> futures = new ArrayList<>();

        while (!currentStartDate.isAfter(endDate)) {
            var currentEndDate = currentStartDate.plusDays(dateRangeLength - 1);
            if (currentEndDate.isAfter(endDate)) {
                currentEndDate = endDate;
            }
            final var start = currentStartDate;
            final var end = currentEndDate;
            futures.add(executorService.submit(() -> getIndexingDatasetByDays(uuid, key, start, end, fields)));
            currentStartDate = currentEndDate.plusDays(1);
        }

        for (java.util.concurrent.Future<List<? extends CloudOptimizedEntry>> future : futures) {
            try {
                var eventData = future.get();
                if (eventData != null) {
                    eventDataList.addAll(eventData);
                }
            } catch (Exception e) {
                throw new RuntimeException("Exception in parallel data fetching: " + e.getMessage(), e);
            }
        }
        // Note: do not shutdown here; executorService is long-lived and cleaned in @PreDestroy
        final Map<CloudOptimizedEntry, Long> allEntries = new HashMap<>();
        aggregateData(allEntries, eventDataList);
        return toFeatureCollection(uuid, key, allEntries);
    }

    public static void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.error("Executor did not terminate");
                }
            }
        } catch (InterruptedException ie) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    @Retryable(
        retryFor = { HttpServerErrorException.ServiceUnavailable.class, ResourceAccessException.class },
        maxAttempts = MAX_RETRY_ATTEMPT,
        backoff = @Backoff(delay = RETRY_DELAY_SECOND * 1000)
    )
    public List<TemporalExtent> getTemporalExtentOf(String uuid, String key) {
        if(isSafeId(uuid)) {
            log.info("Fetching temporal extent of UUID: {}, {}", uuid, key);
            try {
                HttpEntity<String> request = getRequestEntity();

                String url = UriComponentsBuilder.fromUriString(getDataAccessEndpoint() + "/data/{uuid}/{key}/temporal_extent")
                        .buildAndExpand(uuid, key)
                        .toUriString();

                ResponseEntity<List<TemporalExtent>> responseEntity = restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        request,
                        new ParameterizedTypeReference<>() {
                        },
                        Map.of()
                );

                return responseEntity.getBody();

            }
            catch (HttpClientErrorException.NotFound e) {
                throw new MetadataNotFoundException("temporal_extent of uuid not found: " + uuid + " in DataAccess Service");
            }
            catch (HttpServerErrorException.ServiceUnavailable sna) {
                log.error("Waiting data access service UP");
                throw sna;
            }
            catch (Exception e) {
                throw new RuntimeException("Exception thrown while retrieving dataset with UUID: " + uuid + " " + e.getMessage(), e);
            }
        }
        else {
            throw new MetadataNotFoundException("Malform UUID in request: " + uuid);
        }
    }
    /**
     * Summarize the data by counting the number if all the concerned fields are the same, merge data with
     * existing map. That is count will be added for same CloudOptimizedEntry
     *
     * @param merge - You want to merge the result to an existing map
     * @param data the data to summarize
     */
    @Override
    public void aggregateData(Map<CloudOptimizedEntry, Long> merge, List<? extends CloudOptimizedEntry> data) {
        Map<CloudOptimizedEntry, Long> currentAggregation =  data
                .stream()
                // We cannot create a valid geo_shape point if one coordinate is null
                .filter(d -> d.getLatitude() != null && d.getLongitude() != null)
                .collect(Collectors.groupingBy(
                        d -> d,
                        Collectors.counting()
                ));

        currentAggregation.forEach((entry, count) ->
                merge.merge(entry, count, Long::sum)
        );
    }

    @Override
    public void waitTillServiceUp() {
        try {
            while (this.getHealthStatus() != HealthStatus.UP) {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
            }
        }
        catch (Exception ignored) {}
    }

    @Override
    @Retryable(
        retryFor = { Exception.class },
        maxAttempts = MAX_RETRY_ATTEMPT,
        backoff = @Backoff(delay = RETRY_DELAY_SECOND * 1000)
    )
    public FeatureCollectionGeoJson getZarrIndexingDataByMonth(String uuid, String key, YearMonth yearMonth) {
        var startDate = Instant.parse(yearMonth.atDay(1) + "T00:00:00.000000000Z");
        var endDate = Instant.parse(yearMonth.atEndOfMonth() + "T23:59:59.999999999Z");
        var url = UriComponentsBuilder.fromUriString(getDataAccessEndpoint() + "/data/{uuid}/{key}/zarr_rect")
                .queryParam("start_date", startDate)
                .queryParam("end_date", endDate)
                .buildAndExpand(uuid, key)
                .toUriString();

        var request = getRequestEntity();

        ResponseEntity<FeatureCollectionGeoJson> responseEntity = restTemplate.exchange(
                url,
                HttpMethod.GET,
                request,
                new ParameterizedTypeReference<>() {},
                Map.of()
        );

        if (!responseEntity.getStatusCode().is2xxSuccessful()) {
            log.error("Request to DataAccess Service failed with status code: {} for UUID: {} in {} -> {}", responseEntity.getStatusCode(), uuid, startDate, endDate);
            return null;
        }
        if (responseEntity.getBody() == null) {
            log.warn("No data found from DataAccess Service for UUID: {} in {} -> {}", uuid, startDate, endDate);
            return null;
        }
        return responseEntity.getBody();
    }

    @PreDestroy
    public void cleanup() {
        if (executorService != null) {
            shutdownExecutor(executorService);
        }
    }

}
