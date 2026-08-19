import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Explicit opt-in, read-only regression over operator-provided native capture directories. */
final class ShakeAlertHistoricalCaptureRegressionTest {
    private static final Pattern JSON_STRING =
        Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern JSON_NUMBER =
        Pattern.compile("\"%s\"\\s*:\\s*([0-9]+)");

    @Test void operatorProvidedHistoricalCapturesRemainIntactAndParse() throws Exception {
        String configured = System.getProperty("shakealert.capture.directories", "");
        Assumptions.assumeFalse(configured.isBlank(),
            "set -Dshakealert.capture.directories for the read-only historical regression");
        List<Path> files = new ArrayList<>();
        for (String item : configured.split(Pattern.quote(java.io.File.pathSeparator))) {
            Path directory = Path.of(item);
            assertTrue(Files.isDirectory(directory), directory.toString());
            try (var stream = Files.list(directory)) {
                files.addAll(stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList());
            }
        }
        files.sort(Comparator.comparing(Path::toString));

        ShakeAlertMessageParser parser = new ShakeAlertMessageParser(
            new ShakeAlertEventParser.Limits(16777216, 50000, 32, 100000, 16777216));
        int eventCount = 0;
        int followUpCount = 0;
        for (Path file : files) {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            byte[] payload = Base64.getDecoder().decode(stringField(json, "payload_base64"));
            int storedSize = Integer.parseInt(numberField(json, "payload_size"));
            String storedHash = stringField(json, "payload_sha256");
            assertEquals(storedSize, payload.length, file.toString());

            MessageEnvelope envelope = new MessageEnvelope(
                payload, Instant.parse(stringField(json, "received_at_utc")),
                stringField(json, "capture_id"), file.toString(), "scenario",
                "scenario.eew.shakealert.org:61612",
                "eew.test_QuakeLogic-SA1.dm.data", "QuakeLogic-SA1",
                null, null, false, Map.of(), 1);
            assertEquals(storedHash, envelope.payloadSha256(), file.toString());
            ShakeAlertMessage message = parser.parse(envelope);
            if (message instanceof ShakeAlertEventUpdate) {
                eventCount++;
            } else {
                ShakeAlertFollowUp followUp = assertInstanceOf(ShakeAlertFollowUp.class, message);
                assertEquals(900, followUp.version(), file.toString());
                assertEquals("1.1.1 2019-04-17", followUp.algorithmVersion(), file.toString());
                followUpCount++;
            }
        }
        assertEquals(28, files.size());
        assertEquals(26, eventCount);
        assertEquals(2, followUpCount);
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
}
