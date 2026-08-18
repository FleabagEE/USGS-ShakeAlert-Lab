import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Persists bounded, sanitized records for expected parsing/data rejections. */
final class SanitizedRejectionStore {
    record Retention(int maximumFileCount, long maximumTotalBytes, Duration maximumAge) {
        Retention {
            if (maximumFileCount <= 0 || maximumTotalBytes <= 0
                    || maximumAge.isNegative() || maximumAge.isZero()) {
                throw new IllegalArgumentException("retention limits must be positive");
            }
        }
    }

    private static final Set<ShakeAlertEventParser.FailureCategory> EXPECTED = Set.of(
        ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD,
        ShakeAlertEventParser.FailureCategory.UNKNOWN_MESSAGE_TYPE,
        ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA,
        ShakeAlertEventParser.FailureCategory.OVERSIZED_PAYLOAD,
        ShakeAlertEventParser.FailureCategory.DUPLICATE_DELIVERY);
    private static final DateTimeFormatter FILE_TIME =
        DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC);
    private static final Set<PosixFilePermission> DIRECTORY_MODE = Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_EXECUTE);
    private static final Set<PosixFilePermission> FILE_MODE = Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ);

    private final Path directory;
    private final Retention retention;

    SanitizedRejectionStore(Path directory, Retention retention) throws IOException {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.retention = Objects.requireNonNull(retention, "retention");
        Files.createDirectories(this.directory);
        Files.setPosixFilePermissions(this.directory, DIRECTORY_MODE);
    }

    synchronized void record(MessageEnvelope envelope, ShakeAlertEventProcessor.Outcome outcome)
            throws IOException {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(outcome, "outcome");
        if (!EXPECTED.contains(outcome.rejection())) {
            throw new IllegalArgumentException("only expected sanitized rejection categories may persist");
        }
        Instant now = Instant.now();
        String identity = outcome.update() == null ? null : outcome.update().updateIdentity();
        String json = "{"
            + field("timestamp_utc", now.toString()) + ","
            + field("capture_id", envelope.captureId()) + ","
            + field("payload_sha256", envelope.payloadSha256()) + ","
            + field("failure_category", outcome.rejection().name()) + ","
            + field("event_update_identity", identity)
            + "}\n";
        Path target = directory.resolve(FILE_TIME.format(now) + "_" + UUID.randomUUID() + ".json");
        LocalHealthStatus.atomicWrite(target,
            json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(target, FILE_MODE);
        enforceRetention(now);
    }

    void enforceRetention(Instant now) throws IOException {
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(path -> Files.isRegularFile(path) && !Files.isSymbolicLink(path)
                    && path.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing(this::modifiedTime)
                    .thenComparing(path -> path.getFileName().toString()))
                .toList();
        }
        Instant cutoff = now.minus(retention.maximumAge());
        for (Path file : files) {
            if (modifiedTime(file).toInstant().isBefore(cutoff)) Files.deleteIfExists(file);
        }
        try (var stream = Files.list(directory)) {
            files = stream.filter(path -> Files.isRegularFile(path) && !Files.isSymbolicLink(path)
                    && path.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing(this::modifiedTime)
                    .thenComparing(path -> path.getFileName().toString()))
                .toList();
        }
        long total = 0;
        for (Path file : files) total += Files.size(file);
        int count = files.size();
        for (Path file : files) {
            if (count <= retention.maximumFileCount() && total <= retention.maximumTotalBytes()) break;
            long size = Files.size(file);
            if (Files.deleteIfExists(file)) { count--; total -= size; }
        }
        try (var channel = java.nio.channels.FileChannel.open(directory,
                java.nio.file.StandardOpenOption.READ)) { channel.force(true); }
    }

    private FileTime modifiedTime(Path path) {
        try { return Files.getLastModifiedTime(path); }
        catch (IOException error) { throw new RetentionReadException(error); }
    }

    private static final class RetentionReadException extends RuntimeException {
        RetentionReadException(IOException cause) { super(cause); }
    }
    private static String field(String name, String value) {
        return quote(name) + ":" + quote(value);
    }
    private static String quote(String value) {
        if (value == null) return "null";
        if (!value.matches("[A-Za-z0-9_.:+-]{1,256}")) {
            throw new IllegalArgumentException("rejection field is not safely representable");
        }
        return "\"" + value + "\"";
    }
}
