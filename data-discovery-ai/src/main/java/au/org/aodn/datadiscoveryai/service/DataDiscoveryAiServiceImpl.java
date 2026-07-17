package au.org.aodn.datadiscoveryai.service;

import au.org.aodn.datadiscoveryai.enums.AIModel;
import au.org.aodn.datadiscoveryai.enums.AiEnhancementSummaryField;
import au.org.aodn.datadiscoveryai.model.AiEnhancedLink;
import au.org.aodn.datadiscoveryai.model.AiEnhancementRequest;
import au.org.aodn.datadiscoveryai.model.AiEnhancementResponse;
import au.org.aodn.stac.model.ConceptModel;
import au.org.aodn.stac.model.LinkModel;
import au.org.aodn.stac.model.ThemesModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;


@Slf4j
public class DataDiscoveryAiServiceImpl implements DataDiscoveryAiService {

    private final String serviceUrl;
    private final String baseUrl;
    private final RestTemplate restTemplate;
    protected final WebClient webClient;
    protected final ObjectMapper objectMapper;

    // vocab titles used for deciding to call AI keyword classification or not
    private static final String AODN_DISCOVERY_PARAMETER_VOCABULARY = "aodn discovery parameter vocabulary";
    private static final String AODN_PLATFORM_VOCABULARY = "aodn platform vocabulary";
    private static final String GCMD_FULL_VOCABULARY = "global change master directory";
    private static final String GCMD_SHORT_VOCABULARY = "gcmd";

    public DataDiscoveryAiServiceImpl(String serviceUrl, String baseUrl,
                                      RestTemplate restTemplate, WebClient webClient, ObjectMapper objectMapper) {
        this.serviceUrl = serviceUrl;
        this.baseUrl = baseUrl;
        this.restTemplate = restTemplate;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }



    @Override
    public AiEnhancementResponse enhanceWithAi(AiEnhancementRequest aiEnhancementRequest) {
        List<LinkModel> links = aiEnhancementRequest.getLinks();
        String title = aiEnhancementRequest.getTitle();
        String description = aiEnhancementRequest.getAbstractText();
        String uuid = aiEnhancementRequest.getUuid();
        List<ThemesModel> themes = aiEnhancementRequest.getThemes();

        List<String> selectedModels = new ArrayList<>();

        // Add models based on provided parameters
        if (links != null && !links.isEmpty()) {
            selectedModels.add(AIModel.LINK_GROUPING.getValue());
        }
        if ((title != null && !title.isEmpty()) || (description != null && !description.isEmpty())) {
            selectedModels.add(AIModel.DESCRIPTION_FORMATTING.getValue());
            // lineage can be empty for records that need to identify delivery mode so we only need to check title and description are not empty
            selectedModels.add(AIModel.DELIVERY_CLASSIFICATION.getValue());

            // check original themes to see if vocab missing
            if (shouldCallAiEnhancementThemes(themes)) {
                log.debug("Record missing required parameter/platform concepts, need AI keyword classification");
                selectedModels.add(AIModel.KEYWORD_CLASSIFICATION.getValue());
            }
        }

        if (selectedModels.isEmpty()) {
            return null;
        }

        aiEnhancementRequest.setSelectedModel(selectedModels);

        try {
            String url = serviceUrl + baseUrl;

            Flux<ServerSentEvent<String>> eventStream = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(aiEnhancementRequest)
                    .retrieve()
                    .onStatus(
                        status -> status.isError(),
                        response -> response.bodyToMono(String.class)
                                .map(body -> new RuntimeException("API error: " + response.statusCode() + " - " + body))
                    )
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});

            ServerSentEvent<String> doneEvent = eventStream
                    .doOnNext(event -> {
                        if ("error".equals(event.event())) {
                            log.error("Failed to call Data Discovery AI service: {}", event.data());
                        } else if ("processing".equals(event.event())) {
                            log.info("Data Discovery AI service processing...");
                        }
                    })
                    .filter(event -> "done".equals(event.event()))
                    .blockFirst();

