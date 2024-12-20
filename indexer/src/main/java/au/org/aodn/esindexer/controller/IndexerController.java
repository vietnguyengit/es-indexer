package au.org.aodn.esindexer.controller;

import au.org.aodn.esindexer.service.DataAccessService;
import au.org.aodn.esindexer.service.GeoNetworkService;
import au.org.aodn.esindexer.service.IndexerService;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping(value = "/api/v1/indexer/index")
@Tag(name="Indexer", description = "The Indexer API")
@Slf4j
public class IndexerController {

    @Autowired
    IndexerService indexerService;

    @Autowired
    GeoNetworkService geonetworkResourceService;

    @Autowired
    DataAccessService dataAccessService;

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
        ObjectNode response =  indexerService.getDocumentByUUID(uuid).source();
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

        List<BulkResponse> responses = indexerService.indexAllMetadataRecordsFromGeoNetwork(beginWithUuid, confirm, null);
        return ResponseEntity.ok(responses.toString());
    }
    /**
     * Emit result to FE so it will not result in gateway time-out. You need to run it with postman or whatever tools
     * support server side event, the content type needs to be text/event-stream in order to work
     *
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

        IndexerService.Callback callback = new IndexerService.Callback() {
            @Override
            public void onProgress(Object update) {
                try {
                    log.info("Send update with content - {}", update.toString());
                    SseEmitter.SseEventBuilder event = SseEmitter.event()
                            .data(update.toString())
                            .id(String.valueOf(update.hashCode()))
                            .name("Indexer update event");

                    emitter.send(event);
                }
                catch (IOException e) {
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
                    emitter.complete();
                }
                catch (IOException e) {
                    emitter.completeWithError(e);
                }
            }
        };

        new Thread(() -> {
            try {
                indexerService.indexAllMetadataRecordsFromGeoNetwork(beginWithUuid, confirm, callback);
            }
            catch(IOException e) {
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }

    @PostMapping(path="/{uuid}", produces = "application/json")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Index a metadata record by UUID")
    public ResponseEntity<String> addDocumentByUUID(@PathVariable("uuid") String uuid) throws IOException, FactoryException, JAXBException, TransformException, ExecutionException, InterruptedException {
        String metadataValues = geonetworkResourceService.searchRecordBy(uuid);

        CompletableFuture<ResponseEntity<String>> f = indexerService.indexMetadata(metadataValues);
        // Return when done make it back to sync instead of async
        return f.join();
    }

    @DeleteMapping(path="/{uuid}", produces = "application/json")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Delete a metadata record by UUID")
    public ResponseEntity<String> deleteDocumentByUUID(@PathVariable("uuid") String uuid) throws IOException {
        return indexerService.deleteDocumentByUUID(uuid);
    }

    @PostMapping(path="/{uuid}/dataset", produces = "application/json")
    @Operation(security = {@SecurityRequirement(name = "X-API-Key") }, description = "Index a dataset by UUID")
    public ResponseEntity<List<String>> indexDatasetByUUID(@PathVariable("uuid") String uuid)  {

        var temporalExtents = dataAccessService.getTemporalExtentOf(uuid);
        var startDate = temporalExtents.getStartDate();
        var endDate = temporalExtents.getEndDate();
        log.info("Indexing dataset with UUID: {} from {} to {}", uuid, startDate, endDate);

        var responses = indexerService.indexDataset(uuid, startDate, endDate);

        List<String> result = new ArrayList<>();
        for (BulkResponse response : responses) {
            result.add(response.toString());
        }

        return ResponseEntity.ok(result);
    }
}
