package au.org.aodn.ardcvocabs.model;

import lombok.*;

import java.util.List;

/**
 * This is the model class for http://vocabs.ardc.edu.au/repository/api/lda/aodn/aodn-parameter-category-vocabulary/
 */
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CategoryVocabModel {

    protected String label;
    protected String definition;
    protected String about;
    protected List<CategoryVocabModel> broader;
    protected List<CategoryVocabModel> narrower;
}
