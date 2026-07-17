package au.org.aodn.esindexer.service;

import au.org.aodn.ardcvocabs.model.VocabModel;
import au.org.aodn.datadiscoveryai.service.DataDiscoveryAiService;
import au.org.aodn.datadiscoveryai.model.AiEnhancementRequest;
import au.org.aodn.esindexer.configuration.AppConstants;
import au.org.aodn.esindexer.exception.*;
import au.org.aodn.esindexer.utils.CommonUtils;
import au.org.aodn.esindexer.utils.GcmdKeywordUtils;
import au.org.aodn.esindexer.utils.JaxbUtils;
import au.org.aodn.metadata.geonetwork.exception.MetadataNotFoundException;
import au.org.aodn.metadata.geonetwork.service.GeoNetworkService;
import au.org.aodn.metadata.iso19115_3_2018.MDMetadataType;
import au.org.aodn.stac.model.SearchSuggestionsModel;
import au.org.aodn.stac.model.StacCollectionModel;
import au.org.aodn.stac.model.ThemesModel;
import au.org.aodn.stac.model.LinkModel;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.elasticsearch.indices.AnalyzeRequest;
import co.elastic.clients.elasticsearch.indices.AnalyzeResponse;
import co.elastic.clients.elasticsearch.indices.analyze.AnalyzeToken;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.xml.bind.JAXBException;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.ResponseException;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static au.org.aodn.esindexer.utils.CommonUtils.safeGet;

