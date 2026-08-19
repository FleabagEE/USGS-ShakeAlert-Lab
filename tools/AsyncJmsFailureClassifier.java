import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import javax.jms.JMSException;
import javax.jms.JMSSecurityException;
import javax.net.ssl.SSLException;

import org.apache.activemq.AlreadyClosedException;
import org.apache.activemq.ConnectionClosedException;
import org.apache.activemq.ConnectionFailedException;
import org.apache.activemq.transport.InactivityIOException;
import org.apache.activemq.transport.RequestTimedOutIOException;
import org.apache.activemq.transport.TransportDisposedIOException;

/** Type-only, bounded classification for asynchronous JMS connection failures. */
final class AsyncJmsFailureClassifier {
    static final int MAX_CAUSE_NODES = 8;

    enum Category {
        INACTIVITY_TIMEOUT,
        TRANSPORT_EOF,
        TRANSPORT_TIMEOUT,
        TLS_TRANSPORT_FAILURE,
        BROKER_SECURITY_FAILURE,
        JMS_CONNECTION_FAILURE,
        UNKNOWN_JMS_FAILURE
    }

    record Diagnostic(
        Instant failureUtc,
        ScenarioReceiverService.LifecycleState lifecycleState,
        long connectionUptimeMillis,
        String accountId,
        String endpointName,
        String exactDestination,
        Category failureCategory,
        long messagesReceived,
        long capturesCommitted,
        long messagesAcknowledged,
        long acknowledgementFailures,
        int callbacksInProgress,
        boolean shutdownAlreadyRequested
    ) {
        Diagnostic {
            if (failureUtc == null || lifecycleState == null || failureCategory == null) {
                throw new IllegalArgumentException("required diagnostic field is absent");
            }
            if (connectionUptimeMillis < 0 || messagesReceived < 0 || capturesCommitted < 0
                    || messagesAcknowledged < 0 || acknowledgementFailures < 0
                    || callbacksInProgress < 0) {
                throw new IllegalArgumentException("negative diagnostic counter");
            }
            if (!"QuakeLogic-SA1".equals(accountId)) {
                throw new IllegalArgumentException("invalid account identity");
            }
            if (!"scenario-openwire".equals(endpointName)) {
                throw new IllegalArgumentException("invalid endpoint identity");
            }
            if (!"eew.test_QuakeLogic-SA1.dm.data".equals(exactDestination)) {
                throw new IllegalArgumentException("invalid exact destination");
            }
        }
    }

    private AsyncJmsFailureClassifier() {}

    static Category classify(JMSException failure) {
        if (failure == null) return Category.UNKNOWN_JMS_FAILURE;
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(failure);
        Category best = Category.UNKNOWN_JMS_FAILURE;
        int inspected = 0;
        while (!pending.isEmpty() && inspected < MAX_CAUSE_NODES) {
            Throwable current = pending.removeFirst();
            if (current == null || !visited.add(current)) continue;
            inspected++;
            Category category = classifyType(current);
            if (priority(category) < priority(best)) best = category;
            if (best == Category.INACTIVITY_TIMEOUT) return best;
            Throwable cause = current.getCause();
            if (cause != null && !visited.contains(cause)) pending.addLast(cause);
            if (current instanceof JMSException jms) {
                Exception linked = jms.getLinkedException();
                if (linked != null && !visited.contains(linked)) pending.addLast(linked);
            }
        }
        return best;
    }

    static Diagnostic diagnostic(JMSException failure, Instant failureUtc,
            Instant connectionStartedUtc, ScenarioReceiverService.HealthSnapshot health) {
        long uptime = connectionStartedUtc == null ? 0L
            : Math.max(0L, Duration.between(connectionStartedUtc, failureUtc).toMillis());
        return new Diagnostic(failureUtc, health.lifecycleState(), uptime,
            health.accountId(), health.endpointName(), health.exactDestination(),
            classify(failure), health.messagesReceived(), health.capturesCommitted(),
            health.messagesAcknowledged(), health.acknowledgementFailures(),
            health.callbacksInProgress(), health.shutdownRequested());
    }

    private static Category classifyType(Throwable failure) {
        if (failure instanceof InactivityIOException) return Category.INACTIVITY_TIMEOUT;
        if (failure instanceof EOFException) return Category.TRANSPORT_EOF;
        if (failure instanceof SocketTimeoutException
                || failure instanceof RequestTimedOutIOException) {
            return Category.TRANSPORT_TIMEOUT;
        }
        if (failure instanceof SSLException) return Category.TLS_TRANSPORT_FAILURE;
        if (failure instanceof JMSSecurityException) return Category.BROKER_SECURITY_FAILURE;
        if (failure instanceof ConnectionFailedException
                || failure instanceof ConnectionClosedException
                || failure instanceof AlreadyClosedException
                || failure instanceof TransportDisposedIOException
                || failure instanceof ConnectException
                || failure instanceof javax.jms.IllegalStateException) {
            return Category.JMS_CONNECTION_FAILURE;
        }
        return Category.UNKNOWN_JMS_FAILURE;
    }

    private static int priority(Category category) {
        return switch (category) {
            case INACTIVITY_TIMEOUT -> 0;
            case TLS_TRANSPORT_FAILURE -> 1;
            case TRANSPORT_TIMEOUT -> 2;
            case TRANSPORT_EOF -> 3;
            case BROKER_SECURITY_FAILURE -> 4;
            case JMS_CONNECTION_FAILURE -> 5;
            case UNKNOWN_JMS_FAILURE -> 6;
        };
    }
}
