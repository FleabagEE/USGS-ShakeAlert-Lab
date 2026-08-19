import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable domain representation of the observed Scenario version-900 follow-up. */
record ShakeAlertFollowUp(
    ShakeAlertEventUpdate.Provenance provenance,
    String eventId,
    int version,
    String algorithmVersion,
    String category,
    String originSystem,
    String instance,
    Instant messageTimestamp,
    CoreInfo coreInfo,
    List<ShakeAlertEventUpdate.Contributor> contributors,
    List<FollowUpNotice> notices,
    List<GroundMotionContour> contours
) implements ShakeAlertMessage {
    record CoreInfo(
        BigDecimal magnitude,
        BigDecimal magnitudeUncertainty,
        BigDecimal latitude,
        BigDecimal latitudeUncertainty,
        BigDecimal longitude,
        BigDecimal longitudeUncertainty,
        BigDecimal depth,
        BigDecimal depthUncertainty,
        Instant originTime,
        BigDecimal originTimeUncertaintySeconds,
        BigDecimal likelihood,
        int stationCount
    ) {}

    ShakeAlertFollowUp {
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(algorithmVersion, "algorithmVersion");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(originSystem, "originSystem");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(messageTimestamp, "messageTimestamp");
        Objects.requireNonNull(coreInfo, "coreInfo");
        contributors = List.copyOf(Objects.requireNonNull(contributors, "contributors"));
        notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
        contours = List.copyOf(Objects.requireNonNull(contours, "contours"));
    }

    @Override public String messageIdentity() { return eventId + ":" + version + ":FOLLOW_UP"; }
}
