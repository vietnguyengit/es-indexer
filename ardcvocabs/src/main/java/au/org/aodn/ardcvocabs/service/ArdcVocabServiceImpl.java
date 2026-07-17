package au.org.aodn.ardcvocabs.service;

import au.org.aodn.ardcvocabs.exception.ExtractingPathVersionsException;
import au.org.aodn.ardcvocabs.exception.InvalidVersionFormatException;
import au.org.aodn.ardcvocabs.model.ArdcCurrentPaths;
import au.org.aodn.ardcvocabs.model.Name;
import au.org.aodn.ardcvocabs.model.VocabApiPaths;
import au.org.aodn.ardcvocabs.model.VocabModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.StreamSupport;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class ArdcVocabServiceImpl implements ArdcVocabService {

    @Value("${ardcvocabs.baseUrl:https://vocabs.ardc.edu.au/repository/api/lda/aodn}")
    protected String vocabApiBase;

    protected RestTemplate restTemplate;
    protected RetryTemplate retryTemplate;

    protected static final String VERSION_REGEX = "/(version-\\d+-\\d+)(?:/|$)";

    protected Map<Name, String> getVersionedArdcPath(ArdcCurrentPaths currentPath) {
        try {
            // Fetch current contents
            ObjectNode categoryCurrentContent = fetchCurrentContents(currentPath.getCategoryCurrent());
            ObjectNode vocabCurrentContent = fetchCurrentContents(currentPath.getVocabCurrent());
            if (categoryCurrentContent == null || vocabCurrentContent == null) {
                throw new ExtractingPathVersionsException(String.format("Failed to fetch HTML content for %s", currentPath.name()));
            }

            // Extract versions
            String categoryVersion = extractVersionFromCurrentContent(categoryCurrentContent);
            String vocabVersion = extractVersionFromCurrentContent(vocabCurrentContent);
            if (categoryVersion == null || vocabVersion == null) {
                throw new ExtractingPathVersionsException(String.format("Version extraction returned null for %s", currentPath.name()));
            }

            log.info("Fetched ARDC category version for {}: {}", currentPath.name(), categoryVersion);
            log.info("Fetched ARDC vocab version for {}: {}", currentPath.name(), vocabVersion);

            // Build and store resolved paths
            return buildResolvedPaths(currentPath, categoryVersion, vocabVersion);

        } catch (Exception e) {
            log.error("Error initialising versions for {}: {}", currentPath.name(), e.getMessage(), e);
            throw new ExtractingPathVersionsException(String.format("Error initialising versions for %s: %s", currentPath.name(), e.getMessage()));
        }
    }

    protected ObjectNode fetchCurrentContents(String url) {
        try {
            return retryTemplate.execute(context -> restTemplate.getForObject(url, ObjectNode.class));
        }
        catch (RestClientException e) {
            log.error("Failed to fetch HTML content from URL {}: {}", url, e.getMessage());
            throw e;
        }
        catch (Exception e) {
            log.error("Unexpected error while fetching HTML content from URL {}: {}", url, e.getMessage(), e);
            throw e;
        }
    }

    protected Map<Name, String> buildResolvedPaths(ArdcCurrentPaths currentPaths, String categoryVersion, String vocabVersion) {
        VocabApiPaths vocabApiPath = currentPaths.getVocabApiPaths();
        Map<Name, String> resolvedPaths = new HashMap<>();
        resolvedPaths.put(Name.version, categoryVersion + "/" + vocabVersion);
        resolvedPaths.put(Name.categoryApi, String.format(vocabApiPath.getCategoryApiTemplate(), categoryVersion));
        resolvedPaths.put(Name.categoryDetailsApi, String.format(vocabApiPath.getCategoryDetailsTemplate(), categoryVersion, "%s"));
        resolvedPaths.put(Name.vocabApi, String.format(vocabApiPath.getVocabApiTemplate(), vocabVersion));
        resolvedPaths.put(Name.vocabDetailsApi, String.format(vocabApiPath.getVocabDetailsTemplate(), vocabVersion, "%s"));
        return resolvedPaths;
    }

    protected String extractVersionFromCurrentContent(ObjectNode currentContent) {
        if (currentContent != null && !currentContent.isEmpty()) {
            JsonNode node = currentContent.get("result");
            if (!about.apply(node).isEmpty()) {
                Pattern pattern = Pattern.compile(VERSION_REGEX);
                Matcher matcher = pattern.matcher(about.apply(node));

                if (matcher.find()) {
                    String version = matcher.group(1);
                    log.info("Valid Version Found: {}", version);
                    return version;
                } else {
                    throw new InvalidVersionFormatException(String.format("Version does not match the required format: %s", about.apply(node)));
                }
            }
        } else {
            log.warn("Current content is empty or null.");
        }
        return null;
    }

    protected Function<JsonNode, String> extractSingleText(String key) {
        return (node) -> {
            JsonNode labelNode = node.get(key);
            if (labelNode != null) {
                if (labelNode.has("_value")) {
                    return labelNode.get("_value").asText();
                }
                if (labelNode instanceof TextNode) {
                    return labelNode.asText();
                }
            }
            return null;
        };
    }
    protected Function<JsonNode, List<String>> extractMultipleTexts(String key) {
        return (node) -> {
            JsonNode labelNode = node.get(key);
            if (labelNode != null && labelNode.isArray()) {
                return StreamSupport.stream(labelNode.spliterator(), false)
                        .filter(Objects::nonNull)
                        .map(i -> i.get("_value").asText())
                        .collect(Collectors.toList());
            }
            return null;
        };
    }

    // Reusing the utility methods for specific labels
    protected Function<JsonNode, String> label = extractSingleText("prefLabel");
    protected Function<JsonNode, String> displayLabel = extractSingleText("displayLabel");
    protected Function<JsonNode, List<String>> hiddenLabels = extractMultipleTexts("hiddenLabel");
    protected Function<JsonNode, List<String>> altLabels = extractMultipleTexts("altLabel");
    protected Function<JsonNode, String> about = extractSingleText("_about");
    protected Function<JsonNode, String> definition = extractSingleText("definition");
    protected Function<JsonNode, Boolean> isLatestLabel = (node) -> !(node.has("isReplacedBy") || (node.has("scopeNote") && extractSingleText("scopeNote").apply(node).toLowerCase().contains("no longer exists")));
    protected Function<JsonNode, Boolean> isReplacedBy = (node) -> node.has("isReplacedBy") && node.has("scopeNote") && extractSingleText("scopeNote").apply(node).toLowerCase().contains("replaced by");

    private String extractReplacedVocabUri(String scopeNote) {
        String regex = "Replaced by (https?://[\\w./-]+)";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(scopeNote);

        if (matcher.find()) {
            String result = matcher.group(1);
            if (result.endsWith(".")) {
                result = result.substring(0, result.length() - 1);
            }
            return result;
        }

        return null;
    }

    protected BiFunction<JsonNode, String, Boolean> isNodeValid = (node, item) -> node != null && !node.isEmpty() && node.has(item) && !node.get(item).isEmpty();

    public ArdcVocabServiceImpl(RestTemplate restTemplate, RetryTemplate retryTemplate) {
        this.restTemplate = restTemplate;
        this.retryTemplate = retryTemplate;
    }

    protected VocabModel buildVocabByResourceUri(String vocabUri, String vocabApiBase, Map<Name, String> pointers) {
        String resourceDetailsApi = vocabUri.contains("_classes")
                ? pointers.get(Name.categoryDetailsApi)
                : pointers.get(Name.vocabDetailsApi);

        String detailsUrl = String.format(vocabApiBase + resourceDetailsApi, vocabUri);

        try {
            log.debug("Query api -> {}", detailsUrl);
            ObjectNode detailsObj = retryTemplate.execute(context -> restTemplate.getForObject(detailsUrl, ObjectNode.class));
            if(isNodeValid.apply(detailsObj, "result") && isNodeValid.apply(detailsObj.get("result"), "primaryTopic")) {
                JsonNode target = detailsObj.get("result").get("primaryTopic");

                VocabModel vocab = VocabModel
                        .builder()
                        .label(label.apply(target))
                        .definition(definition.apply(target))
                        .about(vocabUri)
                        .version(pointers.get(Name.version))
                        .displayLabel(displayLabel.apply(target))
                        .hiddenLabels(hiddenLabels.apply(target))
                        .altLabels(altLabels.apply(target))
                        .isLatestLabel(isLatestLabel.apply(target))
                        .build();

                if (!vocab.getIsLatestLabel() && isReplacedBy.apply(target)) {
                    vocab.setReplacedBy(extractReplacedVocabUri(extractSingleText("scopeNote").apply(target)));
                }

                List<VocabModel> narrowerNodes = new ArrayList<>();
                if (isNodeValid.apply(target, "narrower")) {
                    for (JsonNode j : target.get("narrower")) {
                        if (!about.apply(j).isEmpty()) {
                            // recursive call
                            VocabModel narrowerNode = buildVocabByResourceUri(about.apply(j), vocabApiBase, pointers);
                            if (narrowerNode != null) {
                                narrowerNodes.add(narrowerNode);
                            }
                        }
                    }
                }

                if (!narrowerNodes.isEmpty()) {
                    vocab.setNarrower(narrowerNodes);
                }

                return vocab;
            }
        } catch(Exception e) {
            log.warn("Item not found in resource, check with person who maintain the vocabs {}", detailsUrl);
        }
        return null;
    }

    protected <T> VocabModel buildVocabModel(T currentNode, String vocabApiBase, Map<Name, String> pointers) {
        String resourceUri = null;

        if (currentNode instanceof ObjectNode objectNode) {
            resourceUri = objectNode.has("_about") ? about.apply(objectNode) : objectNode.asText();
        }
        else if (currentNode instanceof TextNode textNode) {
            resourceUri = textNode.asText();
        }
        else if (currentNode instanceof VocabModel vocabNode) {
            String about = vocabNode.getAbout();
            if (about != null && !about.isEmpty()) {
                resourceUri = about;
            }
        }

        if (resourceUri == null) {
            throw new IllegalArgumentException("Unsupported node type: " + currentNode.getClass().getName());
        }

        return buildVocabByResourceUri(resourceUri, vocabApiBase, pointers);
    }

    protected Map<String, List<VocabModel>> getVocabLeafNodes(String vocabApiBase, Map<Name, String> pointers) {
        Map<String, List<VocabModel>> results = new HashMap<>();
        String url = String.format(vocabApiBase + pointers.get(Name.vocabApi));

        while (url != null && !url.isEmpty()) {
            try {
                log.debug("getVocabLeafNodes -> {}", url);
                String finalUrl = url;
                ObjectNode r = retryTemplate.execute(context -> restTemplate.getForObject(finalUrl, ObjectNode.class));

                if (r != null && !r.isEmpty()) {
                    JsonNode node = r.get("result");

                    if (isNodeValid.apply(node, "items")) {
                        for (JsonNode j : node.get("items")) {
                            // Now we need to construct link to detail resources
                            String dl = String.format(vocabApiBase + pointers.get(Name.vocabDetailsApi), about.apply(j));
                            try {
                                log.debug("getVocabLeafNodes -> {}", dl);
                                ObjectNode d = retryTemplate.execute(context -> restTemplate.getForObject(dl, ObjectNode.class));
                                if(isNodeValid.apply(d, "result") && isNodeValid.apply(d.get("result"), "primaryTopic")) {
                                    JsonNode target = d.get("result").get("primaryTopic");

                                    VocabModel vocab = VocabModel
                                            .builder()
                                            .label(label.apply(target))
                                            .definition(definition.apply(target))
                                            .about(about.apply(target))
                                            .version(pointers.get(Name.version))
                                            .displayLabel(displayLabel.apply(target))
                                            .hiddenLabels(hiddenLabels.apply(target))
                                            .altLabels(altLabels.apply(target))
                                            .isLatestLabel(isLatestLabel.apply(target))
                                            .build();

                                    if (!vocab.getIsLatestLabel() && isReplacedBy.apply(target)) {
                                        vocab.setReplacedBy(extractReplacedVocabUri(extractSingleText("scopeNote").apply(target)));
                                    }

                                    List<VocabModel> vocabNarrower = new ArrayList<>();
                                    if(target.has("narrower") && !target.get("narrower").isEmpty()) {
                                        for(JsonNode currentNode : target.get("narrower")) {
                                            VocabModel narrowerNode = buildVocabModel(currentNode, vocabApiBase, pointers);
                                            if (narrowerNode != null) {
                                                vocabNarrower.add(narrowerNode);
                                            }
                                        }
                                    }
                                    if (!vocabNarrower.isEmpty()) {
                                        vocab.setNarrower(vocabNarrower);
                                    }

                                    if (target.has("broadMatch") && !target.get("broadMatch").isEmpty()) {
                                        for(JsonNode bm : target.get("broadMatch")) {
                                            results.computeIfAbsent(bm.asText(), k -> new ArrayList<>()).add(vocab);
                                        }
                                    }

                                    if (!target.has("broadMatch") && target.has("relatedMatch") && !target.get("relatedMatch").isEmpty()) {
                                        // when the conditions above are true, a vocab doesn't have root node (top-level), it is headless, and it becomes a head node (root node)
                                        // sample: http://vocab.aodn.org.au/def/organisation/entity/133
                                        // each of the vocab's narrower nodes (leaf nodes) now becomes currentInternalNode (second-level)
                                        // they are all, basically jump 1 level up in the tree structure.
                                        if (vocab.getNarrower() != null && !vocab.getNarrower().isEmpty()) {
                                            List<VocabModel> completedInternalNodes = new ArrayList<>();
                                            vocab.getNarrower().forEach(currentInternalNode -> {
                                                // rebuild currentInternalNode (no linked leaf nodes) to completedInternalNode (with linked leaf nodes)
                                                VocabModel completedInternalNode = buildVocabModel(currentInternalNode, vocabApiBase, pointers);
                                                if (completedInternalNode != null) {
                                                    // each internal node now will have linked narrower nodes (if available)
                                                    completedInternalNodes.add(completedInternalNode);
                                                }
                                            });
                                            // update the vocab with completed internal nodes ad their associating leaf nodes.
                                            vocab.setNarrower(completedInternalNodes);
                                        }
                                        results.computeIfAbsent("headlessNodes", k -> new ArrayList<>()).add(vocab);
                                    }
                                }
                            }
                            catch(Exception e) {
                                log.error("Item not found in resource {}", dl);
                            }
                        }
                    }

                    if (!node.isEmpty() && node.has("next")) {
                        url = node.get("next").asText();
                    }
                    else {
                        url = null;
                    }
                }
                else {
                    url = null;
                }
            } catch (RestClientException e) {
                log.error("Fail connect {}, vocab return likely outdated", url);
                url = null;
            }
        }

        return results;
    }

    @Override
    public boolean isVersionEquals(ArdcCurrentPaths path, String version) {
        try {
            Map<Name, String> versioned = this.getVersionedArdcPath(path);
            return versioned.get(Name.version).equals(version);
        }
        catch(ExtractingPathVersionsException ex) {
            // If we fail to extract assume the cache have the same version
            // and continue startup
            log.warn("ARDC server not available, assume Elastic have the latest version");
            return true;
        }
    }

    @Override
    public List<VocabModel> getARDCVocabByType(ArdcCurrentPaths path) {
        Map<Name, String> versioned = this.getVersionedArdcPath(path);

        Map<String, List<VocabModel>> vocabLeafNodes = getVocabLeafNodes(vocabApiBase, versioned);
        String url = String.format(vocabApiBase + versioned.get(Name.categoryApi));
        List<VocabModel> vocabCategoryNodes = new ArrayList<>();
        while (url != null && !url.isEmpty()) {
            try {
                final String finalUrl = url;
                ObjectNode r = retryTemplate.execute(context -> restTemplate.getForObject(finalUrl, ObjectNode.class));
                if (r != null && !r.isEmpty()) {
                    JsonNode node = r.get("result");
                    if (!node.isEmpty() && node.has("items") && !node.get("items").isEmpty()) {
                        for (JsonNode j : node.get("items")) {
                            String labelValue = label.apply(j);
                            String definitionValue = definition.apply(j);
                            String aboutValue = about.apply(j);

                            if (aboutValue != null && !aboutValue.isEmpty() && labelValue != null && !labelValue.isEmpty()) {

                                log.debug("Processing label {}", labelValue);
                                VocabModel vocabCategoryNode = VocabModel.builder()
                                        .label(labelValue)
                                        .definition(definitionValue)
                                        .about(aboutValue)
                                        .version(versioned.get(Name.version))
                                        .build();

                                // process internal nodes of vocab category
                                Map<String, List<VocabModel>> internalVocabCategoryNodes = new HashMap<>();
                                if (j.has("narrower") && !j.get("narrower").isEmpty()) {
                                    j.get("narrower").forEach(currentNode -> {
                                        VocabModel internalNode = buildVocabModel(currentNode, vocabApiBase, versioned);
                                        if (internalNode != null) {
                                            List<VocabModel> leafNodes = vocabLeafNodes.getOrDefault(internalNode.getAbout(), Collections.emptyList());
                                            if (!leafNodes.isEmpty()) {
                                                internalNode.setNarrower(leafNodes);
                                            }
                                            // vocabCategoryNode.getAbout() as key because vocabCategoryNode is an upper level node of narrowerNode
                                            internalVocabCategoryNodes.computeIfAbsent(vocabCategoryNode.getAbout(), k -> new ArrayList<>()).add(internalNode);
                                        }
                                    });
                                }

                                // process root nodes of vocab category
                                if (!j.has("broader")) {
                                    List<VocabModel> leafNodes = vocabLeafNodes.getOrDefault(aboutValue, Collections.emptyList());
                                    List<VocabModel> internalNodes = internalVocabCategoryNodes.getOrDefault(aboutValue, Collections.emptyList());

                                    List<VocabModel> allNarrowerNodes = new ArrayList<>();
                                    if (!leafNodes.isEmpty()) {
                                        allNarrowerNodes.addAll(leafNodes);
                                    }
                                    if (!internalNodes.isEmpty()) {
                                        allNarrowerNodes.addAll(internalNodes);
                                    }
                                    if (!allNarrowerNodes.isEmpty()) {
                                        vocabCategoryNode.setNarrower(allNarrowerNodes);
                                    }

                                    // the final returning results will just be root nodes
                                    vocabCategoryNodes.add(vocabCategoryNode);
                                }
                            }
                        }
                    }

                    if (!node.isEmpty() && node.has("next")) {
                        url = node.get("next").asText();
                    } else {
                        url = null;
                    }
                }
                else {
                    url = null;
                }
            } catch (RestClientException e) {
                log.error("Fail connect {}, parameter vocab return likely outdated", url);
                url = null;
            }
        }

        List<VocabModel> headlessNodes = vocabLeafNodes.getOrDefault("headlessNodes", Collections.emptyList());
        if (!headlessNodes.isEmpty()) {
            vocabCategoryNodes.addAll(headlessNodes);
        }

        return vocabCategoryNodes;
    }
}
