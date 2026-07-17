package au.org.aodn.esindexer.controller;

import au.org.aodn.cloudoptimized.model.MetadataEntity;
import au.org.aodn.cloudoptimized.model.TemporalExtent;
import au.org.aodn.cloudoptimized.service.DataAccessService;
import au.org.aodn.esindexer.service.IndexCloudOptimizedService;
import au.org.aodn.esindexer.service.IndexService;
import au.org.aodn.esindexer.service.AcronymService;
import au.org.aodn.esindexer.service.IndexerMetadataService;
import au.org.aodn.metadata.geonetwork.service.GeoNetworkService;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.xml.bind.JAXBException;
import lombok.extern.slf4j.Slf4j;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import software.amazon.awssdk.services.batch.BatchClient;
import software.amazon.awssdk.services.batch.model.KeyValuePair;
import software.amazon.awssdk.services.batch.model.SubmitJobRequest;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@RestController
@RequestMapping(value = "/api/v1/indexer/index")
@Tag(name="Indexer", description = "The Indexer API")
@Slf4j
public class IndexerController {

    @Autowired
    IndexerMetadataService indexerMetadata;

    @Autowired
    IndexCloudOptimizedService indexCloudOptimizedData;

    @Autowired
    GeoNetworkService geonetworkResourceService;

    @Autowired
    DataAccessService dataAccessService;

    @Autowired
    BatchClient batchClient;

    @Autowired
    AcronymService acronymService;

