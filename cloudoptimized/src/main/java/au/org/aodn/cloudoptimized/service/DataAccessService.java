package au.org.aodn.cloudoptimized.service;

import au.org.aodn.cloudoptimized.model.CloudOptimizedEntry;
import au.org.aodn.cloudoptimized.model.MetadataEntity;
import au.org.aodn.cloudoptimized.model.MetadataFields;
import au.org.aodn.cloudoptimized.model.TemporalExtent;
import au.org.aodn.cloudoptimized.model.geojson.FeatureCollectionGeoJson;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface DataAccessService {

    enum HealthStatus {
        UP("UP"),
        UNKNOWN("UNKNOWN");

        private final String status;

        HealthStatus(String status) {
            this.status = status;
        }

        public static HealthStatus fromValue(String status) {
            for (HealthStatus s : HealthStatus.values()) {
                if(s.status.equalsIgnoreCase(status)) {
                    return s;
                }
            }
            return HealthStatus.UNKNOWN;
        }
    }

    default List<MetadataFields> getFields(MetadataEntity entity) {
        return entity.getDepth() != null ?
                List.of(MetadataFields.TIME, MetadataFields.DEPTH, MetadataFields.LONGITUDE, MetadataFields.LATITUDE) :
                List.of(MetadataFields.TIME, MetadataFields.LONGITUDE, MetadataFields.LATITUDE);
    }

    void aggregateData(Map<CloudOptimizedEntry, Long> merge, List<? extends CloudOptimizedEntry> data);
    FeatureCollectionGeoJson getIndexingDatasetByMonth(String uuid, String key, YearMonth yearMonth, List<MetadataFields> fields);
    /**
     * Get spatial extents value from the cloud optimize data
     * @param uuid - UUID of dataset
     * @param key - UUID can map to multiple dataset, you need a key to find your target
     * @return - The TemporalExtent value
     */
    List<TemporalExtent> getTemporalExtentOf(String uuid, String key);
    Optional<String> getNotebookLink(String uuid);
    Map<String, MetadataEntity> getMetadataByUuid(String uuid);
    Map<String, Map<String, MetadataEntity>> getAllMetadata();
    HealthStatus getHealthStatus();
    void waitTillServiceUp();

    /**
     * Get Zarr indexing data by month. It is a short term solution for Zarr indexing. May change later
     */
    FeatureCollectionGeoJson getZarrIndexingDataByMonth(String uuid, String key, YearMonth yearMonth);
}
