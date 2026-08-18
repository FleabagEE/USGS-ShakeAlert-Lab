import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Stateful domain-processing boundary; capture preservation occurs before entry. */
final class ShakeAlertEventProcessor {
    enum State { RUNNING, FAILED }
    record Outcome(ShakeAlertEventParser.FailureCategory rejection,
                   ShakeAlertEventUpdate update, boolean domainProcessingSuppressed) {
        static Outcome accepted(ShakeAlertEventUpdate update) { return new Outcome(null, update, false); }
        static Outcome rejected(ShakeAlertEventParser.FailureCategory category) {
            return new Outcome(category, null, false);
        }
        static Outcome duplicate(ShakeAlertEventUpdate update) {
            return new Outcome(ShakeAlertEventParser.FailureCategory.DUPLICATE_DELIVERY, update, true);
        }
    }

    interface Parser { ShakeAlertEventUpdate parse(MessageEnvelope envelope) throws Exception; }

    private final Parser parser;
    private final Set<String> brokerIds = new HashSet<>();
    private final Set<String> domainPayloadIds = new HashSet<>();
    private State state = State.RUNNING;
    private String failureCategory;
    private long failureCount;
    private java.time.Instant failureUtc;

    ShakeAlertEventProcessor(ShakeAlertEventParser parser) { this(parser::parse); }
    ShakeAlertEventProcessor(Parser parser) { this.parser = Objects.requireNonNull(parser, "parser"); }

    synchronized Outcome process(MessageEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (state == State.FAILED) return Outcome.rejected(ShakeAlertEventParser.FailureCategory.PARSER_FAILURE);
        try {
            ShakeAlertEventUpdate update = parser.parse(envelope);
            String brokerId = envelope.jmsMessageId();
            String domainPayloadId = update.updateIdentity() + ":" + envelope.payloadSha256();
            if ((brokerId != null && brokerIds.contains(brokerId))
                    || domainPayloadIds.contains(domainPayloadId)) {
                return Outcome.duplicate(update);
            }
            if (brokerId != null) brokerIds.add(brokerId);
            domainPayloadIds.add(domainPayloadId);
            return Outcome.accepted(update);
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
