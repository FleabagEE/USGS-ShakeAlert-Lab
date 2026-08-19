import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** One validated contour from the observed version-900 follow-up profile. */
record GroundMotionContour(
    BigDecimal modifiedMercalliIntensity,
    BigDecimal peakGroundAcceleration,
    BigDecimal peakGroundVelocity,
    List<GeoCoordinate> polygon
) {
    GroundMotionContour {
        Objects.requireNonNull(modifiedMercalliIntensity, "modifiedMercalliIntensity");
        Objects.requireNonNull(peakGroundAcceleration, "peakGroundAcceleration");
        Objects.requireNonNull(peakGroundVelocity, "peakGroundVelocity");
        polygon = List.copyOf(Objects.requireNonNull(polygon, "polygon"));
    }
}
