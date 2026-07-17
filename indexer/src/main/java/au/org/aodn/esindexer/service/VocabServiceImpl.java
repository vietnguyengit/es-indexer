package au.org.aodn.esindexer.service;

import au.org.aodn.ardcvocabs.model.*;
import au.org.aodn.ardcvocabs.service.ArdcVocabService;
import au.org.aodn.esindexer.configuration.AppConstants;
import au.org.aodn.esindexer.exception.DocumentNotFoundException;
import au.org.aodn.esindexer.exception.IgnoreIndexingVocabsException;
import au.org.aodn.stac.model.ConceptModel;
import au.org.aodn.stac.model.ContactsModel;
import au.org.aodn.stac.model.ThemesModel;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;

import java.io.IOException;
import java.util.*;

import static au.org.aodn.esindexer.utils.CommonUtils.safeGet;

@Slf4j
@Service
// create and inject a stub proxy to self due to the circular reference http://bit.ly/4aFvYtt
@Scope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class VocabServiceImpl implements VocabService {

    @Value("${elasticsearch.vocabs_index.name}")
    protected String vocabsIndexName;

    protected static final String NARROWER = "narrower";
    protected static final String LABEL = "label";
    protected static final String ABOUT = "about";

    // self-injection to avoid self-invocation problems when calling the cachable method within the same bean
    @Lazy
    @Autowired
    VocabService self;

    protected ElasticsearchClient portalElasticsearchClient;
    protected ElasticSearchIndexService elasticSearchIndexService;
    protected ObjectMapper indexerObjectMapper;
    protected ArdcVocabService ardcVocabService;
    protected String available = null;
    protected ExecutorService executorService = Executors.newFixedThreadPool(3);

    protected boolean themeMatchConcept(ThemesModel theme, ConceptModel thatConcept) {
        /*
         * comparing by combined values (id and url) of the concept object
         * this will prevent cases where bottom-level vocabs are the same in text, but their parent vocabs are different
         * e.g "car -> parts" vs "bike -> parts" ("parts" is the same but different parent)
         * thisConcept is the extracted from the themes of the record...theme.getConcepts()
         * thatConcept is the object created by iterating over the parameter_vocabs cache...ConceptModel thatConcept = ConceptModel.builder()
         * using overriding equals method to compare the two objects, this is not checking instanceof ConceptModel class
         */

        // since vocabs don't have title nor description, so only compare id and url
        return theme.getConcepts()
                .stream()
                .anyMatch(f ->
                {
                    var thisId = safeGet(() -> f.getId().toLowerCase()).orElse("");
                    var thatId = safeGet(() -> thatConcept.getId().toLowerCase()).orElse("");

                    var thisUrl = safeGet(() -> f.getUrl().toLowerCase()).orElse("");
                    var thatUrl = safeGet(() -> thatConcept.getUrl().toLowerCase()).orElse("");
                    return thisId.equals(thatId) && thisUrl.equals(thatUrl);
                });
    }

    @Autowired
    public VocabServiceImpl(
            ArdcVocabService ardcVocabService,
            ObjectMapper indexerObjectMapper,
            ElasticsearchClient portalElasticsearchClient,
            ElasticSearchIndexService elasticSearchIndexService) {

        this.indexerObjectMapper = indexerObjectMapper;
        this.ardcVocabService = ardcVocabService;
        this.portalElasticsearchClient = portalElasticsearchClient;
        this.elasticSearchIndexService = elasticSearchIndexService;
    }

    /*
    this method for analysing the vocabularies of a record aka bottom-level vocabs (found in the themes section)
    and returning the second-level vocabularies that match (1 level up from the bottom-level vocabularies)
     */
    @Override
    public Set<String> extractVocabLabelsFromThemes(List<ThemesModel> themes, VocabType vocabType, boolean includeFirstLevel) throws IOException {
        Set<String> results = new HashSet<>();
        // Iterate over the top-level vocabularies
        List<JsonNode> vocabs = switch (vocabType) {
            case AODN_DISCOVERY_PARAMETER_VOCABS -> self.getParameterVocabs();
            case AODN_PLATFORM_VOCABS -> self.getPlatformVocabs();
            default -> new ArrayList<>();
        };
        if (!vocabs.isEmpty() && !themes.isEmpty()) {
            // vocabs already filtered by non-null during the get operation
            vocabs.forEach(topLevelVocab -> {
                if (topLevelVocab.has(NARROWER) && !topLevelVocab.get(NARROWER).isEmpty()) {
                    for (JsonNode secondLevelVocab : topLevelVocab.get(NARROWER)) {
                        if (secondLevelVocab != null && secondLevelVocab.has(LABEL) && secondLevelVocab.has(ABOUT)) {
                            ConceptModel secondLevelVocabAsConcept = ConceptModel.builder()
                                    .id(secondLevelVocab.get(LABEL).asText().toLowerCase())
                                    .url(secondLevelVocab.get(ABOUT).asText())
                                    .build();

                            themes.stream().filter(Objects::nonNull).forEach(theme -> {
                                // if the record's theme is already second-level vocab, no need to further check
                                if (themeMatchConcept(theme, secondLevelVocabAsConcept)) {
                                    results.add(secondLevelVocabAsConcept.getId());
                                    // Add top level to the list so we can search it
                                    if (includeFirstLevel) {
                                        results.add(topLevelVocab.get(LABEL).asText());
                                    }
                                }

                                // if the record's theme is leaf-node (bottom-level vocab)
                                if (secondLevelVocab.has(NARROWER) && !secondLevelVocab.get(NARROWER).isEmpty()) {
                                    for (JsonNode bottomLevelVocab : secondLevelVocab.get(NARROWER)) {
                                        if (bottomLevelVocab != null && bottomLevelVocab.has(LABEL) && bottomLevelVocab.has(ABOUT)) {
                                            // map the original values to a ConceptModel object for doing comparison
                                            ConceptModel leafVocabAsConcept = ConceptModel.builder()
                                                    .id(bottomLevelVocab.get(LABEL).asText())
                                                    .url(bottomLevelVocab.get(ABOUT).asText())
                                                    .build();

                                            // Compare with themes' concepts
                                            if (themeMatchConcept(theme, leafVocabAsConcept)) {
                                                results.add(secondLevelVocabAsConcept.getId());
                                                // Add top level to the list so we can search it
                                                if (includeFirstLevel) {
                                                    results.add(topLevelVocab.get(LABEL).asText());
                                                }
                                                // just checking 1 leaf-node of each second-level vocab is enough, because we only care second-level vocabs.
                                                break;
                                            }
                                        }
                                    }
                                }
                            });
                        }
                    }
                }
            });
        }
        return results;
    }

    public List<String> extractOrganisationVocabLabelsFromThemes(List<ThemesModel> themes) {
        List<String> results = new ArrayList<>();

        // filter out null themes and null concepts
        themes = themes.stream()
                .filter(Objects::nonNull)
                .filter(theme -> theme.getConcepts() != null).toList();

        for (var theme : themes) {
            for (var concept : theme.getConcepts()) {

                if (concept.getId() == null || concept.getId().isEmpty()) {
                    continue;
                }

                if (concept.getTitle() == null || concept.getTitle().isEmpty()) {
                    continue;
                }

                if (concept.getTitle().toLowerCase().contains("aodn organisation vocabulary")) {
                    results.add(concept.getId());
                }
            }
        }
        return results;
    }

    public List<VocabModel> getMappedOrganisationVocabsFromContacts(List<ContactsModel> contacts) throws IOException {
        List<String> contactOrgs = new ArrayList<>();

        // (A) (note this)
        // only consider "organisations" with below roles
        // but we don't take them on, only 1, but depends on the priority ranking
        String citationRole = "citation";
        String pointOfContactRole = "pointOfContact";

        // Top priority to citation: cit:citedResponsibleParty>
        contacts.stream()
                .filter(contact -> safeGet(contact::getRoles)
                        .filter(roles -> roles.contains(citationRole))
                        .isPresent())
                .map(ContactsModel::getOrganization)
                .filter(Objects::nonNull)
                .forEach(contactOrgs::add);

        // Second priority if contactOrgs is still empty
        if (contactOrgs.isEmpty()) {
            contacts.stream()
                    .filter(contact -> safeGet(contact::getRoles)
                            .filter(roles -> roles.contains(pointOfContactRole))
                            .isPresent())
                    .map(ContactsModel::getOrganization)
                    .filter(Objects::nonNull)
                    .forEach(contactOrgs::add);
        }

        List<VocabModel> results = new ArrayList<>();

        // the loop for starting DFS search starts from here, each vocab from self.getOrganisationVocabs() is a top-level vocab
        // and we will explore each of the top-level vocab down the branch (to 2nd, then to 3rd level vocabs) until a match with vocabs from (A) or move to the next top-level vocab and do the same process again (recursive)
        for (JsonNode orgVocab : self.getOrganisationVocabs()) {
            if (orgVocab != null) {
                try {
                    // map the json of the top-level vocab to VocabModel class
                    VocabModel vocabModel = indexerObjectMapper.treeToValue(orgVocab, VocabModel.class);
                    // comparing and appending matches to results list via dfs search
                    // vocabModel: current top-level vocab
                    // contactOrgs: vocabs linked to the metadata record and extracted from (A)
                    // results: list of found matching vocabs
                    dfsSearch(vocabModel, contactOrgs, results);
                } catch (JsonProcessingException e) {
                    log.error("Error deserializing JsonNode to VocabModel", e);
                }
            }
        }

        return results;
    }

    /**
     * Performs a Depth-First Search (DFS) to find vocab matches.
     * DFS is well-suited for hierarchical structures due to its memory efficiency
     * and ability to capture matches at any depth while allowing early exits within branches.
     *
     * @param currentVocab the current vocab node being processed
     * @param contactOrgs  the list of organisation names to match against
     * @param results      the list to store matching vocab nodes
     */
    private void dfsSearch(VocabModel currentVocab, List<String> contactOrgs, List<VocabModel> results) {
        // Skip vocabs that have replaced_by field non-null
        if (currentVocab.getReplacedBy() != null) return;

        // Check labels in priority order and add to results if a match is found
        // the condition OR doesn't make multiple matches and adding all the thing while exploring down the branch because there is an order in OR || operator
        // left-to-right order: https://stackoverflow.com/questions/17054737/does-all-evaluation-happen-from-left-to-right
        // In Java, the || (logical OR) operator evaluates expressions from left to right and follows short-circuit evaluation
        // so each comparision below is not randomly put in the if clause, we compare with prioritised order. Comparing displayLabel first, then AltLabels, then normal Label, and last to be HiddenLabels
        if (findAndAddMatch(Collections.singletonList(currentVocab.getDisplayLabel()), contactOrgs) ||
                findAndAddMatch(currentVocab.getAltLabels(), contactOrgs) ||
                findAndAddMatch(Collections.singletonList(currentVocab.getLabel()), contactOrgs) ||
                findAndAddMatch(currentVocab.getHiddenLabels(), contactOrgs)) {
            log.info("Match found: {}", currentVocab);
            results.add(currentVocab);
            return; // this will exist the loop
        }

        // continue to reach here if not being returned at line 236
        // Recursively search narrower nodes
        // when reaching here, the process is likely analysing vocabs at 2nd-level or 3rd-level vocabs
        List<VocabModel> narrowerNodes = currentVocab.getNarrower();
        if (narrowerNodes != null) {
            for (VocabModel narrowerNode : narrowerNodes) {
                dfsSearch(narrowerNode, contactOrgs, results);
            }
        }
    }

    private boolean findAndAddMatch(List<String> labels, List<String> contactOrgs) {
        if (labels == null || labels.isEmpty()) return false;
        for (String label : labels) {
            if (label != null) {
                for (String contactOrg : contactOrgs) {
                    if (label.equalsIgnoreCase(contactOrg)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    protected List<JsonNode> groupVocabsFromEsByKey(String key) throws IOException {
        List<JsonNode> vocabs = new ArrayList<>();
        log.info("Fetching {} vocabularies from {}", key, vocabsIndexName);
        try {
            long totalHits = elasticSearchIndexService.getDocumentsCount(vocabsIndexName);
            if (totalHits == 0) {
                throw new DocumentNotFoundException("No documents found in " + vocabsIndexName);
            } else {
                SearchResponse<JsonNode> response = portalElasticsearchClient.search(s -> s
                        .index(vocabsIndexName)
                        .size((int) totalHits), JsonNode.class
                );
                response.hits().hits().stream()
                        .map(Hit::source)
                        .map(hitSource -> hitSource != null ? hitSource.get(key) : null)
                        .filter(Objects::nonNull)
                        .forEach(vocabs::add);
            }
        } catch (ElasticsearchException | IOException e) {
            throw new IOException("Failed to get documents from " + vocabsIndexName + " | " + e.getMessage());
        }
        return vocabs;
    }

    @Cacheable(value = VocabType.Names.AODN_DISCOVERY_PARAMETER_VOCABS)
    public List<JsonNode> getParameterVocabs() throws IOException {
        return groupVocabsFromEsByKey("parameter_vocab");
    }

    @Cacheable(value = VocabType.Names.AODN_PLATFORM_VOCABS)
    public List<JsonNode> getPlatformVocabs() throws IOException {
        return groupVocabsFromEsByKey("platform_vocab");
    }

    @Cacheable(value = VocabType.Names.AODN_ORGANISATION_VOCABS)
    public List<JsonNode> getOrganisationVocabs() throws IOException {
        return groupVocabsFromEsByKey("organisation_vocab");
    }

    @Override
    public void setAvailable(String status) {
        available = status;
    }

    @Override
    public Health health() {
        if(available == null) {
            return Health.status(HttpStatus.OK.toString())
                    .build();
        }
        else {
            return Health.status(HttpStatus.SERVICE_UNAVAILABLE.toString())
                    .withDetail("reason", available)
                    .build();

        }
    }

    @CacheEvict(value = VocabType.Names.AODN_DISCOVERY_PARAMETER_VOCABS, allEntries = true)
    public void clearParameterVocabCache() {
        log.info("Cache evit for {}", VocabType.Names.AODN_DISCOVERY_PARAMETER_VOCABS);
    }

    @CacheEvict(value = VocabType.Names.AODN_PLATFORM_VOCABS, allEntries = true)
    public void clearPlatformVocabCache() {
        log.info("Cache evit for {}", VocabType.Names.AODN_PLATFORM_VOCABS);
    }

    @CacheEvict(value = VocabType.Names.AODN_ORGANISATION_VOCABS, allEntries = true)
    public void clearOrganisationVocabCache() {
        log.info("Cache evit for {}", VocabType.Names.AODN_ORGANISATION_VOCABS);
    }

    protected void indexAllVocabs(List<VocabModel> parameterVocabs,
                                  List<VocabModel> platformVocabs,
                                  List<VocabModel> organisationVocabs) throws IOException {

        List<VocabDto> vocabDtos = new ArrayList<>();

        // parameter vocabs
        for (VocabModel parameterVocab : parameterVocabs) {
            VocabDto vocabDto = VocabDto.builder().parameterVocabModel(parameterVocab).build();
            vocabDtos.add(vocabDto);
        }

        // platform vocabs
        for (VocabModel platformVocab : platformVocabs) {
            VocabDto vocabDto = VocabDto.builder().platformVocabModel(platformVocab).build();
            vocabDtos.add(vocabDto);
        }

        // organisation vocabs
        for (VocabModel organisationVocab : organisationVocabs) {
            VocabDto vocabDto = VocabDto.builder().organisationVocabModel(organisationVocab).build();
            vocabDtos.add(vocabDto);
        }

        // recreate index from mapping JSON file
        elasticSearchIndexService.recreateIndexFromMappingJSONFile(AppConstants.VOCABS_INDEX_MAPPING_SCHEMA_FILE, vocabsIndexName, null);
        log.info("Indexing all vocabs to {}", vocabsIndexName);

        bulkIndexVocabs(vocabDtos);
    }

    protected void bulkIndexVocabs(List<VocabDto> vocabs) throws IOException {
        if (!vocabs.isEmpty()) {
            BulkRequest.Builder bulkRequest = new BulkRequest.Builder();

            for (VocabDto vocab : vocabs) {
                try {
                    // convert vocab values to binary data
                    log.debug("Ingested json is {}", indexerObjectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(vocab));
                    // send bulk request to Elasticsearch
                    bulkRequest.operations(op -> op
                            .index(idx -> idx
                                    .index(vocabsIndexName)
                                    .document(vocab)
                            )
                    );
                } catch (JsonProcessingException e) {
                    log.error("Failed to ingest parameterVocabs to {}", vocabsIndexName);
                    throw new RuntimeException(e);
                }
            }

            BulkResponse result = portalElasticsearchClient.bulk(bulkRequest.build());

            // Flush after insert, otherwise you need to wait for next auto-refresh. It is
            // especially a problem with autotest, where assert happens very fast.
            portalElasticsearchClient.indices().refresh();

            // Log errors, if any
            if (result.errors()) {
                log.error("Bulk had errors");
                for (BulkResponseItem item : result.items()) {
                    if (item.error() != null) {
                        log.error("{} {}", item.error().reason(), item.error().causedBy());
                    }
                }
            } else {
                log.info("Finished bulk indexing items to index: {}", vocabsIndexName);
            }
            log.info("Total documents in index: {} is {}", vocabsIndexName, elasticSearchIndexService.getDocumentsCount(vocabsIndexName));
        } else {
            log.error("No vocabs to be indexed, nothing to index");
        }
    }

    /**
     * This method do the population in synchronize way
     *
     * @throws IOException - If something happens, throw to allow client aware of issue.
     */
    @Override
    public void populateVocabsData() throws IOException {
        log.info("Starting fetching vocabs data process synchronously...");

        List<VocabModel> parameterVocabs = ardcVocabService.getARDCVocabByType(ArdcCurrentPaths.PARAMETER_VOCAB);
        List<VocabModel> platformVocabs = ardcVocabService.getARDCVocabByType(ArdcCurrentPaths.PLATFORM_VOCAB);
        List<VocabModel> organisationVocabs = ardcVocabService.getARDCVocabByType(ArdcCurrentPaths.ORGANISATION_VOCAB);

        if (parameterVocabs.isEmpty() || platformVocabs.isEmpty() || organisationVocabs.isEmpty()) {
            throw new IgnoreIndexingVocabsException("One or more vocab lists are empty. Skipping indexing.");
        }

        indexAllVocabs(parameterVocabs, platformVocabs, organisationVocabs);
    }

    /**
     * This method do the population in asynchronized way
     *
     * @param delay - Delay the execution by number of minutes
     */
    @Override
    public CompletableFuture<Void> populateVocabsDataAsync(int delay) {
        log.info("Starting async vocabs data fetching process...");

        List<Callable<List<VocabModel>>> vocabTasks = List.of(
                () -> ardcVocabService.getARDCVocabByType(ArdcCurrentPaths.PARAMETER_VOCAB),
                () -> ardcVocabService.getARDCVocabByType(ArdcCurrentPaths.PLATFORM_VOCAB),
                () -> ardcVocabService.getARDCVocabByType(ArdcCurrentPaths.ORGANISATION_VOCAB)
        );

        return CompletableFuture.runAsync(() -> {
            try {
                log.info("Vocabs data fetching process started in the background.");

                // Invoke all tasks and wait for completion
                List<Future<List<VocabModel>>> completedFutures = executorService.invokeAll(vocabTasks);

                // Ensure all tasks are completed and check for exceptions
                List<List<VocabModel>> allResults = new ArrayList<>();
                for (Future<List<VocabModel>> future : completedFutures) {
                    try {
                        allResults.add(future.get());  // Blocks until the task is completed and retrieves the result
                    } catch (Exception taskException) {
                        log.error("Task failed with an exception", taskException);
                        // Handle failure for this particular task
                        allResults.add(Collections.emptyList()); // add empty result for failed task
                    }
                }

                // Validate allResults to ensure none of the lists are empty
                if (allResults.stream().anyMatch(List::isEmpty)) {
                    throw new IgnoreIndexingVocabsException("One or more vocab tasks returned empty results. Skipping indexing.");
                }

                // Call indexAllVocabs only after all tasks are completed and validated
                log.info("Indexing fetched vocabs to {}", vocabsIndexName);
                indexAllVocabs(allResults.get(0), allResults.get(1), allResults.get(2));
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();  // Restore interrupt status
                log.error("Thread was interrupted while processing vocab tasks", e);
            }
        }, CompletableFuture.delayedExecutor(delay, TimeUnit.MINUTES));
    }

    @PreDestroy
    protected void shutdown() {
        shutdownExecutor(executorService);
    }

    protected void shutdownExecutor(ExecutorService executor) {
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
}
