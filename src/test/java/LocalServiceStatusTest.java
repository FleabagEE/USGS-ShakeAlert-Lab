import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalServiceStatusTest {
    private static final String TOPIC = "eew.test_QuakeLogic-SA1.dm.data";

    @Test void healthSnapshotAtomicallyReplacesSanitizedAllowlistedSchema(@TempDir Path directory)
            throws Exception {
        ShakeAlertEventProcessor processor = healthyProcessor();
        Path target = directory.resolve("runtime/health.json");
        LocalHealthStatus publisher = new LocalHealthStatus(target, processor);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "stale");
        publisher.publish(health(ScenarioReceiverService.LifecycleState.RUNNING,
            true, true, true, true, false, false));

        String json = Files.readString(target);
        for (String field : Set.of(
                "lifecycle_state", "state_entered_utc", "process_started_utc",
                "connected", "authenticated", "subscribed", "connection_started",
                "account_id", "endpoint_name", "exact_destination", "messages_received",
                "captures_committed", "capture_failures", "messages_acknowledged",
                "acknowledgement_failures", "callbacks_in_progress",
                "async_jms_error", "parser_failed", "parser_failure_count",
                "last_error_category", "last_error_utc", "shutdown_requested")) {
            assertTrue(json.contains("\"" + field + "\":"), field);
        }
        assertEquals(22, json.split("\":", -1).length - 1);
        for (String forbidden : Set.of("password", "credential", "payload", "broker_header",
                "exception_text", "capture_reference")) assertFalse(json.contains(forbidden));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ), Files.getPosixFilePermissions(target));
        try (var files = Files.list(target.getParent())) {
            assertEquals(1, files.count(), "temporary health file must not remain");
        }
    }

    @Test void readinessRequiresEveryHealthPredicate() {
        ShakeAlertEventProcessor processor = healthyProcessor();
        assertTrue(LocalHealthStatus.ready(health(ScenarioReceiverService.LifecycleState.RUNNING,
            true, true, true, true, false, false), processor));
        assertFalse(LocalHealthStatus.ready(health(ScenarioReceiverService.LifecycleState.STARTING,
            true, true, true, true, false, false), processor));
        assertFalse(LocalHealthStatus.ready(health(ScenarioReceiverService.LifecycleState.RUNNING,
            false, true, true, true, false, false), processor));
        assertFalse(LocalHealthStatus.ready(health(ScenarioReceiverService.LifecycleState.RUNNING,
            true, false, true, true, false, false), processor));
        assertFalse(LocalHealthStatus.ready(health(ScenarioReceiverService.LifecycleState.RUNNING,
            true, true, false, true, false, false), processor));
        assertFalse(LocalHealthStatus.ready(health(ScenarioReceiverService.LifecycleState.RUNNING,
            true, true, true, false, false, false), processor));
        assertFalse(LocalHealthStatus.ready(health(ScenarioReceiverService.LifecycleState.RUNNING,
            true, true, true, true, true, false), processor));
    }

    @Test void parserFailureImmediatelyMakesHealthNotReady(@TempDir Path directory) throws Exception {
        ShakeAlertEventProcessor processor = new ShakeAlertEventProcessor(envelope -> {
            throw new IllegalStateException("private parser detail");
        });
        processor.process(envelope("capture-parser-failure"));
        Path target = directory.resolve("health.json");
        new LocalHealthStatus(target, processor).publish(
            health(ScenarioReceiverService.LifecycleState.RUNNING,
                true, true, true, true, false, false));

        String json = Files.readString(target);
        assertTrue(json.contains("\"parser_failed\":true"));
        assertTrue(json.contains("\"parser_failure_count\":1"));
        assertTrue(json.contains("\"last_error_category\":\"PARSER_FAILURE\""));
        assertFalse(json.contains("private parser detail"));
        assertFalse(LocalHealthStatus.ready(health(ScenarioReceiverService.LifecycleState.RUNNING,
            true, true, true, true, false, false), processor));
    }

    @Test void rejectionRecordIsSanitizedAndAtomic(@TempDir Path directory) throws Exception {
        SanitizedRejectionStore store = new SanitizedRejectionStore(directory,
            new SanitizedRejectionStore.Retention(10, 10000, Duration.ofDays(1)));
        MessageEnvelope envelope = envelope("capture-rejected");
        store.record(envelope, ShakeAlertEventProcessor.Outcome.rejected(
            ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD));

        Path record;
        try (var files = Files.list(directory)) { record = files.findFirst().orElseThrow(); }
        String json = Files.readString(record);
        assertTrue(json.contains("\"capture_id\":\"capture-rejected\""));
        assertTrue(json.contains("\"payload_sha256\":\"" + envelope.payloadSha256() + "\""));
        assertTrue(json.contains("\"failure_category\":\"MALFORMED_PAYLOAD\""));
        for (String forbidden : Set.of("payload_text", "password", "credential", "raw_exception")) {
            assertFalse(json.contains(forbidden));
        }
        assertFalse(record.getFileName().toString().endsWith(".tmp"));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ), Files.getPosixFilePermissions(record));
    }

    @Test void rejectionRetentionAppliesAgeThenOldestCountAndBytes(@TempDir Path directory)
            throws Exception {
        SanitizedRejectionStore store = new SanitizedRejectionStore(directory,
            new SanitizedRejectionStore.Retention(2, 12, Duration.ofDays(2)));
        Path expired = file(directory, "a.json", "1111", Instant.now().minus(Duration.ofDays(3)));
        Path oldest = file(directory, "b.json", "22222222", Instant.now().minus(Duration.ofHours(3)));
        Path middle = file(directory, "c.json", "333333", Instant.now().minus(Duration.ofHours(2)));
        Path newest = file(directory, "d.json", "444444", Instant.now().minus(Duration.ofHours(1)));

        store.enforceRetention(Instant.now());

        assertFalse(Files.exists(expired));
        assertFalse(Files.exists(oldest));
        assertTrue(Files.exists(middle));
        assertTrue(Files.exists(newest));
    }

    @Test void parserFailureCannotBeWrittenAsExpectedRejection(@TempDir Path directory) throws Exception {
        SanitizedRejectionStore store = new SanitizedRejectionStore(directory,
            new SanitizedRejectionStore.Retention(10, 10000, Duration.ofDays(1)));
        assertThrows(IllegalArgumentException.class, () -> store.record(envelope("capture"),
            ShakeAlertEventProcessor.Outcome.rejected(
                ShakeAlertEventParser.FailureCategory.PARSER_FAILURE)));
    }

    private static Path file(Path directory, String name, String content, Instant modified)
            throws Exception {
        Path path = directory.resolve(name);
        Files.writeString(path, content);
        Files.setLastModifiedTime(path, FileTime.from(modified));
        return path;
    }

    private static ShakeAlertEventProcessor healthyProcessor() {
        return new ShakeAlertEventProcessor(envelope -> { throw new AssertionError("not called"); });
    }

    private static MessageEnvelope envelope(String captureId) {
        return new MessageEnvelope("<fixture/>".getBytes(StandardCharsets.UTF_8), Instant.EPOCH,
            captureId, "capture.json", "scenario", "scenario.eew.shakealert.org:61612",
            TOPIC, "QuakeLogic-SA1", null, null, false, Map.of(), 1);
    }

    private static ScenarioReceiverService.HealthSnapshot health(
            ScenarioReceiverService.LifecycleState state, boolean connected,
            boolean authenticated, boolean subscribed, boolean connectionStarted,
            boolean asyncError, boolean shutdownRequested) {
        return new ScenarioReceiverService.HealthSnapshot(state, Instant.EPOCH, Instant.EPOCH,
            connected, authenticated, subscribed, connectionStarted, "QuakeLogic-SA1",
            "scenario-openwire", TOPIC, 2, 2, 0, 2, 0, 0, asyncError,
            asyncError ? "async_jms" : null, asyncError ? Instant.EPOCH : null,
            shutdownRequested);
    }
}
