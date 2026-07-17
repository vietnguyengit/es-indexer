package au.org.aodn.esindexer.batch;

import au.org.aodn.esindexer.service.IndexerMetadataService;
import au.org.aodn.esindexer.service.IndexCloudOptimizedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

class BatchJobRunnerTest {
    @Mock
    private IndexerMetadataService indexerMetadataService;
    @Mock
    private IndexCloudOptimizedService indexCloudOptimizedService;
    @InjectMocks
    private BatchJobRunner batchJobRunner;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void runIndexAllMetadataWithParamShouldThrow() {
        Exception ex = assertThrows(IllegalArgumentException.class, () -> batchJobRunner.run("indexAllMetadata", "param"));
        assertTrue(ex.getMessage().contains("Job parameter not required"));
    }

    @Test
    void runIndexAllMetadataShouldCallService() throws Exception {
        batchJobRunner.run("indexAllMetadata", null);
        verify(indexerMetadataService).indexAllMetadataRecordsFromGeoNetwork(isNull(), eq(true), any());
    }

    @Test
    void runIndexAllCloudOptimisedDatasetWithParamShouldThrow() {
        Exception ex = assertThrows(IllegalArgumentException.class, () -> batchJobRunner.run("indexAllCODataset", "param"));
        assertTrue(ex.getMessage().contains("Job parameter not required"));
    }

    @Test
    void runIndexAllCODataFromUuidMissingParamShouldThrow() {
        Exception ex = assertThrows(IllegalArgumentException.class, () -> batchJobRunner.run("indexAllCODataFromUuid", null));
        assertTrue(ex.getMessage().contains("Job parameter (beginWithUuid) is required"));
    }

    @Test
    void runUnknownJobShouldThrow() {
        Exception ex = assertThrows(IllegalArgumentException.class, () -> batchJobRunner.run("unknownJob", null));
        assertTrue(ex.getMessage().contains("Unknown job name"));
    }
}
