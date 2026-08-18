import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Atomically publishes the strictly allowlisted local receiver health contract. */
final class LocalHealthStatus implements ScenarioReceiverService.HealthSink {
    private static final Set<PosixFilePermission> DIRECTORY_MODE = Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_EXECUTE);
    private static final Set<PosixFilePermission> FILE_MODE = Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ);

    private final Path target;
    private final ShakeAlertEventProcessor processor;

    LocalHealthStatus(Path target, ShakeAlertEventProcessor processor) {
        this.target = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    @Override public void publish(ScenarioReceiverService.HealthSnapshot health) {
        boolean parserFailed = processor.state() == ShakeAlertEventProcessor.State.FAILED;
        String lastCategory = parserFailed ? processor.failureCategory() : health.lastErrorCategory();
        Instant lastError = parserFailed && processor.failureUtc() != null
            ? processor.failureUtc() : health.lastErrorUtc();
        String json = "{"
            + field("lifecycle_state", health.lifecycleState().name()) + ","
            + field("state_entered_utc", health.stateEnteredUtc()) + ","
            + field("process_started_utc", health.processStartedUtc()) + ","
            + field("connected", health.connected()) + ","
            + field("authenticated", health.authenticated()) + ","
            + field("subscribed", health.subscribed()) + ","
            + field("connection_started", health.connectionStarted()) + ","
            + field("account_id", health.accountId()) + ","
            + field("endpoint_name", health.endpointName()) + ","
            + field("exact_destination", health.exactDestination()) + ","
            + field("messages_received", health.messagesReceived()) + ","
            + field("captures_committed", health.capturesCommitted()) + ","
            + field("capture_failures", health.captureFailures()) + ","
            + field("callbacks_in_progress", health.callbacksInProgress()) + ","
            + field("async_jms_error", health.asyncJmsError()) + ","
            + field("parser_failed", parserFailed) + ","
            + field("parser_failure_count", processor.failureCount()) + ","
            + field("last_error_category", lastCategory) + ","
            + field("last_error_utc", lastError) + ","
            + field("shutdown_requested", health.shutdownRequested())
            + "}\n";
        try {
            atomicWrite(target, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new HealthWriteException(error);
        }
    }

    static boolean ready(ScenarioReceiverService.HealthSnapshot health,
            ShakeAlertEventProcessor processor) {
        return health.lifecycleState() == ScenarioReceiverService.LifecycleState.RUNNING
            && health.connected() && health.authenticated() && health.subscribed()
            && health.connectionStarted() && !health.asyncJmsError()
            && processor.state() != ShakeAlertEventProcessor.State.FAILED;
    }

    static void atomicWrite(Path target, byte[] content) throws IOException {
        Path parent = target.getParent();
        if (parent == null) throw new IOException("health file has no parent");
        Files.createDirectories(parent);
        Files.setPosixFilePermissions(parent, DIRECTORY_MODE);
        Path temporary = Files.createTempFile(parent, ".health-", ".tmp");
        try {
            Files.setPosixFilePermissions(temporary, FILE_MODE);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(content));
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                throw new IOException("atomic health replacement is unavailable", error);
            }
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static final class HealthWriteException extends RuntimeException {
        HealthWriteException(IOException cause) { super("sanitized health publication failed", cause); }
    }

    private static String field(String name, String value) {
        return quote(name) + ":" + quote(value);
    }
    private static String field(String name, Instant value) {
        return field(name, value == null ? null : value.toString());
    }
    private static String field(String name, boolean value) {
        return quote(name) + ":" + value;
    }
    private static String field(String name, long value) {
        return quote(name) + ":" + value;
    }
    private static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) out.append(String.format("\\u%04x", (int) character));
                    else out.append(character);
                }
            }
        }
        return out.append('\"').toString();
    }
}
