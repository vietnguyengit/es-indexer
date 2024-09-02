package au.org.aodn.esindexer.controller;

import au.org.aodn.ardcvocabs.configuration.VocabApiPaths;
import au.org.aodn.ardcvocabs.model.VocabModel;
import au.org.aodn.esindexer.configuration.AppConstants;
import au.org.aodn.esindexer.service.VocabService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping(value = "/api/v1/indexer/ext/")
@Tag(name="Indexer Extras", description = "The Indexer API - Ext endpoints")
@Slf4j
public class IndexerExtController {
    VocabService vocabService;
    @Autowired
    public void setArdcVocabService(VocabService vocabService) {
        this.vocabService = vocabService;
    }

    @Value(AppConstants.ARDC_VOCAB_API_BASE)
    protected String vocabApiBase;

    protected ObjectMapper indexerObjectMapper;
    @Autowired
    public void setIndexerObjectMapper(ObjectMapper indexerObjectMapper) {
        this.indexerObjectMapper = indexerObjectMapper;
    }

    // this endpoint for debugging/development purposes
    @GetMapping(path="/parameter/vocabs")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Get parameter vocabs from Elastic search")
    public ResponseEntity<List<JsonNode>> getParameterVocab() throws IOException {
        return ResponseEntity.ok(vocabService.getParameterVocabs());
    }

    // this endpoint for debugging/development purposes
    @GetMapping(path="/platform/vocabs")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Get platform vocabs from Elastic search")
    public ResponseEntity<List<JsonNode>> getPlatformVocabs() throws IOException {
        return ResponseEntity.ok(vocabService.getPlatformVocabs());
    }

    // this endpoint for debugging/development purposes
    @GetMapping(path="/organisation/vocabs")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Get organisation vocabs from Elastic search")
    public ResponseEntity<List<JsonNode>> getOrganisationVocabs() throws IOException {
        return ResponseEntity.ok(vocabService.getOrganisationVocabs());
    }

    // this endpoint for debugging/development purposes
    @GetMapping(path="/ardc/parameter/vocabs")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Get parameter vocabs from ARDC directly")
    public ResponseEntity<List<JsonNode>> getParameterVocabsFromArdc() {
        List<VocabModel> vocabs = vocabService.getVocabTreeFromArdcByType(vocabApiBase, VocabApiPaths.PARAMETER_VOCAB);
        return ResponseEntity.ok(indexerObjectMapper.valueToTree(vocabs));
    }

    // this endpoint for debugging/development purposes
    @GetMapping(path="/ardc/platform/vocabs")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Get platform vocabs from ARDC directly")
    public ResponseEntity<List<JsonNode>> getPlatformVocabsFromArdc() {
        List<VocabModel> vocabs = vocabService.getVocabTreeFromArdcByType(vocabApiBase, VocabApiPaths.PLATFORM_VOCAB);
        return ResponseEntity.ok(indexerObjectMapper.valueToTree(vocabs));
    }

    // this endpoint for debugging/development purposes
    @GetMapping(path="/ardc/organisation/vocabs")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Get organisation vocabs from ARDC directly")
    public ResponseEntity<List<JsonNode>> getOrganisationVocabsFromArdc() {
        List<VocabModel> vocabs = vocabService.getVocabTreeFromArdcByType(vocabApiBase, VocabApiPaths.ORGANISATION_VOCAB);
        return ResponseEntity.ok(indexerObjectMapper.valueToTree(vocabs));
    }

    // this endpoint for debugging/development purposes
    @GetMapping(path="/vocabs/populate")
    @Operation(security = { @SecurityRequirement(name = "X-API-Key") }, description = "Populate data to the vocabs index")
    public ResponseEntity<String> populateDataToVocabsIndex() throws IOException {
        // clear existing caches
        vocabService.clearParameterVocabCache();
        vocabService.clearPlatformVocabCache();
        vocabService.clearOrganisationVocabCache();
        // populate new data
        vocabService.populateVocabsData();
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body("Populated data to the vocabs index");
    }
}
