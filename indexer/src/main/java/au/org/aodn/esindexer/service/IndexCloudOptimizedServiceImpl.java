package au.org.aodn.esindexer.service;

import au.org.aodn.cloudoptimized.enums.GeoJsonProperty;
import au.org.aodn.cloudoptimized.model.DatasetProvider;
import au.org.aodn.cloudoptimized.model.MetadataEntity;
import au.org.aodn.cloudoptimized.model.TemporalExtent;
import au.org.aodn.cloudoptimized.model.geojson.FeatureCollectionGeoJson;
import au.org.aodn.cloudoptimized.service.DataAccessService;
import au.org.aodn.esindexer.configuration.AppConstants;
import au.org.aodn.metadata.geonetwork.exception.MetadataNotFoundException;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

@Slf4j
@Scope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class IndexCloudOptimizedServiceImpl extends IndexServiceImpl implements IndexCloudOptimizedService {

    protected DataAccessService dataAccessService;
    protected ObjectMapper indexerObjectMapper;
    protected String indexName;
    protected ElasticSearchIndexService elasticSearchIndexService;

    @Lazy
    @Autowired
    protected IndexCloudOptimizedServiceImpl self;

    public IndexCloudOptimizedServiceImpl(
            String indexName,
            ElasticsearchClient elasticsearchClient,
            ObjectMapper indexerObjectMapper,
            DataAccessService dataAccessService,
            ElasticSearchIndexService elasticSearchIndexService) {

        super(elasticsearchClient, indexerObjectMapper);

        this.indexName = indexName;
        this.indexerObjectMapper = indexerObjectMapper;
        this.dataAccessService = dataAccessService;
        this.elasticSearchIndexService = elasticSearchIndexService;
    }

    @Override
    public boolean hasIndex(String collectionId) {
        try {
            return elasticSearchIndexService.count(this.indexName, "properties.collection.keyword", collectionId) > 0;
        } catch (IOException | ElasticsearchException exception) {
            // ElasticsearchException when indexName do not exist, this happens in a partial config env
            // but we still need to make sure indexing works as is, backward compatible
            log.warn("Missing index for collectionId {} on index {}", collectionId, this.indexName);
            return false;
        }
    }

    /**
     * We will remove this function in the upcoming change. We replace it with pmtitle if works.
     * @param beginWithUuid - The UUID to start indexing from, if null, we will start from scratch
     * @param callback - The callback to avoid timeout
     * @return - The list of bulk response
     */
    @Override
    public List<BulkResponse> indexAllCloudOptimizedData(String beginWithUuid, IndexService.Callback callback) {
        // It is unreliable to check data service status here because in a multiple process env,
        // even the one instance is UP the other instance can be down given round-robin fashion,
        // it is better to check the status in the real function call.
        List<BulkResponse> results = new ArrayList<>();
        Map<String, Map<String, MetadataEntity>> entities = dataAccessService.getAllMetadata();
        List<String> sorted = entities.keySet().stream()
                .sorted()
                .toList();

        if(beginWithUuid != null && !sorted.isEmpty()) {
            // The CO is going to migrate to PMTile, so it is just a hotfix to avoid
            // cleaning up all data before reindex all. Currently, the index is too slow and
            // because the index cleaned, the metadata indexing thinks some data is not CO available which is wrong
            if(!"".equalsIgnoreCase(beginWithUuid)) {
                // We ignore all UUID before this beginWithUuid
                int index = sorted.indexOf(beginWithUuid);

                // If target not found or at start, no removal needed, else remove all items before
                if (index > 0) {
                    sorted = new ArrayList<>(sorted.subList(index, sorted.size()));
                }
            }
        }
        else {
            // Do it from scratch so make sense to refresh the schema
            elasticSearchIndexService.recreateIndexFromMappingJSONFile(AppConstants.DATASET_INDEX_MAPPING_JSON_FILE, indexName, null);
        }

        callback.onProgress(String.format("UUIDs to be process : %s", sorted));
        for (String uuid : sorted) {
            Map<String, MetadataEntity> entry = entities.get(uuid);

            for(String key: entry.keySet()) {
                callback.onProgress(String.format("Start indexing dataset with UUID: %s, dataset: %s", uuid, key));
                try {
                    List<TemporalExtent> temporalExtents = dataAccessService.getTemporalExtentOf(uuid, key);
                    if (!temporalExtents.isEmpty()) {
                        // Only first block works from data service api
                        LocalDate startDate = temporalExtents.get(0).getLocalStartDate();
                        LocalDate endDate = temporalExtents.get(0).getLocalEndDate();

                        callback.onProgress(String.format("Indexing dataset with UUID: %s %s from %s to %s", uuid, key, startDate, endDate));
                        try {
                            results.addAll(indexCloudOptimizedData(entry.get(key), startDate, endDate, callback));
                        } catch (IOException ioe) {
                            // Do nothing
                        }
                    }
                    callback.onProgress(String.format("Finish indexing dataset with UUID: %s, dataset: %s", uuid, key));
                }
                catch(MetadataNotFoundException enf) {
                    callback.onProgress(String.format("Metadata not found, skip! %s", enf.getMessage()));
                }
                catch(Exception e) {
                    callback.onError(new RuntimeException(String.format("Error indexing dataset with UUID: %s %s", uuid, key), e));
                }
            }
        }
        return results;
    }

    /**
     * Index the cloud optimized data
     *
     * @param metadata  - The metadata that describe the data
     * @param startDate - The start range to index
     * @param endDate   - THe end range to index
     * @return - The index result
     */
    @Override
    public List<BulkResponse> indexCloudOptimizedData(MetadataEntity metadata,
                                                      LocalDate startDate,
                                                      LocalDate endDate,
                                                      IndexService.Callback callback) throws IOException {

        List<BulkResponse> responses = new ArrayList<>();

        callback.onProgress("Indexing cloud optimized data for dataset: " + metadata.getUuid() + " " + metadata.getDname());
        callback.onProgress("Temporal extent: " + startDate + " - " + endDate);

        Iterable<FeatureCollectionGeoJson> datasetIterator = new DatasetProvider(
                metadata.getUuid(),
                metadata.getDname(),
                startDate,
                endDate,
                dataAccessService,
                dataAccessService.getFields(metadata)
        ).getIterator();

        BulkRequestProcessor<FeatureCollectionGeoJson> bulkRequestProcessor = new BulkRequestProcessor<>(
                indexName, (item) -> Optional.empty(), self, callback
        );

        try {
            for (FeatureCollectionGeoJson featureCollection : datasetIterator) {
                if (featureCollection == null) {
                    continue;
                }
                var featureCollections = avoidTooManyNestedObjects(featureCollection);
                if (!featureCollections.isEmpty()) {
                    for (int i = 0; i < featureCollections.size(); i++) {
                        // No need to process if there is no features
                        if(!featureCollections.get(i).getFeatures().isEmpty()) {
                            String id = featureCollections.get(i).getProperties().get(GeoJsonProperty.COLLECTION.getValue()).toString();
                            String key = featureCollections.get(i).getProperties().get(GeoJsonProperty.KEY.getValue()).toString();
                            String date = featureCollections.get(i).getProperties().get(GeoJsonProperty.DATE.getValue()).toString();

                            bulkRequestProcessor.processItem(
                                    String.format("%s|%s|%s|%d", id, key, date, i),
                                    featureCollections.get(i),
                                    true
                            ).ifPresent(responses::add);
                            callback.onProgress(
                                    String.format(
                                            "Processed %s  %s in year month: %s, group %d" ,
                                            id,
                                            key,
                                            featureCollection.getProperties().get(GeoJsonProperty.DATE.getValue()),
                                            i
                                    )
                            );
                        }
                    }
                }
            }

            bulkRequestProcessor
                    .flush()
                    .ifPresent(responses::add);

            log.info("Finished execute bulk indexing records to index: {}", indexName);
            callback.onProgress(responses);
        } catch (Exception e) {
            log.error("Exception thrown or not found while indexing cloud optimized data : {}", metadata.getUuid(), e);
            throw e;
        }
        return responses;
    }

    private List<FeatureCollectionGeoJson> avoidTooManyNestedObjects(FeatureCollectionGeoJson featureCollection) {

        final int MAX_NESTED_OBJECTS = 9000;
        List<FeatureCollectionGeoJson> featureCollections = new ArrayList<>();
        if (featureCollection.getFeatures().size() > MAX_NESTED_OBJECTS) {
            // split the feature collection into smaller ones so that all smaller ones have less than MAX_NESTED_OBJECTS features. e.g.: first featurecollection is from 0 to 8999, second is from 9000 to 17999, etc.
            log.info("Splitting feature collection with {} features into smaller ones", featureCollection.getFeatures().size());
            int i = 0;
            while (i < featureCollection.getFeatures().size()) {
                FeatureCollectionGeoJson featureCollectionPart = new FeatureCollectionGeoJson();
                featureCollectionPart.setFeatures(featureCollection.getFeatures().subList(i, Math.min(i + MAX_NESTED_OBJECTS, featureCollection.getFeatures().size())));
                featureCollectionPart.setProperties(featureCollection.getProperties());
                featureCollections.add(featureCollectionPart);
                i += MAX_NESTED_OBJECTS;
            }
        } else {
            log.debug("Feature collection has {} features", featureCollection.getFeatures().size());
            featureCollections.add(featureCollection);
        }
        return featureCollections;
    }
}
