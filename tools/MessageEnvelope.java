import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable application-owned representation of one durably captured delivery. */
final class MessageEnvelope {
    private static final java.util.Set<String> ALLOWED_METADATA =
        java.util.Set.of("protocol", "protocol_version");
    private final byte[] payload;
    private final int payloadByteCount;
    private final String payloadSha256;
    private final Instant receivedAtUtc;
    private final String captureId;
    private final String captureReference;
    private final String sourceEnvironment;
    private final String endpointIdentity;
    private final String exactDestination;
    private final String accountIdentity;
    private final String jmsMessageId;
    private final Instant brokerTimestamp;
    private final boolean redelivered;
    private final Map<String, String> metadata;
    private final long activationGeneration;

    MessageEnvelope(
        byte[] payload,
        Instant receivedAtUtc,
        String captureId,
        String captureReference,
        String sourceEnvironment,
        String endpointIdentity,
        String exactDestination,
        String accountIdentity,
        String jmsMessageId,
        Instant brokerTimestamp,
        boolean redelivered,
        Map<String, String> metadata,
        long activationGeneration
    ) {
        this.payload = Objects.requireNonNull(payload, "payload").clone();
        this.payloadByteCount = this.payload.length;
        this.payloadSha256 = sha256(this.payload);
        this.receivedAtUtc = Objects.requireNonNull(receivedAtUtc, "receivedAtUtc");
        this.captureId = requireText(captureId, "captureId");
        this.captureReference = requireText(captureReference, "captureReference");
        this.sourceEnvironment = requireText(sourceEnvironment, "sourceEnvironment");
        this.endpointIdentity = requireText(endpointIdentity, "endpointIdentity");
        this.exactDestination = requireText(exactDestination, "exactDestination");
        this.accountIdentity = requireText(accountIdentity, "accountIdentity");
        this.jmsMessageId = jmsMessageId;
        this.brokerTimestamp = brokerTimestamp;
        this.redelivered = redelivered;
        LinkedHashMap<String, String> metadataCopy = new LinkedHashMap<>(
            Objects.requireNonNull(metadata, "metadata"));
        if (!ALLOWED_METADATA.containsAll(metadataCopy.keySet())
                || metadataCopy.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("metadata contains a non-allowlisted entry");
        }
        this.metadata = Map.copyOf(metadataCopy);
        this.activationGeneration = activationGeneration;
    }

    byte[] payload() { return payload.clone(); }
    int payloadByteCount() { return payloadByteCount; }
    String payloadSha256() { return payloadSha256; }
    Instant receivedAtUtc() { return receivedAtUtc; }
    String captureId() { return captureId; }
    String captureReference() { return captureReference; }
    String sourceEnvironment() { return sourceEnvironment; }
    String endpointIdentity() { return endpointIdentity; }
    String exactDestination() { return exactDestination; }
    String accountIdentity() { return accountIdentity; }
    String jmsMessageId() { return jmsMessageId; }
    Instant brokerTimestamp() { return brokerTimestamp; }
    boolean redelivered() { return redelivered; }
    Map<String, String> metadata() { return metadata; }
    long activationGeneration() { return activationGeneration; }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        return value;
    }

    private static String sha256(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) out.append(String.format("%02x", value));
            Arrays.fill(digest, (byte) 0);
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
