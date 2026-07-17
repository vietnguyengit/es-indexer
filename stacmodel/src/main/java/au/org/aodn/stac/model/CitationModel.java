package au.org.aodn.stac.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitationModel {

    private String suggestedCitation;
    private List<String> useLimitations;
    private List<String> otherConstraints;

    public void addUseLimitation(String useLimitation) {
        if (this.useLimitations == null) {
            this.useLimitations = new ArrayList<>();
        }
        this.useLimitations.add(useLimitation);
    }

    public void addOtherConstraint(String otherConstraint) {
        if (this.otherConstraints == null) {
            this.otherConstraints = new ArrayList<>();
        }
        this.otherConstraints.add(otherConstraint);
    }
}
