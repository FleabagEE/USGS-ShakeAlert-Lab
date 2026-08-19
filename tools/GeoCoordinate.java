import java.math.BigDecimal;
import java.util.Objects;

/** Immutable latitude/longitude coordinate in source order. */
record GeoCoordinate(BigDecimal latitude, BigDecimal longitude) {
    GeoCoordinate {
        Objects.requireNonNull(latitude, "latitude");
        Objects.requireNonNull(longitude, "longitude");
    }
}
