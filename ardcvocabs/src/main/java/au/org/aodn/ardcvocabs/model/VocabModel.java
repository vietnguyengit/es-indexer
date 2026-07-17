package au.org.aodn.ardcvocabs.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VocabModel {
    protected String label;
    @JsonProperty("display_label")
    protected String displayLabel;
    @JsonProperty("hidden_labels")
    protected List<String> hiddenLabels;
    @JsonProperty("alt_labels")
    protected List<String> altLabels;
    @JsonProperty("is_latest_label")
    protected Boolean isLatestLabel;
    @JsonProperty("replaced_by")
    protected String replacedBy;
    protected String definition;
    protected String about;
    protected List<VocabModel> narrower;
    protected String version;
}
