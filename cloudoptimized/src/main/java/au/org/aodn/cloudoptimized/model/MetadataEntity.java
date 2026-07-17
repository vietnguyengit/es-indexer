package au.org.aodn.cloudoptimized.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetadataEntity {

    public static class Depth {
        @JsonProperty("max")
        double max;

        @JsonProperty("min")
        double min;

        @JsonProperty("unit")
        String unit;
    }

    @JsonProperty("depth")
    protected Depth depth;

    @JsonProperty("dname")
    protected String dname;

    @JsonProperty("uuid")
    protected String uuid;
}
