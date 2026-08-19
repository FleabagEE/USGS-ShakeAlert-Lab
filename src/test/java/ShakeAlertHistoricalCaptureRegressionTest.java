import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fail-closed verification of an explicitly named, immutable historical corpus. */
final class ShakeAlertHistoricalCaptureRegressionTest {
    private static final String HEADER =
        "source\tcapture_id\tpayload_size\tpayload_sha256\tdomain_type\tversion";
    private static final Pattern JSON_STRING =
        Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern JSON_NUMBER =
        Pattern.compile("\"%s\"\\s*:\\s*([0-9]+)");
    private static final Path MANIFEST = Path.of(
        "src/test/resources/historical-capture-manifest.tsv");

    enum Source { legacy, persistent }
    enum DomainType { ShakeAlertEventUpdate, ShakeAlertFollowUp }
    record Member(Source source, String captureId, int payloadSize, String payloadSha256,
                  DomainType domainType, int version) {}
    record Result(int verified, int eventUpdates, int followUps) {}

    @Test void frozenHistoricalCorpusVerifiesIndependentlyOfNewerCaptures() throws Exception {
        String legacy = System.getProperty("shakealert.capture.legacy.directory", "");
        String persistent = System.getProperty("shakealert.capture.persistent.directory", "");
        Assumptions.assumeFalse(legacy.isBlank() || persistent.isBlank(),
            "set both historical capture source properties for the read-only regression");

        Result result = verify(loadManifest(MANIFEST), Map.of(
            Source.legacy, Path.of(legacy), Source.persistent, Path.of(persistent)));

        assertEquals(28, result.verified());
        assertEquals(26, result.eventUpdates());
        assertEquals(2, result.followUps());
    }

    @Test void unrelatedExtraCaptureDoesNotChangeFrozenMembership(@TempDir Path root)
            throws Exception {
        Fixture fixture = fixture(root);
        Files.writeString(fixture.legacy().resolve("unrelated.json"),
            captureJson("unrelated", fixture.payload()), StandardCharsets.UTF_8);
        Result result = verify(List.of(fixture.member()), fixture.sources());
        assertEquals(new Result(1, 1, 0), result);
    }

    @Test void namedMemberMustExistAndUseApprovedSource(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        Files.delete(fixture.capture());
        assertThrows(AssertionError.class,
            () -> verify(List.of(fixture.member()), fixture.sources()));

        Fixture wrongSource = fixture(root.resolve("wrong-source"));
        Member moved = new Member(Source.persistent, wrongSource.member().captureId(),
            wrongSource.member().payloadSize(), wrongSource.member().payloadSha256(),
            wrongSource.member().domainType(), wrongSource.member().version());
        assertThrows(AssertionError.class,
            () -> verify(List.of(moved), wrongSource.sources()));
    }

    @Test void sizeHashAndProfileAreFailClosed(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        Member member = fixture.member();
        assertThrows(AssertionError.class, () -> verify(List.of(new Member(member.source(),
            member.captureId(), member.payloadSize() + 1, member.payloadSha256(),
            member.domainType(), member.version())), fixture.sources()));
        assertThrows(AssertionError.class, () -> verify(List.of(new Member(member.source(),
            member.captureId(), member.payloadSize(), "0".repeat(64),
            member.domainType(), member.version())), fixture.sources()));
        assertThrows(AssertionError.class, () -> verify(List.of(new Member(member.source(),
            member.captureId(), member.payloadSize(), member.payloadSha256(),
            DomainType.ShakeAlertFollowUp, member.version())), fixture.sources()));
    }

    @Test void manifestFormatRejectsDuplicateIdsAndUnknownFields(@TempDir Path root)
            throws Exception {
        Fixture fixture = fixture(root);
        String row = row(fixture.member());
        Path duplicate = root.resolve("duplicate.tsv");
        Files.writeString(duplicate, HEADER + "\n" + row + "\n" + row + "\n");
        assertThrows(IllegalArgumentException.class, () -> loadManifest(duplicate));

        Path unknown = root.resolve("unknown.tsv");
        Files.writeString(unknown, HEADER + "\tunknown\n" + row + "\textra\n");
        assertThrows(IllegalArgumentException.class, () -> loadManifest(unknown));
    }

