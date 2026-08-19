import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SanitizedAsyncJmsIncidentStoreTest {
    @TempDir Path temporary;

    @Test void atomicallyPublishesBoundedPersistentSanitizedRecord() throws Exception {
        Path state = temporary.resolve("state/incidents");
        SanitizedAsyncJmsIncidentStore store = new SanitizedAsyncJmsIncidentStore(state);
        store.persist(diagnostic(AsyncJmsFailureClassifier.Category.TRANSPORT_EOF));

        Path record = state.resolve(SanitizedAsyncJmsIncidentStore.FILE_NAME);
        String json = Files.readString(record, StandardCharsets.UTF_8);
        assertTrue(Files.isRegularFile(record));
        assertTrue(Files.size(record) <= SanitizedAsyncJmsIncidentStore.MAX_RECORD_BYTES);
        assertEquals("rwxr-x---", PosixFilePermissions.toString(
            Files.getPosixFilePermissions(state)));
        assertEquals("rw-r-----", PosixFilePermissions.toString(
            Files.getPosixFilePermissions(record)));
        assertTrue(json.contains("\"failure_category\":\"TRANSPORT_EOF\""));
        assertFalse(json.contains("password"));
        assertFalse(json.contains("stack"));
        assertFalse(json.contains("/home/"));
        try (var files = Files.list(state)) {
            assertEquals(List.of(SanitizedAsyncJmsIncidentStore.FILE_NAME),
                files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test void recordSurvivesIndependentRuntimeDirectoryRemoval() throws Exception {
        Path runtime = temporary.resolve("run");
        Files.createDirectories(runtime);
        Path state = temporary.resolve("state/incidents");
        new SanitizedAsyncJmsIncidentStore(state).persist(
            diagnostic(AsyncJmsFailureClassifier.Category.UNKNOWN_JMS_FAILURE));
        Files.delete(runtime);
        assertTrue(Files.isRegularFile(
            state.resolve(SanitizedAsyncJmsIncidentStore.FILE_NAME)));
    }

    @Test void replacesOnlyTheSingleLatestRecord() throws Exception {
        Path state = temporary.resolve("state/incidents");
        SanitizedAsyncJmsIncidentStore store = new SanitizedAsyncJmsIncidentStore(state);
        store.persist(diagnostic(AsyncJmsFailureClassifier.Category.TRANSPORT_EOF));
        store.persist(diagnostic(AsyncJmsFailureClassifier.Category.TLS_TRANSPORT_FAILURE));
        String json = Files.readString(
            state.resolve(SanitizedAsyncJmsIncidentStore.FILE_NAME));
        assertTrue(json.contains("TLS_TRANSPORT_FAILURE"));
        assertFalse(json.contains("TRANSPORT_EOF"));
        try (var files = Files.list(state)) { assertEquals(1L, files.count()); }
    }

    @Test void refusesSymbolicLinkTargets() throws Exception {
        Path state = temporary.resolve("state/incidents");
        Files.createDirectories(state);
        Path elsewhere = temporary.resolve("elsewhere");
        Files.writeString(elsewhere, "unchanged");
        Files.createSymbolicLink(
            state.resolve(SanitizedAsyncJmsIncidentStore.FILE_NAME), elsewhere);
        assertThrows(SanitizedAsyncJmsIncidentStore.IncidentWriteException.class,
            () -> new SanitizedAsyncJmsIncidentStore(state).persist(
                diagnostic(AsyncJmsFailureClassifier.Category.TRANSPORT_EOF)));
        assertEquals("unchanged", Files.readString(elsewhere));
    }

    private static AsyncJmsFailureClassifier.Diagnostic diagnostic(
            AsyncJmsFailureClassifier.Category category) {
        return new AsyncJmsFailureClassifier.Diagnostic(
            Instant.parse("2026-08-19T18:50:47.633Z"),
            ScenarioReceiverService.LifecycleState.RUNNING, 60000,
            "QuakeLogic-SA1", "scenario-openwire", "eew.test_QuakeLogic-SA1.dm.data",
            category, 0, 0, 0, 0, 0, false);
    }
}
