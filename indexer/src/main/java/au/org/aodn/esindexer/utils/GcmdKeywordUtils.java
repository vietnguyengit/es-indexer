package au.org.aodn.esindexer.utils;

import au.org.aodn.stac.model.ConceptModel;
import au.org.aodn.stac.model.ThemesModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.ResourceUtils;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@Component
public class GcmdKeywordUtils {

    protected Map<String, String> gcmdMapping = new HashMap<>();

    @PostConstruct
    public void init() {
        loadCsvToMap("config_files/gcmd-mapping.csv");
    }

    private String getLastWord(String keyword) {
        String result;
        if (keyword.contains("|")) {
            result = keyword.substring(keyword.lastIndexOf("|") + 1).strip();
        } else if (keyword.contains(">")) {
            result = keyword.substring(keyword.lastIndexOf(">") + 1).strip();
        } else {
            result = keyword.strip();
        }
        return result;
    }


    private static String readResourceFile(String path) throws IOException {
        Resource resource = new ClassPathResource(path);
        InputStream fStream = resource.getInputStream();
        try ( BufferedReader reader = new BufferedReader(
                new InputStreamReader(fStream)) ) {
            return reader.lines()
                    .collect(Collectors.joining("\n"));
        }
    }

    // Load the CSV file into a HashMap
    private void loadCsvToMap(String path) {
        try {

            log.info("Loading GCMD mapping contents from CSV resource: {}", path);

            // Read the file as a single String
            String fileContent = readResourceFile(path);

            // Split the content into lines
            String[] lines = fileContent.split("\\r?\\n");

            // Process each line
            for (String line : lines) {
                // Split the line into key and value based on comma
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    gcmdMapping.put(key, value);
                }
            }

            log.info("Successfully loaded GCMD mapping contents from CSV resource: {}", path);
        } catch (IOException e) {
            log.error("Error while loading GCMD mapping contents from CSV resource: {}", path, e);
        }
    }

    protected List<String> extractGcmdKeywordLastWords(List<ThemesModel> themes) {
        log.info("Extracting GCMD keywords from record's themes");
        Set<String> keywords = new HashSet<>();
        for (ThemesModel themesModel : themes) {
            if ((themesModel.getTitle().toLowerCase().contains("gcmd") || themesModel.getTitle().toLowerCase().contains("global change master directory")) && !themesModel.getTitle().toLowerCase().contains("palaeo temporal coverage")) {
                for (ConceptModel conceptModel : themesModel.getConcepts()) {
                    if (conceptModel.getId() != null && !conceptModel.getId().isEmpty()) {
                        keywords.add(getLastWord(conceptModel.getId().replace("\"", "")).toUpperCase());
                    }
                }
            }
        }
        return new ArrayList<>(keywords);
    }

    protected String getParameterVocabByGcmdKeywordLastWord(String gcmdKeywordLastWord) {
        return gcmdMapping.getOrDefault(gcmdKeywordLastWord, "");
    }

    public List<String> getMappedParameterVocabsFromGcmdKeywords(List<ThemesModel> themes) {
        Set<String> results = new HashSet<>();

        log.info("Get parameter vocabs from record's GCMD keywords");

        List<String> gcmdKeywordLastWords = extractGcmdKeywordLastWords(themes);

        if (!gcmdKeywordLastWords.isEmpty()) {
            for (String gcmdKeywordLastWord : gcmdKeywordLastWords) {
                String mappedParameterVocab = getParameterVocabByGcmdKeywordLastWord(gcmdKeywordLastWord);
                if (!mappedParameterVocab.isEmpty() && !mappedParameterVocab.equalsIgnoreCase("uncategorised")) {
                    results.add(mappedParameterVocab.toLowerCase());
                }
            }
        }

        return new ArrayList<>(results);
    }
}
