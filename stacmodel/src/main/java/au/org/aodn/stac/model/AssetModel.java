package au.org.aodn.stac.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssetModel {
    // https://github.com/radiantearth/stac-spec/blob/master/best-practices.md#list-of-asset-roles
    public enum Role {
        DATA("data"),
        SUMMARY("summary");

        private final String role;

        Role(String role) {
            this.role = role;
        }

        @Override
        public String toString() {
            return role;
        }
    }
    /**
     * REQUIRED. URI to the asset object. Relative and absolute URI are both allowed. Trailing slashes are significant.
     */
    protected String href;
    protected String title;
    protected String description;
    protected String type;
    /**
     * The semantic roles of the asset, similar to the use of rel in links.
     */
    protected Role role;
}