            if (doneEvent != null && doneEvent.data() != null) {
                log.info("Successfully calling Data Discovery AI service for UUID: {} with {} models", uuid, selectedModels.size());
                return objectMapper.readValue(doneEvent.data(), AiEnhancementResponse.class);
            } else {
                log.warn("Received non-successful response from AI service: Processing not completed.");
                return null;
            }

        } catch (HttpClientErrorException e) {
            log.error("Client error when calling Data Discovery AI service for UUID: {} - Status: {}, Response: {}",
                    uuid, e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (RestClientException e) {
            log.error("Error calling Data Discovery AI service for UUID: {}", uuid, e);
            return null;
        } catch (Exception e) {
            log.error("Unexpected error when enhancing content for UUID: {}", uuid, e);
            return null;
        }
    }

    @Override
    public boolean isServiceAvailable() {
        try {
            String healthUrl = serviceUrl + "/api/v1/ml/health";
            ResponseEntity<String> response = restTemplate.getForEntity(healthUrl, String.class);

            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("Data Discovery AI service health check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<LinkModel> getEnhancedLinks(AiEnhancementResponse aiResponse) {
        if (aiResponse != null && aiResponse.getLinks() != null) {
            return convertAiEnhancedLinksToLinkModels(aiResponse.getLinks());
        }
        return null;
    }

    @Override
    public String getEnhancedDescription(AiEnhancementResponse aiResponse) {
        return getSummaryField(aiResponse, AiEnhancementSummaryField.AI_DESCRIPTION);
    }

    @Override
    public String getEnhancedUpdateFrequency(AiEnhancementResponse aiResponse) {
        return getSummaryField(aiResponse, AiEnhancementSummaryField.AI_UPDATE_FREQUENCY);
    }

    private String getSummaryField(AiEnhancementResponse aiResponse, AiEnhancementSummaryField field) {
        if (aiResponse != null && aiResponse.getSummaries() != null
                && aiResponse.getSummaries().containsKey(field.getFieldName())) {
            return Optional.ofNullable(aiResponse.getSummaries().get(field.getFieldName()))
                    .map(JsonNode::asText)
                    .orElse(null);
        }
        return null;
    }

    @Override
    public List<ThemesModel> getEnhancedThemes(AiEnhancementResponse aiResponse) {
        if (aiResponse != null && aiResponse.getThemes() != null) {
            // Convert each JsonNode into ThemesModel manually
            return aiResponse.getThemes().stream()
                    .map(node -> {
                        List<ConceptModel> concepts = new ArrayList<>();
                        // Iterate over concepts array in the JSON node
                        if (node.has("concepts")) {
                            node.get("concepts").forEach(conceptNode -> {
                                concepts.add(ConceptModel.builder()
                                        .id(conceptNode.has("id") ? conceptNode.get("id").asText() : null)
                                        .url(conceptNode.has("url") ? conceptNode.get("url").asText() : null)
                                        .title(conceptNode.has("title") ? conceptNode.get("title").asText() : null)
                                        .description(conceptNode.has("description") ? conceptNode.get("description").asText() : null)
                                        .aiDescription(conceptNode.has("ai:description") ? conceptNode.get("ai:description").asText() : null)
                                        .build());
                            });
                        }
                        // Build ThemesModel
                        return ThemesModel.builder()
                                .scheme(node.has("scheme") ? node.get("scheme").asText() : null)
                                .concepts(concepts)
                                .build();
                    })
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public List<LinkModel> convertAiEnhancedLinksToLinkModels(List<AiEnhancedLink> aiEnhancedLinks) {
        if (aiEnhancedLinks == null) {
            return List.of();
        }

        return aiEnhancedLinks.stream()
                .map(aiLink -> LinkModel.builder()
                        .href(aiLink.getHref())
                        .rel(aiLink.getRel())
                        .type(aiLink.getType())
                        .title(aiLink.getTitle())
                        .aiGroup(aiLink.getAiGroup())
                        .aiRole(aiLink.getAiRole())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public Boolean shouldCallAiEnhancementThemes(List<ThemesModel> originalThemes) {
        if (originalThemes == null || originalThemes.isEmpty()) {
            return false;
        }
        // return true only if no parameter nor platform nor GCMD vocab concepts exist in original themes
        return originalThemes.stream()
                .flatMap(theme -> theme.getConcepts().stream())
                .noneMatch(concept ->
                        concept.getTitle() != null && (
                                concept.getTitle().toLowerCase().contains(AODN_DISCOVERY_PARAMETER_VOCABULARY) ||
                                        concept.getTitle().toLowerCase().contains(AODN_PLATFORM_VOCABULARY) ||
                                        concept.getTitle().toLowerCase().contains(GCMD_FULL_VOCABULARY) ||
                                        concept.getTitle().toLowerCase().contains(GCMD_SHORT_VOCABULARY)
                        )
                );
    }
}
