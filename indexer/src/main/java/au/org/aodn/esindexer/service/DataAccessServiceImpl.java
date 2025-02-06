package au.org.aodn.esindexer.service;

import au.org.aodn.cloudoptimized.model.*;
import au.org.aodn.cloudoptimized.service.DataAccessService;
import au.org.aodn.esindexer.exception.MetadataNotFoundException;
import au.org.aodn.esindexer.utils.GeometryUtils;
import au.org.aodn.stac.model.StacItemModel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Getter
public class DataAccessServiceImpl implements DataAccessService {

    protected String accessEndPoint;
    protected RestTemplate restTemplate;

    public DataAccessServiceImpl(String serverUrl, String baseUrl, RestTemplate restTemplate) {
       this.accessEndPoint = serverUrl + baseUrl;
       this.restTemplate = restTemplate;
    }

    @Override
    public MetadataEntity getMetadataByUuid(String uuid) {
        HttpEntity<String> request = getRequestEntity(List.of(MediaType.APPLICATION_JSON));

        String url = UriComponentsBuilder
                .fromUriString(getDataAccessEndpoint() + "/metadata/{uuid}")
                .buildAndExpand(uuid)
                .toUriString();

        ResponseEntity<MetadataEntity> responseEntity = restTemplate.exchange(
                url,
                HttpMethod.GET,
                request,
                new ParameterizedTypeReference<>() {},
                Map.of()
        );
        if (responseEntity.getStatusCode().is2xxSuccessful()) {
            return responseEntity.getBody();
        }
        else {
            return null;
        }
    }

    @Override
    public List<MetadataEntity> getAllMetadata() {
        HttpEntity<String> request = getRequestEntity(List.of(MediaType.APPLICATION_JSON));
        String url = UriComponentsBuilder
                .fromUriString(getDataAccessEndpoint() + "/metadata")
                .toUriString();

        ResponseEntity<List<MetadataEntity>> responseEntity = restTemplate.exchange(
                url,
                HttpMethod.GET,
                request,
                new ParameterizedTypeReference<>() {},
                Map.of()
        );
        if (responseEntity.getStatusCode().is2xxSuccessful()) {
            return responseEntity.getBody();
        }
        else {
            return List.of();
        }
    }

    @Override
    public Optional<String> getNotebookLink(String uuid) {
        try {
            HttpEntity<String> request = getRequestEntity(List.of(MediaType.APPLICATION_JSON));

            String url = UriComponentsBuilder
                    .fromUriString(getDataAccessEndpoint() + "/data/{uuid}/notebook_url")
                    .buildAndExpand(uuid)
                    .toUriString();

            ResponseEntity<String> responseEntity = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    request,
                    new ParameterizedTypeReference<>() {},
                    Map.of()
            );

            if (responseEntity.getStatusCode().is2xxSuccessful()) {
                if (responseEntity.getBody() != null || !responseEntity.getBody().isEmpty()) {
                    return Optional.of(responseEntity.getBody());
                }
            }
            return Optional.empty();
        }
        catch(Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public List<StacItemModel> getIndexingDatasetBy(String uuid, LocalDate startDate, LocalDate endDate, List<MetadataFields> fields) {

        // currently, we force to get data in the same year to simplify the logic
        if (startDate.getYear() != endDate.getYear()) {
            throw new IllegalArgumentException("Start date and end date must be in the same year");
        }

        try {
            HttpEntity<String> request = getRequestEntity(List.of(MediaType.APPLICATION_JSON));

            Map<String, Object> params = new HashMap<>();
            params.put("uuid", uuid);

            String url = UriComponentsBuilder.fromUriString(getDataAccessEndpoint() + "/data/{uuid}")
                    .queryParam("is_to_index", "true")
                    .queryParam("start_date", startDate)
                    .queryParam("end_date", endDate)
                    .queryParam("columns", fields)
                    .buildAndExpand(uuid)
                    .toUriString();

            ResponseEntity<List<CloudOptimizedEntryReducePrecision>> responseEntity = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    request,
                    new ParameterizedTypeReference<>() {},
                    params
            );

            if (responseEntity.getStatusCode().is2xxSuccessful()) {
                if (responseEntity.getBody() != null) {
                    log.info("Indexed cloud optimized data with UUID: {} in S3 for {} -> {}",uuid, startDate, endDate);
                    return toStacItemModel(uuid, aggregateData(responseEntity.getBody()));
                }
            }
        }
        catch (Exception e) {
            // Do nothing just return empty list
            log.info("Unable to find cloud optimized data with UUID: {} in S3 for {} -> {}",uuid, startDate, endDate, e);
        }
        return List.of();
    }