    static List<Member> loadManifest(Path manifest) throws Exception {
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !HEADER.equals(lines.getFirst())) {
            throw new IllegalArgumentException("historical manifest header mismatch");
        }
        List<Member> members = new ArrayList<>();
        Set<String> captureIds = new HashSet<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) throw new IllegalArgumentException("blank manifest row");
            String[] fields = line.split("\t", -1);
            if (fields.length != 6) throw new IllegalArgumentException("manifest field count");
            Member member = new Member(Source.valueOf(fields[0]), required(fields[1]),
                positiveInt(fields[2]), sha256(fields[3]), DomainType.valueOf(fields[4]),
                nonnegativeInt(fields[5]));
            if (!captureIds.add(member.captureId())) {
                throw new IllegalArgumentException("duplicate manifest capture ID");
            }
            members.add(member);
        }
        if (members.isEmpty()) throw new IllegalArgumentException("empty historical manifest");
        return List.copyOf(members);
    }

    static Result verify(List<Member> members, Map<Source, Path> sources) throws Exception {
        if (!sources.keySet().equals(Set.of(Source.legacy, Source.persistent))) {
            throw new IllegalArgumentException("exact approved source identities are required");
        }
        Map<Source, Map<String, Path>> indexes = new EnumMap<>(Source.class);
        for (Source source : Source.values()) {
            Path directory = sources.get(source);
            assertTrue(Files.isDirectory(directory), "missing approved source " + source);
            Map<String, Path> index = new HashMap<>();
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    String id = stringField(json, "capture_id");
                    assertNull(index.put(id, file), "duplicate source capture ID " + id);
                }
            }
            indexes.put(source, index);
        }

        Set<String> manifestIds = new HashSet<>();
        int events = 0;
        int followUps = 0;
        ShakeAlertMessageParser parser = new ShakeAlertMessageParser(
            new ShakeAlertEventParser.Limits(16777216, 50000, 32, 100000, 16777216));
        for (Member member : members) {
            assertTrue(manifestIds.add(member.captureId()), "duplicate manifest capture ID");
            Path file = indexes.get(member.source()).get(member.captureId());
            assertNotNull(file, "missing historical capture " + member.captureId());
            String json = Files.readString(file, StandardCharsets.UTF_8);
            assertEquals(member.captureId(), stringField(json, "capture_id"));
            assertEquals(member.payloadSize(), Integer.parseInt(numberField(json, "payload_size")));
            assertEquals(member.payloadSha256(), stringField(json, "payload_sha256"));
            byte[] payload = Base64.getDecoder().decode(stringField(json, "payload_base64"));
            assertEquals(member.payloadSize(), payload.length, member.captureId());
            MessageEnvelope envelope = new MessageEnvelope(payload,
                Instant.parse(stringField(json, "received_at_utc")), member.captureId(),
                file.toString(), "scenario", "scenario.eew.shakealert.org:61612",
                "eew.test_QuakeLogic-SA1.dm.data", "QuakeLogic-SA1", null, null,
                false, Map.of(), 1);
            assertEquals(member.payloadSha256(), envelope.payloadSha256(), member.captureId());
            ShakeAlertMessage message = parser.parse(envelope);
            if (member.domainType() == DomainType.ShakeAlertEventUpdate) {
                ShakeAlertEventUpdate event = assertInstanceOf(ShakeAlertEventUpdate.class, message);
                assertEquals(member.version(), event.updateVersion(), member.captureId());
                events++;
            } else {
                ShakeAlertFollowUp followUp = assertInstanceOf(ShakeAlertFollowUp.class, message);
                assertEquals(member.version(), followUp.version(), member.captureId());
                followUps++;
            }
        }
        return new Result(members.size(), events, followUps);
    }

    private record Fixture(Path legacy, Path persistent, Path capture, byte[] payload,
                           Member member, Map<Source, Path> sources) {}

    private static Fixture fixture(Path root) throws Exception {
        Path legacy = Files.createDirectories(root.resolve("legacy"));
        Path persistent = Files.createDirectories(root.resolve("persistent"));
        byte[] payload = Files.readAllBytes(
            Path.of("src/test/resources/westmoreland-event-early.xml"));
        String id = "fixture-capture";
        String hash = new MessageEnvelope(payload, Instant.EPOCH, id, "fixture", "scenario",
            "offline", "fixture", "fixture", null, null, false, Map.of(), 1).payloadSha256();
        Path capture = legacy.resolve("fixture.json");
        Files.writeString(capture, captureJson(id, payload));
        Member member = new Member(Source.legacy, id, payload.length, hash,
            DomainType.ShakeAlertEventUpdate, 0);
        return new Fixture(legacy, persistent, capture, payload, member,
            Map.of(Source.legacy, legacy, Source.persistent, persistent));
    }

    private static String captureJson(String id, byte[] payload) {
        MessageEnvelope envelope = new MessageEnvelope(payload, Instant.EPOCH, id, "fixture",
            "scenario", "offline", "fixture", "fixture", null, null, false, Map.of(), 1);
        return "{\"capture_id\":\"" + id + "\","
            + "\"received_at_utc\":\"1970-01-01T00:00:00Z\","
            + "\"payload_size\":" + payload.length + ","
            + "\"payload_sha256\":\"" + envelope.payloadSha256() + "\","
            + "\"payload_base64\":\"" + Base64.getEncoder().encodeToString(payload)
            + "\"}";
    }

    private static String row(Member member) {
        return String.join("\t", member.source().name(), member.captureId(),
            Integer.toString(member.payloadSize()), member.payloadSha256(),
            member.domainType().name(), Integer.toString(member.version()));
    }

    private static String stringField(String json, String name) {
        Matcher matcher = Pattern.compile(String.format(JSON_STRING.pattern(),
            Pattern.quote(name))).matcher(json);
        if (!matcher.find()) throw new AssertionError("missing JSON field " + name);
        return matcher.group(1);
    }

    private static String numberField(String json, String name) {
        Matcher matcher = Pattern.compile(String.format(JSON_NUMBER.pattern(),
            Pattern.quote(name))).matcher(json);
        if (!matcher.find()) throw new AssertionError("missing JSON field " + name);
        return matcher.group(1);
    }

    private static String required(String value) {
        if (value.isBlank()) throw new IllegalArgumentException("blank manifest value");
        return value;
    }
    private static int positiveInt(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) throw new IllegalArgumentException("nonpositive size");
        return parsed;
    }
    private static int nonnegativeInt(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 0) throw new IllegalArgumentException("negative version");
        return parsed;
    }
    private static String sha256(String value) {
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid SHA-256");
        return value;
    }
}