@Slf4j
@Service
@Scope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class IndexerMetadataServiceImpl extends IndexServiceImpl implements IndexerMetadataService {

    protected String indexName;
    protected final static String RUNNING_ALIAS_SUFFIX = "-running";
    protected String tokensAnalyserName;
    protected GeoNetworkService geoNetworkResourceService;
    protected ElasticsearchClient portalElasticsearchClient;
    protected ElasticSearchIndexService elasticSearchIndexService;
    protected ObjectMapper indexerObjectMapper;
    protected StacCollectionMapperService stacCollectionMapperService;
    protected JaxbUtils<MDMetadataType> jaxbUtils;
    protected RankingService rankingService;
    protected VocabService vocabService;
    protected GcmdKeywordUtils gcmdKeywordUtils;
    protected DataDiscoveryAiService dataDiscoveryAiService;
    protected AcronymService acronymService;

    @Lazy
    @Autowired
    protected IndexerMetadataService self;

    @Autowired
    public IndexerMetadataServiceImpl(
            @Value("${elasticsearch.index.name}") String indexName,
            @Value("${elasticsearch.analyser.tokens.name}") String tokensAnalyserName,
            ObjectMapper indexerObjectMapper,
            JaxbUtils<MDMetadataType> jaxbUtils,
            RankingService rankingService,
            GeoNetworkService geoNetworkResourceService,
            @Qualifier("portalElasticsearchClient") ElasticsearchClient elasticsearchClient,
            ElasticSearchIndexService elasticSearchIndexService,
            StacCollectionMapperService stacCollectionMapperService,
            VocabService vocabService,
            GcmdKeywordUtils gcmdKeywordUtils,
            DataDiscoveryAiService dataDiscoveryAiService,
            AcronymService acronymService
    ) {
        super(elasticsearchClient, indexerObjectMapper);

        this.indexName = indexName;
        this.tokensAnalyserName = tokensAnalyserName;
        this.indexerObjectMapper = indexerObjectMapper;
        this.jaxbUtils = jaxbUtils;
        this.rankingService = rankingService;
        this.geoNetworkResourceService = geoNetworkResourceService;
        this.portalElasticsearchClient = elasticsearchClient;
        this.elasticSearchIndexService = elasticSearchIndexService;
        this.stacCollectionMapperService = stacCollectionMapperService;
        this.vocabService = vocabService;
        this.gcmdKeywordUtils = gcmdKeywordUtils;
        this.dataDiscoveryAiService = dataDiscoveryAiService;
        this.acronymService = acronymService;
    }

    public Hit<ObjectNode> getDocumentByUUID(String uuid) throws IOException {
        return getDocumentByUUID(uuid, indexName);
    }

    public Hit<ObjectNode> getDocumentByUUID(String uuid, String indexName) throws IOException {
        try {
            SearchResponse<ObjectNode> response = portalElasticsearchClient
                    .search(s -> s
                            .index(indexName)
                            .query(q -> q.ids(ids -> ids.values(uuid))),
                        ObjectNode.class
                    );
            TotalHits total = Objects.requireNonNull(response.hits().total());
            if (total.value() > 0) {
                boolean isExactResult = total.relation() == TotalHitsRelation.Eq && Objects.equals(uuid, Objects.requireNonNull(Objects.requireNonNull(response.hits().hits().get(0).source()).get("id").asText()));
                if (!isExactResult) {
                    throw new DocumentNotFoundException("Document with UUID: " + uuid + " not found in index: " + indexName);
                } else {
                    return response.hits().hits().get(0);
                }
            } else {
                throw new DocumentNotFoundException("Document with UUID: " + uuid + " not found in index: " + indexName);
            }
        } catch (ElasticsearchException | IOException e) {
            throw new IOException("Failed to get document with UUID: " + uuid + " | " + e.getMessage());
        }
    }

    @Override
    public boolean isGeoNetworkInstanceReinstalled(long portalIndexDocumentsCount) {
        /*
         * compare if GeoNetwork has only one metadata (the recently added one which triggered the indexer)
         * and the portal index has more than 0 documents (the most recent metadata yet indexed to portal index at this point)
         */
        return geoNetworkResourceService.isMetadataRecordsCountLessThan(2) && portalIndexDocumentsCount > 0;
    }

    @Override
    public boolean isMetadataPublished(String uuid) {
        /* read for the published status from GN Elasticsearch index, the flag is not part of the XML body */
        try {
            geoNetworkResourceService.searchRecordBy(uuid);
            return true;
        }
        catch(MetadataNotFoundException e) {
            return false;
        }
    }
    /**
     * Call the analyzer to create token, however it cannot be too big and too fast else you will get
     * URI [/es-indexer-edge__20260208_224943+0000/_analyze], status line [HTTP/1.1 429 Too Many Requests]
     * @param description - The text you want to tokenize
     * @param targetIndexName - The index name
     * @return - The token created from description
     * @throws IOException - Not expected
     */
    @Retryable(
            retryFor = ResponseException.class,
            maxAttempts = 15,
            backoff = @Backoff(delay = 60000, multiplier = 2)
    )
    public Set<String> extractTokensFromDescription(String description, String targetIndexName) throws IOException {
        Set<String> results = new HashSet<>();
        AnalyzeRequest request = AnalyzeRequest.of(ar -> ar
                .index(targetIndexName)
                .analyzer(tokensAnalyserName)
                .text(description)
        );

        AnalyzeResponse response = portalElasticsearchClient.indices().analyze(request);

        for (AnalyzeToken token : response.tokens()) {
            // tweak as needed
            String cleanedToken = token.token().replace("_", "").replaceAll("\\s{2,}", " ").trim();
            if (!cleanedToken.isEmpty() && cleanedToken.split("\\s+").length > 0) { // change to 1 for at least 2 words, 2 for at least 3 words
                results.add(cleanedToken);
            }
        }
        return results;
    }

    protected StacCollectionModel getMappedMetadataValues(String metadataValues) throws IOException, FactoryException, TransformException, JAXBException {
        return getMappedMetadataValues(metadataValues, indexName);
    }

    protected StacCollectionModel getMappedMetadataValues(String metadataValues, String targetIndexName) throws IOException, FactoryException, TransformException, JAXBException {
        MDMetadataType metadataType = jaxbUtils.unmarshal(metadataValues);

        // Step 1: Pure mapping (XML -> STAC)
        StacCollectionModel stacCollectionModel = stacCollectionMapperService.mapToSTACCollection(metadataType);

        // Step 2: Apply AI enhancements
        enhanceWithAi(stacCollectionModel, metadataType);

        // evaluate completeness
        // TODO: in future, evaluate other aspects of the data such as relevance, quality, etc using NLP

        /* expand score with other aspect of the data such as relevance, quality, etc.
        * can maintain 100 points as the maximum score by dividing the score by the number of aspects (round up/down to the nearest integer)
        * given max score is 100 for each aspect
        * e.g completeness = 80, relevance = 90, quality = 100
        * final score = (80 + 90 + 100) / 3 = 90
        */
        Integer score = rankingService.evaluateCompleteness(stacCollectionModel);

        stacCollectionModel.getSummaries().setScore(score);

        // extract parameter vocabs and platform vocabs
        // separate original themes from AI enhanced themes (AI themes have concepts with ai:description property)
        List<ThemesModel> originalThemes = stacCollectionModel.getThemes().stream()
                .filter(theme -> theme.getConcepts().stream()
                        .noneMatch(concept -> concept.getAiDescription() != null))
                .toList();
        // for AI predicted themes, the concept.ai:description field is not null
        List<ThemesModel> aiThemes = stacCollectionModel.getThemes().stream()
                .filter(theme -> theme.getConcepts().stream()
                        .anyMatch(concept -> concept.getAiDescription() != null))
                .toList();
        // parameter vocabs
        Set<String> mappedParameterLabels = new HashSet<>();
        Set<String> processedParameterVocabs = vocabService.extractVocabLabelsFromThemes(
                originalThemes,
                VocabService.VocabType.AODN_DISCOVERY_PARAMETER_VOCABS,
                false
        );

        if (!processedParameterVocabs.isEmpty()) {
            mappedParameterLabels.addAll(processedParameterVocabs);
        } else {
            // manual mapping with custom logic when the record doesn't have existing AODN Parameter Vocabs
            // use originalThemes to avoid mixing AI enhanced concepts into GCMD mapping
            mappedParameterLabels.addAll(gcmdKeywordUtils.getMappedParameterVocabsFromGcmdKeywords(originalThemes));
        }
        stacCollectionModel.getSummaries().setParameterVocabs(new ArrayList<>(mappedParameterLabels));

        // process AI enhanced themes -> aiParameterVocabs
        if (!aiThemes.isEmpty()) {
            Set<String> aiParameterLabels = vocabService.extractVocabLabelsFromThemes(
                    aiThemes,
                    VocabService.VocabType.AODN_DISCOVERY_PARAMETER_VOCABS,
                    false
            );
            stacCollectionModel.getSummaries().setAiParameterVocabs(new ArrayList<>(aiParameterLabels));
        }

        /*
        NOTE: The following implementation for platform and organization vocabularies is just a placeholder, not the final version.
        It follows the same logic as what we intended for the parameter vocabulary, where we extract the list of second-level vocabularies that a record belongs to from its bottom-level vocabularies.
        // TODO: Adjust if necessary, or remove the above comments after making a final decision.
        --------------BEGIN--------------
        */
        // platform vocabs use first level to do the search, hence we need to add the first level to the list
        // operate with original themes
        Set<String> processedPlatformVocabs = vocabService.extractVocabLabelsFromThemes(
                originalThemes,
                VocabService.VocabType.AODN_PLATFORM_VOCABS,
                true
        );
        stacCollectionModel.getSummaries().setPlatformVocabs(new ArrayList<>(processedPlatformVocabs));

        // process AI enhanced themes -> aiPlatformVocabs
        if (!aiThemes.isEmpty()) {
            Set<String> aiPlatformLabels = vocabService.extractVocabLabelsFromThemes(
                    aiThemes,
                    VocabService.VocabType.AODN_PLATFORM_VOCABS,
                    false
            );
            stacCollectionModel.getSummaries().setAiPlatformVocabs(new ArrayList<>(aiPlatformLabels));
        }

        // organisation vocabs
        Set<String> mappedOrganisationLabels = new HashSet<>();
        List<String> organisationLabelsFromThemes = vocabService.extractOrganisationVocabLabelsFromThemes(stacCollectionModel.getThemes());
        if (!organisationLabelsFromThemes.isEmpty()) {
            mappedOrganisationLabels.addAll(organisationLabelsFromThemes);
        } else {
            // manual mapping with custom logics when the record doesn't have existing AODN Organisation Vocabs
            List<VocabModel> mappedOrganisationVocabsFromContacts = vocabService.getMappedOrganisationVocabsFromContacts(stacCollectionModel.getContacts());
            for (VocabModel vocabModel : mappedOrganisationVocabsFromContacts) {
                mappedOrganisationLabels.addAll(extractOrderedLabels(vocabModel));
            }
        }
        stacCollectionModel.getSummaries().setOrganisationVocabs(new ArrayList<>(mappedOrganisationLabels));

        // search_as_you_type enabled fields can be extended
        // safely merge original parameter vocabs and AI-predicted Parameter vocabs and platform vocabs to serch suggestion
        // Merge parameterVocabs and aiParameterVocabs, handling null cases
        List<String> allParameterVocabs = Stream.of(
                        stacCollectionModel.getSummaries().getParameterVocabs(),
                        stacCollectionModel.getSummaries().getAiParameterVocabs()
                )
                .filter(Objects::nonNull) // skip null lists, e.g. no AI parameter vocabs
                .flatMap(Collection::stream)
                .distinct()
                .toList();

        // Merge platformVocabs and aiPlatformVocabs
        List<String> allPlatformVocabs = Stream.of(
                        stacCollectionModel.getSummaries().getPlatformVocabs(),
                        stacCollectionModel.getSummaries().getAiPlatformVocabs() // skip null lists, e.g. no AI platform vocabs
                )
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .distinct()
                .toList();

        SearchSuggestionsModel searchSuggestionsModel = SearchSuggestionsModel.builder()
                .abstractPhrases(List.copyOf(self.extractTokensFromDescription(stacCollectionModel.getDescription(), targetIndexName)))
                .parameterVocabs(allParameterVocabs)
                .platformVocabs(allPlatformVocabs)
                .organisationVocabs(stacCollectionModel.getSummaries().getOrganisationVocabs())
                .build();
        stacCollectionModel.setSearchSuggestionsModel(searchSuggestionsModel);

        return stacCollectionModel;
    }

    /**
     * Enhances a STAC collection with AI-generated content including description and link grouping
     */
    protected void enhanceWithAi(StacCollectionModel target, MDMetadataType source) {
        String uuid = CommonUtils.getUUID(source);
        String title = CommonUtils.getTitle(source);
        String description = CommonUtils.getDescription(source);

        String status = safeGet(() -> target.getSummaries().getStatus()).orElse(null);
        List<Map<String, String>> temporal = safeGet(() -> target.getSummaries().getTemporal()).orElse(null);
        String statement = safeGet(() -> target.getSummaries().getStatement()).orElse(null);
        List<ThemesModel> themes = safeGet(target::getThemes).orElse(null);

        if (dataDiscoveryAiService.isServiceAvailable()) {
            log.info("start enhancing STAC collection in service layer with UUID: {}", uuid);
            try {
                // build AI enhancement request
                var aiEnhancementRequest = AiEnhancementRequest.builder()
                        .uuid(uuid)
                        .title(title)
                        .abstractText(description)
                        .links(target.getLinks())
                        .lineageText(statement)
                        .status(status)
                        .temporal(temporal)
                        .themes(themes)
                        .build();

                // Make a single AI call for both description and link enhancement
                var aiResponse = dataDiscoveryAiService.enhanceWithAi(aiEnhancementRequest);

                if (aiResponse != null) {
                    // Update AI description if available
                    String enhancedDescription = dataDiscoveryAiService.getEnhancedDescription(aiResponse);
                    if (enhancedDescription != null && !enhancedDescription.trim().isEmpty()) {
                        target.getSummaries().setAiDescription(enhancedDescription);
                    }

                    // Update links if AI enhanced links are available
                    List<LinkModel> enhancedLinks = dataDiscoveryAiService.getEnhancedLinks(aiResponse);
                    if (enhancedLinks != null && !enhancedLinks.isEmpty()) {
                        target.setLinks(enhancedLinks);
                    }

                    String inferredUpdateFrequency = dataDiscoveryAiService.getEnhancedUpdateFrequency(aiResponse);
                    if (inferredUpdateFrequency != null) {
                        target.getSummaries().setAiUpdateFrequency(inferredUpdateFrequency);
                    }

                    // Append predicted themes if AI enhanced themes are available
                    List<ThemesModel> enhancedThemes = dataDiscoveryAiService.getEnhancedThemes(aiResponse);
                    if(enhancedThemes != null) {
                        // add enhanced themes and original themes
                        List<ThemesModel> mergedThemes = new ArrayList<>(target.getThemes());
                        mergedThemes.addAll(enhancedThemes);
                        target.setThemes(mergedThemes);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to enhance STAC collection with AI for UUID: {}", uuid, e);
            }
        }
    }

    private List<String> extractOrderedLabels(VocabModel vocabModel) {
        // Priority: DisplayLabel > AltLabels > PrefLabel
        if (safeGet(vocabModel::getDisplayLabel).isPresent()) {
            return List.of(vocabModel.getDisplayLabel());
        } else if (safeGet(vocabModel::getAltLabels).isPresent()) {
            return vocabModel.getAltLabels();
        } else if (safeGet(vocabModel::getLabel).isPresent()) {
            return List.of(vocabModel.getLabel());
        }
        return List.of();
    }

    /**
     * Use to index a particular UUID, the async is used to limit the number of same function call to avoid flooding
     * the system.
     * @param metadataValues - The XML of the metadata
     * @return - The STAC doc in string format.
     */
    @Async("asyncIndexMetadata")
    public CompletableFuture<ResponseEntity<String>> indexMetadata(String metadataValues) {
        try {
            StacCollectionModel mappedMetadataValues = this.getMappedMetadataValues(metadataValues);
            String uuid = mappedMetadataValues.getUuid();

            // index the metadata if it is published
            if (this.isMetadataPublished(uuid)) {
                IndexRequest<JsonData> req;

                try (InputStream is = new ByteArrayInputStream(indexerObjectMapper.writeValueAsBytes(mappedMetadataValues))) {
                    log.info("Ingesting a new metadata with UUID: {} to index: {}", uuid, indexName);
                    log.debug("{}", mappedMetadataValues);

                    // With the id in place, it will always update the same doc given the same id
                    req = IndexRequest.of(b -> b
                            .id(uuid)
                            .index(indexName)
                            .withJson(is));

                    IndexResponse response = portalElasticsearchClient.index(req);
                    log.info("Metadata with UUID: {} indexed with version: {}", uuid, response.version());
                    return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.OK).body(response.toString()));
                } catch (ElasticsearchException e) {
                    String fullError = String.format("%s -> %s", e.getMessage(), e.error().causedBy());
                    log.error(fullError);
                    throw new IndexingRecordException(fullError);
                }
            } else {
                log.info("Metadata with UUID: {} is not published yet, skip indexing", uuid);
                return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.NO_CONTENT).body(null));
            }
        } catch (IOException | FactoryException | TransformException | JAXBException e) {
            log.error(e.getMessage());
            throw new MappingValueException(e.getMessage());
        }
    }

    public ResponseEntity<String> deleteDocumentByUUID(String uuid) throws IOException {
        log.info("Deleting document with UUID: {} from index: {}", uuid, indexName);
        try {
            Hit<ObjectNode> doc = this.getDocumentByUUID(uuid);
            DeleteResponse response = portalElasticsearchClient.delete(b -> b
                    .index(indexName)
                    .id(doc.id())
            );

            log.info("Document with UUID: {} deleted from index: {}", uuid, indexName);

            // Flush after insert, otherwise you need to wait for next auto-refresh. It is
            // especially a problem with autotest, where assert happens very fast.
            portalElasticsearchClient.indices().refresh();

            return ResponseEntity.status(HttpStatus.OK).body(response.toString());
        }
        catch (DocumentNotFoundException e) {
            log.info("Document with UUID: {} not found in index: {}, skip deleting", uuid, indexName);
            return ResponseEntity.status(HttpStatus.NO_CONTENT).body(null);
        }
    }

    public List<BulkResponse> indexAllMetadataRecordsFromGeoNetwork(
            String beginWithUuid, boolean confirm, final Callback callback) {
        var runningAliasName = indexName + RUNNING_ALIAS_SUFFIX;

        // this suffix is the one without the in-use alias
        var availableIndexSuffix = elasticSearchIndexService.getAvailableIndexSuffix(indexName);

        // try to find if there is already an incomplete index with the alias,
        // if yes, it means there is an ongoing indexing process which has not been completed, and we can resume from the incomplete index;
        // if no, it means there is no ongoing indexing process, and we can start a new one with a new index.
        var incompleteIndexName = elasticSearchIndexService.getIndexNameFromAlias(runningAliasName);

        var runningIndexName = incompleteIndexName == null ?
                indexName + availableIndexSuffix :
                incompleteIndexName;

        if (!confirm) {
            throw new IndexAllRequestNotConfirmedException("Please confirm that you want to index all metadata records from GeoNetwork");
        }

        if(beginWithUuid == null) {
            log.info("Indexing all metadata records from GeoNetwork");

            // Sync the synonyms set referenced by the schema before creating the index.
            // An acronym sync failure shouldn't abort the whole reindex.
            try {
                acronymService.pushAcronymListToElasticsearch();
            } catch (ElasticsearchException | IOException e) {
                log.error("Acronym sync failed, continuing reindex: {}", e.getMessage());
            }

            // Because it is a full reindex, we need to remove the incomplete index first, and then recreate it.
            // currently, we don't want any leftover incomplete indices existing if we are not resume indexing based on it.
            log.warn("An incomplete index with name {} is found, it will be deleted and recreated because there is no beginWithUuid provided to resume from a particular UUID. ", incompleteIndexName);
            elasticSearchIndexService.recreateIndexFromMappingJSONFile(AppConstants.PORTAL_RECORDS_MAPPING_JSON_FILE, runningIndexName, Map.of("portal-acronyms", acronymService.getSynonymSetName()));

            // give the working index an running alias for more robust handling
            elasticSearchIndexService.updateAliasToNewIndex(runningAliasName, runningIndexName);
        }
        else {
            // if the beginWithUuid is provided, it means resuming from a particular UUID.
            // So if the incomplete index does not exist, no index can be resumed.
            if (incompleteIndexName == null) {
                throw new RuntimeException("Cannot find the incomplete index to resume from UUID: " + beginWithUuid);
            }
            log.info("The index {} will be used to resume", runningIndexName);
            log.info("Resume indexing records from GeoNetwork at {}", beginWithUuid);
        }

        Function<BulkResponseItem, Optional<StacCollectionModel>> mapper = (item) ->
        {
            try {
                return Optional.of(this.getMappedMetadataValues(
                        geoNetworkResourceService.searchRecordBy(item.id()),
                        runningIndexName
                ));
            } catch (IOException | FactoryException | TransformException | JAXBException e) {
                return Optional.empty();
            }
        };

        List<BulkResponse> results = new ArrayList<>();
        BulkRequestProcessor<StacCollectionModel> bulkRequestProcessor = new BulkRequestProcessor<>(runningIndexName, mapper, self, callback);

        // We need to keep sending messages to client to avoid timeout on long processing
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            for (String metadataRecord : geoNetworkResourceService.getAllMetadataRecords(beginWithUuid)) {
                if (metadataRecord != null) {
                    // get mapped metadata values from GeoNetwork to STAC collection schema
                    final CountDownLatch countDown = new CountDownLatch(1);
                    Callable<StacCollectionModel> task = () ->  {
                        try {
                            return this.getMappedMetadataValues(metadataRecord, runningIndexName);
                        }
                        catch (FactoryException | JAXBException | TransformException | NullPointerException e) {
                            /*
                             * it will reach here if cannot extract values of all the keys in GeoNetwork metadata JSON
                             * or ID is not found, which is fatal.
                             */
                            log.error("Error extracting values from GeoNetwork metadata JSON: {}", metadataRecord);
                            if (callback != null) {
                                callback.onProgress(
                                        String.format(
                                                "WARNING - Skip %s due to transform error -> %s",
                                                metadataRecord,
                                                e.getMessage()
                                        ));
                            }
                        }
                        finally {
                            countDown.countDown();
                        }
                        return null;
                    };

                    Callable<Void> msg = () -> {
                        // Make sure gateway not timeout on long processing
                        while(!countDown.await(20, TimeUnit.SECONDS)) {
                            if (callback != null) {
                                callback.onProgress("Processing Metadata Index.... ");
                            }
                        }
                        return null;
                    };
                    // Submit a task to keep sending client message to avoid timeout
                    executor.submit(msg);
                    Future<StacCollectionModel> value = executor.submit(task);

                    final StacCollectionModel mappedMetadataValues = value.get();

                    if(mappedMetadataValues != null) {
                        bulkRequestProcessor
                                .processItem(mappedMetadataValues.getUuid(), mappedMetadataValues, false)
                                .ifPresent(results::add);
                    }
                } else {
                    log.warn("Got null metadata record from GeoNetwork, skip it.");
                }
            }

            // In case there are residual, just report error
            bulkRequestProcessor
                    .flush()
                    .ifPresent(response -> {
                        results.add(response);

                        if(callback != null) {
                            callback.onComplete(response);
                        }
                    });


            // TODO now processing for record_suggestions index
            log.info("Finished execute bulk indexing records to index: {}",runningIndexName);
        }
        catch(Exception e) {
            log.error("Failed", e);

            if (callback != null) {
                callback.onComplete(
                        String.format(
                                "WARNING - Cannot process due to error -> %s, need to run 'Delete index and reindex in geonetwork?'",
                                e.getMessage()
                        ));
            }
        }
        finally {
            CommonUtils.shutdownExecutor(executor);
        }

        try {

            var metadataCount = geoNetworkResourceService.getAllMetadataCounts();
            // get document count from portal index
            var indexedCountResponse = portalElasticsearchClient.count(c -> c.index(runningIndexName));
            var indexedCount = indexedCountResponse.count();
            log.info("Total metadata records in GeoNetwork: {}, total indexed documents in portal index {}: {}",
                    metadataCount,
                    runningIndexName,
                    indexedCount);

            if (indexedCount != metadataCount) {
                log.warn(" Indexed document count ({}) does not match metadata count ({}) from GeoNetwork", indexedCount, metadataCount);
            }

            // Because of the eventual consistency feature of Elasticsearch,
            // the indexed document count can be outdated if you query immediately after insert or delete.
            // Elasticsearch officially recommends NOT to rely on explicit refresh (doc: https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-indices-refresh)
            // In addition, several edge cases of metada records shouldn't block indexing-all workflow and alias switching.
            // So small counts mismatch is acceptable to switch alias.
            // However, if the function is running in test cases, which usually have very small number of records,
            // the threshold is relaxed to avoid the issue of small counts mismatch.
            // test cases should assert their own expected results.
            if (indexedCount > metadataCount * 0.9 || metadataCount < 10) {
                finalizeAliasSwitching(runningIndexName, runningAliasName);
            } else {
                throw new RuntimeException("Indexed document count is less than 90% of metadata count from GeoNetwork, alias switch aborted. GeoNetwork metadata count: " + metadataCount + ", indexed document count: " + indexedCount);
            }

        } catch (IOException e) {
            log.error("Failed to get total metadata count from GeoNetwork: {}", e.getMessage());
        }

        return results;
    }

    private void finalizeAliasSwitching(String runningIndexName, String runningAliasName) {
        //After indexing, if there is a same-name index, delete it
        // this is not a regular deleting of old index. it is only for smoothly swapping from non-alias index to alias-based index.
        checkAndDelete(indexName, runningIndexName);

        // remove the running alias from the already completed index
        elasticSearchIndexService.removeAliasFromIndex(runningAliasName, runningIndexName);

        // The below one is the old index still using the alias (e.g. es-indexer-edge)
        var indexNameToDelete = elasticSearchIndexService.getIndexNameFromAlias(indexName);

        // switch alias to point to the new index
        elasticSearchIndexService.updateAliasToNewIndex(indexName, runningIndexName);
        log.info("Alias: {} switched to point to index: {}", indexName, runningIndexName);

        // after switching, delete the old index
        elasticSearchIndexService.deleteIndexStore(indexNameToDelete);
        log.info("Old index: {} deleted after alias switch", indexNameToDelete);
    }

    /**
     * This method only for smoothly swapping from non-alias index to alias-based index.
     * If alias already working properly in all edge, staging and prod, this method is not needed and can be removed later.
     * @param alias - The alias name, which is use to determine if this is the in use index.
     * @param workingIndexName - The name of the index,
     */
    protected void checkAndDelete(String alias ,String workingIndexName) {
        try {
            // First determine if the provided name is an alias. If it is an alias, do NOT delete.
            boolean isAlias = false;
            try {
                var getAliasResp = portalElasticsearchClient.indices().getAlias(g -> g.name(alias));
                if (getAliasResp.result() != null && !getAliasResp.result().isEmpty()) {
                    isAlias = true;
                    log.info("Provided name '{}' resolves to an alias pointing to concrete indices: {}. Skipping delete.", alias, getAliasResp.result().keySet());
                }
            } catch (ElasticsearchException | IOException e) {
                // If alias lookup fails or returns not found, treat as not an alias and continue to index existence check.
                log.debug("Alias lookup for '{}' failed or not found: {}", alias, e.getMessage());
            }

            if (isAlias) {
                return; // do not attempt to delete when the name is an alias
            }

            // Fallback: check if a concrete index with the same name exists, and delete it if appropriate
            var hasSameNameIndex = portalElasticsearchClient.indices()
                    .exists( e -> e.index(alias))
                    .value();

            if (hasSameNameIndex && !alias.equals(workingIndexName)) {
                elasticSearchIndexService.deleteIndexStore(alias);
            }

        } catch (ElasticsearchException | IOException e) {
            log.error("Failed to check/delete same name index: {} | {}", alias, e.getMessage());
        }
    }

}