    @GetMapping(path="/records/{uuid}", produces = "application/json")
    @Operation(description = "Get a document from GeoNetwork by UUID directly - JSON format response")
    public ResponseEntity<String> getMetadataRecordFromGeoNetworkByUUID(@PathVariable("uuid") String uuid) {
        log.info("getting a document from geonetwork by UUID: {}", uuid);
        String response =  geonetworkResourceService.searchRecordBy(uuid);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @GetMapping(path="/{uuid}", produces = "application/json")
    @Operation(description = "Get a document from portal index by UUID")
    public ResponseEntity<ObjectNode> getDocumentByUUID(@PathVariable("uuid") String uuid) throws IOException {
        log.info("getting a document form portal by UUID: {}", uuid);
        ObjectNode response =  indexerMetadata.getDocumentByUUID(uuid).source();
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }
    /**
     * A synchronized load operation, useful for local run but likely fail in cloud due to gateway time out. No response
     * come back unlike everything done. Please use async load with postman if you want feedback constantly.
     *
     * @param confirm - Must set to true to begin load
     * @param beginWithUuid - You want to start load with particular uuid, it is useful for resume previous incomplete reload
     * @return A string contains all ingested record status
     * @throws IOException - Any failure during reload, it is the called to handle the error
     */
    @PostMapping(path="/all", consumes = "application/json", produces = "application/json")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Index all metadata records from GeoNetwork")
    public ResponseEntity<String> indexAllMetadataRecords(
            @RequestParam(value = "confirm", defaultValue = "false") Boolean confirm,
            @RequestParam(value = "beginWithUuid", required=false) String beginWithUuid) throws IOException {

        List<BulkResponse> responses = indexerMetadata.indexAllMetadataRecordsFromGeoNetwork(beginWithUuid, confirm, null);
        return ResponseEntity.ok(responses.toString());
    }

    /**
     * Build the acronyms from the Organisation vocab (vocabs_index) and push them into the ES
     * synonyms set, live (no reindex). Overwrites the set.
     * @return A confirmation message
     */
    @PostMapping(path="/acronyms", produces = "application/json")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Build acronyms from the Organisation vocab and push them into the ES synonyms set (live, no reindex)")
    public ResponseEntity<AcronymService.AcronymSyncResult> syncAcronyms() throws IOException {
        log.info("pushing acronym synonyms set from the Organisation vocab");
        int pushed = acronymService.pushAcronymListToElasticsearch();
        String message = pushed > 0
                ? "Acronym synonyms set updated with " + pushed + " rules"
                : "No rules came from the vocab; synonyms set left unchanged";
        return ResponseEntity.ok(new AcronymService.AcronymSyncResult(
                acronymService.getSynonymSetName(), pushed, message));
    }

    /**
     * Read-only preview of the acronym rules from the Organisation vocab (vocabs_index). Nothing is
     * pushed; the synonyms set is left untouched. Use it to check the rules before POST /acronyms.
     * @return The "short => long" rules
     */
    @GetMapping(path="/acronyms/preview", produces = "application/json")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Preview acronym rules from the Organisation vocab (read-only, nothing is pushed)")
    public ResponseEntity<AcronymService.AcronymPreview> previewAcronyms() throws IOException {
        log.info("previewing acronym synonyms from the Organisation vocab");
        return ResponseEntity.ok(acronymService.previewAcronyms());
    }

    /**
     * Show the acronym rules currently live in the ES synonyms set (what search uses now);
     * /acronyms/preview shows what a push would write instead.
     * @return The "short => long" rules live in the synonyms set
     */
    @GetMapping(path="/acronyms/current", produces = "application/json")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Show the acronym rules currently live in the ES synonyms set (read-only)")
    public ResponseEntity<AcronymService.AcronymCurrent> currentAcronyms() throws IOException {
        log.info("reading the acronym synonyms set currently live in ES");
        return ResponseEntity.ok(acronymService.currentAcronyms());
    }

    /**
     * index all metadata records in aws batch, it is to prevent aws to gracefully shutdown ecs instance and cause some unexpected issues.
     * @param confirm - Must set to true to begin a load
     * @param beginWithUuid - You want to start load from a particular uuid, it is useful for resume previous incomplete
     * @return - The job result
     * @throws IOException - Any failure during reload, it is the caller to handle the error
     */
    @PostMapping(path="/allinbatch", consumes = "application/json", produces = "application/json")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Index all metadata records from GeoNetwork in aws batch")
    public ResponseEntity<String> indexAllMetadataRecordsInBatch(
            @RequestParam(value = "confirm", defaultValue = "false") Boolean confirm,
            @RequestParam(value = "beginWithUuid", required=false) String beginWithUuid) {

        if (!confirm) {
            return ResponseEntity.badRequest().body("You must set confirm to true to really index all metadata records in batch");
        }

        // Build the APP_ARGS value based on parameters
        String appArgs = beginWithUuid != null
                ? "--batch --jobName=indexAllMetadataFromUuid --jobParam=" + beginWithUuid
                : "--batch --jobName=indexAllMetadata";

        var envVariables = List.of(
                KeyValuePair.builder()
                        .name("APP_ARGS")
                        .value(appArgs)
                        .build()
        );

        var request = SubmitJobRequest.builder()
                .jobName("index-all-metadata-records")
                .jobQueue("indexing-queue")
                .jobDefinition("scheduled-es-indexing")
                .containerOverrides(override -> override
                        .environment(envVariables)
                )
                .build();

        var response = batchClient.submitJob(request);

        return ResponseEntity.ok("Job submitted with jobId: " + response.jobId() + ", APP_ARGS: " + appArgs);
    }

    /**
     * Emit result to FE so it will not result in gateway time-out. You need to run it with postman or whatever tools
     * support server side event, the content type needs to be text/event-stream in order to work
     * Noted: There is a bug in postman desktop, so either you run postman using web-browser with agent directly
     * or you need to have version 10.2 or above in order to get the emitted result
     *
     * @param confirm - Must set to true to begin load
     * @param beginWithUuid - You want to start load with particular uuid, it is useful for resume previous incomplete reload
     * @return The SSeEmitter for status update, you can use it to tell which record is being ingested and ingest status.
     */
    @PostMapping(path="/async/all")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Index all metadata records from GeoNetwork")
    public SseEmitter indexAllMetadataRecordsAsync(
            @RequestParam(value = "confirm", defaultValue = "false") Boolean confirm,
            @RequestParam(value = "beginWithUuid", required=false) String beginWithUuid) {

        final SseEmitter emitter = new SseEmitter(0L); // 0L means no timeout;
        final IndexService.Callback callback = createCallback(emitter);

        new Thread(() -> {
            try {
                indexerMetadata.indexAllMetadataRecordsFromGeoNetwork(beginWithUuid, confirm, callback);
            }
            catch(IOException e) {
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }

    @PostMapping(path="/async/all-cloud")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Index a dataset by UUID")
    public SseEmitter indexAllCOData(@RequestParam(value = "beginWithUuid", required=false) String beginWithUuid) {
        final SseEmitter emitter = new SseEmitter(0L); // 0L means no timeout;
        final IndexService.Callback callback = createCallback(emitter);

        new Thread(() -> {
            try {
                indexCloudOptimizedData.indexAllCloudOptimizedData(beginWithUuid, callback);
            }
            catch (Exception ioe) {
                callback.onError(ioe);
            }
            finally {
                log.info("Close emitter at indexAllCOData");
                emitter.complete();
            }
        }).start();

        return emitter;
    }
    /**
     *
     * @param uuid - The UUID of the metadata
     * @param withCO - Index cloud optimized data the same time
     * @return - No use
     * @throws IOException - No use
     * @throws FactoryException - No use
     * @throws JAXBException - No use
     * @throws TransformException - No use
     * @throws InterruptedException - No use
     */
    @PostMapping(path="/{uuid}", produces = "application/json")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Index a metadata record by UUID")
    public ResponseEntity<String> addDocumentByUUID(
            @PathVariable("uuid") String uuid,
            @RequestParam(value = "withCO", defaultValue = "false") Boolean withCO) throws IOException, FactoryException, JAXBException, TransformException, InterruptedException {

        if(withCO) {
            CountDownLatch countDownLatch = new CountDownLatch(1);
            indexCODataByUUID(uuid, null, null, countDownLatch);

            // Wait till index co data completed
            countDownLatch.await();
        }
        String metadataValues = geonetworkResourceService.searchRecordBy(uuid);
        CompletableFuture<ResponseEntity<String>> f = indexerMetadata.indexMetadata(metadataValues);
        // Return when done make it back to sync instead of async
        return f.join();
    }

    @DeleteMapping(path="/{uuid}", produces = "application/json")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Delete a metadata record by UUID")
    public ResponseEntity<String> deleteDocumentByUUID(@PathVariable("uuid") String uuid) throws IOException {
        return indexerMetadata.deleteDocumentByUUID(uuid);
    }

    @PostMapping(path="/{uuid}/cloud")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Index a dataset by UUID")
    public SseEmitter indexCODataByUUID(
            @PathVariable("uuid") String uuid,
            @RequestParam(value="start_date", required = false) String startDate,
            @RequestParam(value="end_date", required = false) String endDate) {
        return indexCODataByUUID(uuid, startDate, endDate, null);
    }

    protected SseEmitter indexCODataByUUID(String uuid, String start, String end, CountDownLatch countDownLatch)  {

        final SseEmitter emitter = new SseEmitter(0L); // 0L means no timeout;
        final IndexService.Callback callback = createCallback(emitter);
        final CountDownLatch msgCountDown = new CountDownLatch(1);

        // We need to keep sending messages to client to avoid timeout on long processing
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Void> msg = () -> {
            // Make sure gateway not timeout on long processing
            while(!msgCountDown.await(20, TimeUnit.SECONDS)) {
                if (callback != null) {
                    callback.onProgress("Processing Cloud Optimized Data Index.... ");
                }
            }
            return null;
        };

        Callable<Void> task = () -> {
            try {
                Map<String, MetadataEntity> metadata = dataAccessService.getMetadataByUuid(uuid);

                if (metadata != null) {
                    for (String key : metadata.keySet()) {
                        List<TemporalExtent> temporalExtents = dataAccessService.getTemporalExtentOf(uuid, key);

                        if (!temporalExtents.isEmpty()) {
                            // Only first block works from dataservice api
                            LocalDate startDate = start == null ? temporalExtents.get(0).getLocalStartDate() : LocalDate.parse(start);
                            LocalDate endDate = end == null ? temporalExtents.get(0).getLocalEndDate() : LocalDate.parse(end);
                            log.info("Index cloud optimized data with UUID: {} from {} to {}", uuid, startDate, endDate);

                            indexCloudOptimizedData.indexCloudOptimizedData(metadata.get(key), startDate, endDate, callback);
                        } else {
                            log.info("Index cloud optimized data : {} not found", uuid);
                        }
                    }
                }
            }
            catch (Exception ioe) {
                callback.onError(ioe);
            }
            finally {
                if(countDownLatch != null) {
                    countDownLatch.countDown();
                }
                log.info("Close emitter at indexCODataByUUID");
                emitter.complete();
                msgCountDown.countDown();
            }
            return null;
        };

        executor.submit(msg);
        executor.submit(task);
        // Let submitted tasks finish, then release the pool threads
        executor.shutdown();

        return emitter;
    }

    protected IndexerMetadataService.Callback createCallback(SseEmitter emitter) {
        return new IndexService.Callback() {
            @Override
            public void onProgress(Object update) {
                try {
                    log.info("Send sse message to client - {}", update.toString());
                    SseEmitter.SseEventBuilder event = SseEmitter.event()
                            .data(update.toString())
                            .id(String.valueOf(update.hashCode()))
                            .name("Indexer update event");

                    emitter.send(event);
                } catch (IOException e) {
                    // In case of fail, try close the stream, if it cannot be closed. (likely stream terminated
                    // already, the load error out and we need to result from a particular uuid.
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onComplete(Object result) {
                try {
                    log.info("Flush and complete update to client");
                    SseEmitter.SseEventBuilder event = SseEmitter.event()
                            .data(result.toString())
                            .id(String.valueOf(result.hashCode()))
                            .name("Indexer update event");

                    emitter.send(event);
                    log.info("Close emitter in onComplete");
                    emitter.complete();
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onError(Throwable t) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(t.getMessage()));
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }

            }
        };
    }
}
