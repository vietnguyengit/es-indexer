package au.org.aodn.esindexer.utils;

import au.org.aodn.esindexer.service.VocabService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;


@Slf4j
public class VocabsIndexUtils {
    @Value("${elasticsearch.vocabs_index.name}")
    String vocabsIndexName;

    @Autowired
    VocabService vocabService;

    @PostConstruct
    public void init() throws IOException {
        log.info("Initialising {}", vocabsIndexName);
        vocabService.populateVocabsData();
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void scheduledRefreshVocabsData() throws IOException {
        log.info("Refreshing ARDC vocabularies data");
        // clear existing caches
        vocabService.clearParameterVocabsCache();
        vocabService.clearPlatformVocabsCache();
        vocabService.clearOrganisationVocabsCache();
        // populate latest vocabs
        vocabService.populateVocabsData();
        // update the caches
        vocabService.getParameterVocabsFromEs();
        vocabService.getPlatformVocabsFromEs();
        vocabService.getOrganisationVocabsFromEs();
    }
}
