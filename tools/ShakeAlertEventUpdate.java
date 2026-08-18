import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable domain representation of one supported ShakeAlert Event update. */
record ShakeAlertEventUpdate(
    Provenance provenance,
    MessageType messageType,
    int updateVersion,
    String eventId,
    String algorithmVersion,
    String category,
    String originSystem,
    String instance,
    Instant messageTimestamp,
    CoreInfo coreInfo,
    List<Contributor> contributors,
    boolean groundMotionInfoPresent
) {
    enum MessageType { NEW, UPDATE }

    record Provenance(
        String captureId,
        String payloadSha256,
        Instant receivedAtUtc,
        String sourceEnvironment,
        String endpointIdentity,
        String exactDestination,
        String accountIdentity,
        String jmsMessageId,
        boolean redelivered,
        long activationGeneration
    ) {}

    record CoreInfo(
        Instant originTime,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal depth,
        BigDecimal magnitude,
        BigDecimal likelihood,
        int stationCount
    ) {}

    record Contributor(
        String algorithmName,
        String algorithmVersion,
        String algorithmInstance,
        String category,
        String eventId,
        int version
    ) {}

    ShakeAlertEventUpdate {
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(algorithmVersion, "algorithmVersion");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(originSystem, "originSystem");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(messageTimestamp, "messageTimestamp");
        Objects.requireNonNull(coreInfo, "coreInfo");
        contributors = List.copyOf(Objects.requireNonNull(contributors, "contributors"));
    }

    String updateIdentity() {
        return eventId + ":" + updateVersion + ":" + messageType.name();
    }
}
