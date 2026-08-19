import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Stateful domain-processing boundary; capture preservation occurs before entry. */
final class ShakeAlertEventProcessor {
    enum State { RUNNING, FAILED }
    record Outcome(ShakeAlertEventParser.FailureCategory rejection,
                   ShakeAlertMessage message, boolean domainProcessingSuppressed) {
        static Outcome accepted(ShakeAlertMessage message) {
            return new Outcome(null, message, false);
        }
        static Outcome rejected(ShakeAlertEventParser.FailureCategory category) {
            return new Outcome(category, null, false);
        }
        static Outcome duplicate(ShakeAlertMessage message) {
            return new Outcome(ShakeAlertEventParser.FailureCategory.DUPLICATE_DELIVERY,
                message, true);
        }
        ShakeAlertEventUpdate update() {
            return message instanceof ShakeAlertEventUpdate update ? update : null;
        }
    }

    interface Parser { ShakeAlertMessage parse(MessageEnvelope envelope) throws Exception; }

    private final Parser parser;
    private final Set<String> brokerIds = new HashSet<>();
    private final Set<String> domainPayloadIds = new HashSet<>();
    private State state = State.RUNNING;
    private String failureCategory;
    private long failureCount;
    private java.time.Instant failureUtc;

    ShakeAlertEventProcessor(ShakeAlertEventParser parser) { this(parser::parse); }
    ShakeAlertEventProcessor(ShakeAlertMessageParser parser) { this(parser::parse); }
    ShakeAlertEventProcessor(Parser parser) { this.parser = Objects.requireNonNull(parser, "parser"); }

    synchronized Outcome process(MessageEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (state == State.FAILED) return Outcome.rejected(ShakeAlertEventParser.FailureCategory.PARSER_FAILURE);
        try {
            ShakeAlertMessage message = parser.parse(envelope);
            String brokerId = envelope.jmsMessageId();
            String domainPayloadId = message.messageIdentity() + ":" + envelope.payloadSha256();
            if ((brokerId != null && brokerIds.contains(brokerId))
                    || domainPayloadIds.contains(domainPayloadId)) {
                return Outcome.duplicate(message);
            }
            if (brokerId != null) brokerIds.add(brokerId);
            domainPayloadIds.add(domainPayloadId);
            return Outcome.accepted(message);
        } catch (ShakeAlertEventParser.ExpectedFailure expected) {
            return Outcome.rejected(expected.category());
        } catch (Exception | LinkageError unexpected) {
            state = State.FAILED;
            failureCategory = ShakeAlertEventParser.FailureCategory.PARSER_FAILURE.name();
            failureCount++;
            failureUtc = java.time.Instant.now();
            return Outcome.rejected(ShakeAlertEventParser.FailureCategory.PARSER_FAILURE);
        }
    }

    synchronized State state() { return state; }
    synchronized String failureCategory() { return failureCategory; }
    synchronized long failureCount() { return failureCount; }
    synchronized java.time.Instant failureUtc() { return failureUtc; }
}
