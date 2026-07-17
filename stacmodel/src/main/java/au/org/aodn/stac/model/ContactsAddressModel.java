package au.org.aodn.stac.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Objects;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ContactsAddressModel {
    @JsonProperty("delivery_point")
    protected List<String> deliveryPoint;
    protected String city;
    protected String country;
    @JsonProperty("postal_code")
    protected String postalCode;
    @JsonProperty("administrative_area")
    protected String administrativeArea;

    @JsonIgnore
    public boolean isEmpty() {
        return (deliveryPoint == null || deliveryPoint.isEmpty() || deliveryPoint.stream().allMatch(String::isBlank))
                && (city == null || city.isBlank())
                && (country == null || country.isBlank())
                && (postalCode == null || postalCode.isBlank())
                && (administrativeArea == null || administrativeArea.isBlank());
    }

    @Override
    public int hashCode() {
        return Objects.hash(deliveryPoint, city, country, postalCode, administrativeArea);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ContactsAddressModel that = (ContactsAddressModel) o;
        return Objects.deepEquals(deliveryPoint, that.deliveryPoint)
                && Objects.equals(city, that.city)
                && Objects.equals(country, that.country)
                && Objects.equals(postalCode, that.postalCode)
                && Objects.equals(administrativeArea, that.administrativeArea);
    }
}
