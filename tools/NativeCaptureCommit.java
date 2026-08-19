import java.time.Instant;
import java.util.Objects;

/** Immutable application-owned proof that one native JMS delivery was durably captured. */
final class NativeCaptureCommit {
    private final byte[] payload;
    private final Instant receivedAtUtc;
    private final String captureId;
    private final String captureReference;
    private final String jmsMessageId;
    private final Instant brokerTimestamp;
    private final boolean redelivered;

    NativeCaptureCommit(byte[] payload, Instant receivedAtUtc, String captureId,
            String captureReference, String jmsMessageId, Instant brokerTimestamp,
            boolean redelivered) {
        this.payload = Objects.requireNonNull(payload, "payload").clone();
        this.receivedAtUtc = Objects.requireNonNull(receivedAtUtc, "receivedAtUtc");
        this.captureId = Objects.requireNonNull(captureId, "captureId");
        this.captureReference = Objects.requireNonNull(captureReference, "captureReference");
        this.jmsMessageId = jmsMessageId;
        this.brokerTimestamp = brokerTimestamp;
        this.redelivered = redelivered;
    }

    byte[] payload() { return payload.clone(); }
    Instant receivedAtUtc() { return receivedAtUtc; }
    String captureId() { return captureId; }
    String captureReference() { return captureReference; }
    String jmsMessageId() { return jmsMessageId; }
    Instant brokerTimestamp() { return brokerTimestamp; }
    boolean redelivered() { return redelivered; }
}
