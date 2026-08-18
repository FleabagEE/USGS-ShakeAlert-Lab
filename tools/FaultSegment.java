import java.util.List;
import java.util.Objects;

/** One immutable finite-fault segment in source document order. */
record FaultSegment(List<FaultVertex> vertices) {
    FaultSegment {
        vertices = List.copyOf(Objects.requireNonNull(vertices, "vertices"));
        if (vertices.isEmpty()) throw new IllegalArgumentException("vertices must not be empty");
    }
}
