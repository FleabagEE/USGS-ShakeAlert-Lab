import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.EOFException;
import java.net.SocketTimeoutException;
import java.time.Instant;

import javax.jms.JMSException;
import javax.jms.JMSSecurityException;
import javax.net.ssl.SSLException;

import org.apache.activemq.transport.InactivityIOException;
import org.junit.jupiter.api.Test;

final class AsyncJmsFailureClassifierTest {
    private static final String SECRET = "password=do-not-record /private/credentials";

    @Test void classifiesOnlyReliablyDistinguishableTypes() {
        assertCategory(AsyncJmsFailureClassifier.Category.INACTIVITY_TIMEOUT,
            linked(new InactivityIOException(SECRET)));
        assertCategory(AsyncJmsFailureClassifier.Category.TRANSPORT_EOF,
            linked(new EOFException(SECRET)));
        assertCategory(AsyncJmsFailureClassifier.Category.TRANSPORT_TIMEOUT,
            linked(new SocketTimeoutException(SECRET)));
        assertCategory(AsyncJmsFailureClassifier.Category.TLS_TRANSPORT_FAILURE,
            linked(new SSLException(SECRET)));
        assertCategory(AsyncJmsFailureClassifier.Category.BROKER_SECURITY_FAILURE,
            new JMSSecurityException(SECRET));
        assertCategory(AsyncJmsFailureClassifier.Category.JMS_CONNECTION_FAILURE,
            new org.apache.activemq.ConnectionFailedException(
                new java.io.IOException(SECRET)));
    }

    @Test void unknownAndNestedCausesFailClosed() {
        assertCategory(AsyncJmsFailureClassifier.Category.UNKNOWN_JMS_FAILURE,
            new JMSException(SECRET));
        assertCategory(AsyncJmsFailureClassifier.Category.UNKNOWN_JMS_FAILURE,
            linked(new java.net.SocketException("Connection reset: " + SECRET)));
        JMSException nested = new JMSException(SECRET);
        nested.initCause(new SSLException(SECRET));
        assertCategory(AsyncJmsFailureClassifier.Category.TLS_TRANSPORT_FAILURE, nested);
    }

    @Test void causeTraversalIsBoundedAndCycleSafe() {
        JMSException cyclic = new JMSException(SECRET);
        cyclic.setLinkedException(cyclic);
        assertCategory(AsyncJmsFailureClassifier.Category.UNKNOWN_JMS_FAILURE, cyclic);

        Throwable tail = new EOFException(SECRET);
        for (int index = 0; index < AsyncJmsFailureClassifier.MAX_CAUSE_NODES; index++) {
            tail = new Exception(SECRET, tail);
        }
        JMSException deep = new JMSException(SECRET);
        deep.initCause(tail);
        assertCategory(AsyncJmsFailureClassifier.Category.UNKNOWN_JMS_FAILURE, deep);
    }

    @Test void diagnosticContainsOnlySanitizedStructuredValues() {
        ScenarioReceiverService.HealthSnapshot health = new ScenarioReceiverService.HealthSnapshot(
            ScenarioReceiverService.LifecycleState.RUNNING, Instant.parse("2026-08-19T18:00:00Z"),
            Instant.parse("2026-08-19T17:00:00Z"), true, true, true, true,
            "QuakeLogic-SA1", "scenario-openwire", "eew.test_QuakeLogic-SA1.dm.data",
            0, 0, 0, 0, 0, 0, false, null, null, false);
        AsyncJmsFailureClassifier.Diagnostic diagnostic = AsyncJmsFailureClassifier.diagnostic(
            linked(new EOFException(SECRET)), Instant.parse("2026-08-19T18:50:47.633Z"),
            Instant.parse("2026-08-19T18:30:00Z"), health);
        String json = SanitizedAsyncJmsIncidentStore.json(diagnostic);
        assertEquals(AsyncJmsFailureClassifier.Category.TRANSPORT_EOF,
            diagnostic.failureCategory());
        assertEquals(1247633L, diagnostic.connectionUptimeMillis());
        assertFalse(json.contains(SECRET));
        assertFalse(json.contains("stack"));
        assertFalse(json.contains("credential"));
        assertTrue(json.contains("TRANSPORT_EOF"));
    }

    private static JMSException linked(Exception cause) {
        JMSException outer = new JMSException(SECRET);
        outer.setLinkedException(cause);
        return outer;
    }

    private static void assertCategory(AsyncJmsFailureClassifier.Category expected,
            JMSException failure) {
        assertEquals(expected, AsyncJmsFailureClassifier.classify(failure));
    }
}
