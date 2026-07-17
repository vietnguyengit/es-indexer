package au.org.aodn.ardcvocabs.model;

import lombok.Getter;

@Getter
public enum ArdcCurrentPaths {
    PARAMETER_VOCAB(VocabApiPaths.PARAMETER_VOCAB),
    PLATFORM_VOCAB(VocabApiPaths.PLATFORM_VOCAB),
    ORGANISATION_VOCAB(VocabApiPaths.ORGANISATION_VOCAB);

    private final VocabApiPaths vocabApiPaths;
    private final String categoryCurrent;
    private final String vocabCurrent;

    ArdcCurrentPaths(VocabApiPaths vocabApiPaths) {
        String baseUrl = "https://vocabs.ardc.edu.au/repository/api/lda/aodn";
        this.vocabApiPaths = vocabApiPaths;
        this.categoryCurrent = baseUrl + String.format(vocabApiPaths.getCategoryApiTemplate(), "current");
        this.vocabCurrent = baseUrl + String.format(vocabApiPaths.getVocabApiTemplate(), "current");
    }
}