    @Override
    public List<TemporalExtent> getTemporalExtentOf(String uuid) {
        try {
            HttpEntity<String> request = getRequestEntity(List.of(MediaType.APPLICATION_JSON));

            Map<String, Object> params = new HashMap<>();
            params.put("uuid", uuid);

            String url = UriComponentsBuilder.fromUriString(getDataAccessEndpoint() + "/data/{uuid}/temporal_extent")
                    .buildAndExpand(uuid)
                    .toUriString();

            ResponseEntity<List<TemporalExtent>> responseEntity = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    request,
                    new ParameterizedTypeReference<>() {},
                    params
            );

            return responseEntity.getBody();

        } catch (HttpClientErrorException.NotFound e) {
            throw new MetadataNotFoundException("Unable to find dataset with UUID: " + uuid + " in GeoNetwork");
        } catch (Exception e) {
            throw new RuntimeException("Exception thrown while retrieving dataset with UUID: " + uuid + e.getMessage(), e);
        }
    }
    /**
     * Summarize the data by counting the number if all the concerned fields are the same
     * @param data the data to summarize
     * @return the summarized data
     */
    protected Map<? extends CloudOptimizedEntry, Long> aggregateData(List<? extends CloudOptimizedEntry> data) {
        return data.stream()
                .collect(Collectors.groupingBy(
                        d -> d,
                        Collectors.counting()
                ));
    }
    /**
     * Group and count the entries based on user object equals/hashcode
     * @param uuid - The parent uuid that associate with input
     * @param data - The aggregated data
     * @return - List of formatted stac item
     */
    protected List<StacItemModel> toStacItemModel(String uuid, Map<? extends CloudOptimizedEntry, Long> data) {
        return data.entrySet().stream()
                .filter(d -> d.getKey().getLongitude() != null && d.getKey().getLatitude() != null)
                .map(d -> {
                    StacItemModel.StacItemModelBuilder builder = StacItemModel.builder()
                            .collection(uuid) // collection point to the uuid of parent
                            .uuid(String
                                    .join("|",
                                            uuid,
                                            d.getKey().getTime().toString(),
                                            d.getKey().getLongitude().toString(),
                                            d.getKey().getLatitude().toString(),
                                            d.getKey().getDepth() != null ? d.getKey().getDepth().toString() : "*"
                                    )
                            )
                            // The elastic query cannot sort by geo_shape or geo_point, so need to flatten value in properties
                            // this geometry is use for filtering
                            .geometry(GeometryUtils.createGeoShapeJson(d.getKey().getLongitude(), d.getKey().getLatitude()));

                    Map<String, Object> props = new HashMap<>() {{
                            // Fields dup here is use for aggregation, you must have the geo_shape to do spatial search
                            put("lng", d.getKey().getLongitude().doubleValue());
                            put("lat", d.getKey().getLatitude().doubleValue());
                            put("count", d.getValue());
                            put("time", d.getKey().getZonedDateTime().format(DateTimeFormatter.ISO_ZONED_DATE_TIME));

                            // Some data do not have depth!
                            if(d.getKey().getDepth() != null) {
                                put("depth", d.getKey().getDepth().doubleValue());
                            }
                    }};

                    return builder.properties(props).build();

                })
                .toList();
    }

    protected String getDataAccessEndpoint() {
        return this.accessEndPoint;
    }

    // parameters are not in use for now. May be useful in the future so just keep it
    protected HttpEntity<String> getRequestEntity(List<MediaType> accept) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(accept);
        return new HttpEntity<>(headers);
    }
}
