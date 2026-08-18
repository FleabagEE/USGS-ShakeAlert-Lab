import java.util.List;
import java.util.Objects;

/** Narrow immutable model of the finite-fault profile observed in Scenario Event updates. */
record FiniteFault(
    boolean attenuationGeometry,
    int segmentNumber,
    String segmentShape,
    List<FaultSegment> segments
) {
    FiniteFault {
        if (segmentNumber <= 0) throw new IllegalArgumentException("segmentNumber must be positive");
        Objects.requireNonNull(segmentShape, "segmentShape");
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        if (segments.isEmpty()) throw new IllegalArgumentException("segments must not be empty");
    }
}
