import java.math.BigDecimal;
import java.util.Objects;

/** One immutable finite-fault vertex in source document order. */
record FaultVertex(BigDecimal latitude, BigDecimal longitude, BigDecimal depthKilometers) {
    FaultVertex {
        Objects.requireNonNull(latitude, "latitude");
        Objects.requireNonNull(longitude, "longitude");
        Objects.requireNonNull(depthKilometers, "depthKilometers");
    }
}
