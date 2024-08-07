package au.org.aodn.esindexer.model;

import lombok.Getter;

@Getter
public enum MediaType {
    TEXT_HTML("text/html"),
    IMAGE_PNG("image/png"),
    APPLICATION_JSON("application/json"),
    ;

    private final String value;

    MediaType(String value) {
        this.value = value;
    }
}
