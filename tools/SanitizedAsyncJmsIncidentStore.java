import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Durable single-record store for the latest sanitized asynchronous JMS incident. */
final class SanitizedAsyncJmsIncidentStore
        implements ScenarioReceiverService.AsyncFailureSink {
    static final String FILE_NAME = "async-jms-latest.json";
    static final int MAX_RECORD_BYTES = 4096;
    private static final Set<PosixFilePermission> DIRECTORY_MODE = Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_EXECUTE);
    private static final Set<PosixFilePermission> FILE_MODE = Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ);

    private final Path directory;
    private final Path target;

    SanitizedAsyncJmsIncidentStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        this.target = this.directory.resolve(FILE_NAME);
    }

    @Override public void persist(AsyncJmsFailureClassifier.Diagnostic diagnostic) {
        byte[] content = json(diagnostic).getBytes(StandardCharsets.UTF_8);
        if (content.length > MAX_RECORD_BYTES) {
            throw new IncidentWriteException(new IOException("sanitized incident exceeds limit"));
        }
        try {
            atomicWrite(directory, target, content);
        } catch (IOException error) {
            throw new IncidentWriteException(error);
        }
    }

    static String json(AsyncJmsFailureClassifier.Diagnostic diagnostic) {
        return "{"
            + field("failure_utc", diagnostic.failureUtc().toString()) + ","
            + field("lifecycle_state", diagnostic.lifecycleState().name()) + ","
            + field("connection_uptime_millis", diagnostic.connectionUptimeMillis()) + ","
            + field("account_id", diagnostic.accountId()) + ","
            + field("endpoint_name", diagnostic.endpointName()) + ","
            + field("exact_destination", diagnostic.exactDestination()) + ","
            + field("failure_category", diagnostic.failureCategory().name()) + ","
            + field("messages_received", diagnostic.messagesReceived()) + ","
            + field("captures_committed", diagnostic.capturesCommitted()) + ","
            + field("messages_acknowledged", diagnostic.messagesAcknowledged()) + ","
            + field("acknowledgement_failures", diagnostic.acknowledgementFailures()) + ","
            + field("callbacks_in_progress", diagnostic.callbacksInProgress()) + ","
            + field("shutdown_already_requested", diagnostic.shutdownAlreadyRequested())
            + "}\n";
    }

    static void atomicWrite(Path directory, Path target, byte[] content) throws IOException {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(target)) {
            throw new IOException("incident path is a symbolic link");
        }
        Files.setPosixFilePermissions(directory, DIRECTORY_MODE);
        Path temporary = Files.createTempFile(directory, ".async-jms-", ".tmp");
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
                throw new IOException("atomic incident replacement is unavailable", error);
            }
            Files.setPosixFilePermissions(target, FILE_MODE);
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String field(String name, String value) {
        return quote(name) + ":" + quote(value);
    }
    private static String field(String name, long value) {
        return quote(name) + ":" + value;
    }
    private static String field(String name, int value) {
        return field(name, (long) value);
    }
    private static String field(String name, boolean value) {
        return quote(name) + ":" + value;
    }
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (character < 0x20) out.append(String.format("\\u%04x", (int) character));
                    else out.append(character);
                }
            }
        }
        return out.append('\"').toString();
    }

    static final class IncidentWriteException extends RuntimeException {
        IncidentWriteException(IOException cause) {
            super("sanitized asynchronous JMS incident persistence failed", cause);
        }
    }
}
