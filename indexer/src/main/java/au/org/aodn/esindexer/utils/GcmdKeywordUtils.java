package au.org.aodn.esindexer.utils;

import au.org.aodn.stac.model.ConceptModel;
import au.org.aodn.stac.model.ThemesModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.util.*;

import static au.org.aodn.esindexer.utils.CommonUtils.safeGet;


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


    // Load the CSV file into a HashMap
    private void loadCsvToMap(String path) {
        try {
            log.info("Loading GCMD mapping contents from CSV resource: {}", path);

            // Read the file content using Apache Commons CSV
            Resource resource = new ClassPathResource(path);
            try (InputStream inputStream = resource.getInputStream();
                 Reader reader = new InputStreamReader(inputStream);
                 CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT)) {

                for (CSVRecord record : csvParser) {
                    if (record.size() >= 2) { // Ensure at least key-value pairs exist
                        String key = record.get(0).trim();
                        String value = record.get(1).trim();
                        gcmdMapping.put(key, value);
                    }
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
            for (var concept : themesModel.getConcepts()) {

                if (concept.getId() == null || concept.getId().isEmpty()) {
                    continue;
                }
                if (concept.getTitle() == null || concept.getTitle().isEmpty()) {
                    continue;
                }

                var lowerCaseTitle = concept.getTitle().toLowerCase();
                // skip concepts that contain "palaeo temporal coverage"
                if (lowerCaseTitle.contains("palaeo temporal coverage")) {
                    continue;
                }

                if (lowerCaseTitle.contains("global change master directory") || lowerCaseTitle.contains("gcmd")) {
                    keywords.add(getLastWord(concept.getId().replace("\"", "")).toUpperCase());
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
