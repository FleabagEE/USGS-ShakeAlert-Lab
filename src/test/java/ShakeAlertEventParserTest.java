import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import javax.jms.Message;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ShakeAlertEventParserTest {
    private static final String TOPIC = "eew.test_QuakeLogic-SA1.dm.data";
    private static final ShakeAlertEventParser LIMITS = new ShakeAlertEventParser(
        new ShakeAlertEventParser.Limits(65536, 100, 12, 100, 65536));

    @Test void envelopeDefensivelyCopiesMutableInputsAndMetadata() {
        byte[] bytes = early();
        java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
        metadata.put("protocol", "ActiveMQ OpenWire");
        MessageEnvelope envelope = envelope(bytes, "ID:1", false, metadata);
        bytes[0] = 0;
        metadata.put("later", "mutation");
        byte[] returned = envelope.payload();
        returned[0] = 0;

        assertEquals('<', envelope.payload()[0]);
        assertEquals(Map.of("protocol", "ActiveMQ OpenWire"), envelope.metadata());
        assertThrows(UnsupportedOperationException.class,
            () -> envelope.metadata().put("x", "y"));
    }

    @Test void envelopeRejectsMetadataOutsideTheVerifiedAllowlist() {
        assertThrows(IllegalArgumentException.class,
            () -> envelope(early(), null, false, Map.of("arbitrary", "value")));
    }

    @Test void envelopeDerivesPayloadSizeAndSha256() throws Exception {
        byte[] bytes = early();
        MessageEnvelope envelope = envelope(bytes, null, false, Map.of());
        assertEquals(bytes.length, envelope.payloadByteCount());
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            envelope.payloadSha256());
    }

    @Test void strictUtf8RejectsMalformedInputAndBom() {
        assertCategory(new byte[]{(byte) 0xc3, 0x28},
            ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD);
        byte[] plain = early();
        byte[] bom = new byte[plain.length + 3];
        bom[0] = (byte) 0xef; bom[1] = (byte) 0xbb; bom[2] = (byte) 0xbf;
        System.arraycopy(plain, 0, bom, 3, plain.length);
        assertCategory(bom, ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD);
    }

    @Test void malformedXmlIsRejected() {
        assertCategory("<event_message>".getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD);
    }

    @Test void dtdAndExternalEntityAreRejectedWithoutResolution() {
        String xml = "<!DOCTYPE event_message [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>"
            + "<event_message>&xxe;</event_message>";
        assertCategory(xml.getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD);
    }

    @Test void payloadElementDepthAttributeAndTextLimitsAreEnforced() {
        assertCategoryWith(new ShakeAlertEventParser.Limits(10, 10, 10, 10, 10), early(),
            ShakeAlertEventParser.FailureCategory.OVERSIZED_PAYLOAD);
        assertCategoryWith(new ShakeAlertEventParser.Limits(65536, 2, 12, 100, 65536), early(),
            ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD);
        assertCategoryWith(new ShakeAlertEventParser.Limits(65536, 100, 1, 100, 65536), early(),
            ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD);
        assertCategoryWith(new ShakeAlertEventParser.Limits(65536, 100, 12, 2, 65536), early(),
            ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD);
        assertCategoryWith(new ShakeAlertEventParser.Limits(65536, 100, 12, 100, 8), early(),
            ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD);
    }

    @Test void rootMustBeExactEventMessage() {
        String wrong = text(early()).replace("event_message", "EventMessage");
        assertCategory(wrong.getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.UNKNOWN_MESSAGE_TYPE);
    }

    @Test void supportedObservedSchemaParsesEarlyUpdateWithoutGmInfo() throws Exception {
        ShakeAlertEventUpdate update = LIMITS.parse(envelope(early(), "ID:early", false, Map.of()));
        assertEquals(ShakeAlertEventUpdate.MessageType.NEW, update.messageType());
        assertEquals(0, update.updateVersion());
        assertFalse(update.groundMotionInfoPresent());
        assertEquals("2.3.23 2020-04-01", update.algorithmVersion());
    }

    @Test void unsupportedSchemaVersionIsRejected() {
        String unsupported = text(early()).replace("2.3.23 2020-04-01", "9.9 unsupported");
        assertCategory(unsupported.getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA);
    }

    @Test void unknownTopLevelStructureIsRejected() {
        String unsupported = text(early()).replace("<contributors>",
            "<unknown_section/><contributors>");
        assertCategory(unsupported.getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA);
    }

    @Test void unknownMessageTypeIsRejected() {
        String unknown = text(early()).replace("message_type=\"new\"", "message_type=\"cancel\"");
        assertCategory(unknown.getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.UNKNOWN_MESSAGE_TYPE);
    }

    @Test void laterUpdateAllowsObservedOptionalGmInfo() throws Exception {
        ShakeAlertEventUpdate update = LIMITS.parse(envelope(later(), "ID:later", false, Map.of()));
        assertEquals(ShakeAlertEventUpdate.MessageType.UPDATE, update.messageType());
        assertEquals(7, update.updateVersion());
        assertTrue(update.groundMotionInfoPresent());
        assertTrue(update.finiteFault().isEmpty());
    }

    @Test void observedUpdateFiveFiniteFaultParsesIntoImmutableTypedModel() throws Exception {
        ShakeAlertEventUpdate update = LIMITS.parse(
            envelope(faultMinimal(), "ID:fault-5", false, Map.of()));
        assertEquals(5, update.updateVersion());
        assertFalse(update.groundMotionInfoPresent());
        FiniteFault fault = update.finiteFault().orElseThrow();
        assertTrue(fault.attenuationGeometry());
        assertEquals(1, fault.segmentNumber());
        assertEquals("line", fault.segmentShape());
        assertEquals(1, fault.segments().size());
        assertEquals(2, fault.segments().getFirst().vertices().size());
        assertEquals("34.01", fault.segments().getFirst().vertices().getFirst()
            .latitude().toPlainString());
        assertThrows(UnsupportedOperationException.class,
            () -> fault.segments().add(new FaultSegment(java.util.List.of(
                new FaultVertex(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    java.math.BigDecimal.ZERO)))));
        assertThrows(UnsupportedOperationException.class,
            () -> fault.segments().getFirst().vertices().clear());
    }

    @Test void laterFiniteFaultPreservesVertexOrderAndCoexistsWithGmInfo() throws Exception {
        ShakeAlertEventUpdate update = LIMITS.parse(
            envelope(faultLater(), "ID:fault-9", false, Map.of()));
        assertEquals(9, update.updateVersion());
        assertTrue(update.groundMotionInfoPresent());
        var vertices = update.finiteFault().orElseThrow().segments().getFirst().vertices();
        assertEquals(4, vertices.size());
        assertEquals("34.00", vertices.get(0).latitude().toPlainString());
        assertEquals("34.06", vertices.get(3).latitude().toPlainString());
    }

    @Test void unknownFaultChildAttributeAndNamespaceAreRejected() {
        String xml = text(faultMinimal());
        assertCategory(xml.replace("<finite_fault", "<unexpected/><finite_fault")
            .getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA);
        assertCategory(xml.replace("segment_shape=\"line\"",
            "segment_shape=\"line\" extra=\"no\"").getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA);
        assertCategory(xml.replace("<fault_info>", "<fault_info extra=\"no\">")
            .getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA);
        assertCategory(xml.replace("<fault_info>", "<fault_info xmlns=\"urn:unsupported\">")
            .getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA);
    }

    @Test void missingMalformedAndNonFiniteCoordinatesAreRejected() {
        String xml = text(faultMinimal());
        for (String coordinate : java.util.List.of(
                "<lat units=\"deg\">34.01</lat>",
                "<lon units=\"deg\">-117.21</lon>",
                "<depth units=\"km\">0.0</depth>")) {
            assertCategory(xml.replace(coordinate, "").getBytes(StandardCharsets.UTF_8),
                ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD);
        }
        assertCategory(xml.replace(">34.01</lat>", ">not-a-number</lat>")
            .getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD);
        assertCategory(xml.replace(">34.01</lat>", ">NaN</lat>")
            .getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD);
        assertCategory(xml.replace(">34.01</lat>", ">Infinity</lat>")
            .getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD);
    }

    @Test void coordinateBoundsAndUnitsFailClosed() {
        String xml = text(faultMinimal());
        assertCategory(xml.replace(">34.01</lat>", ">91</lat>")
            .getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD);
        assertCategory(xml.replace("<lat units=\"deg\">34.01</lat>",
            "<lat units=\"radian\">34.01</lat>").getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA);
    }

    @Test void finiteFaultCardinalityAndNestingAreBounded() {
        String xml = text(faultMinimal());
        String fault = between(xml, "  <fault_info>", "  </fault_info>");
        assertCategory(xml.replace("</event_message>", fault + "</event_message>")
            .getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD);
        String segment = "<segment><vertices>"
            + "<vertex><lat units=\"deg\">34</lat><lon units=\"deg\">-117</lon>"
            + "<depth units=\"km\">0</depth></vertex></vertices></segment>";
        assertCategory(xml.replace("    </finite_fault>", segment + "    </finite_fault>")
            .getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD);
        assertCategory(xml.replace("<segment><vertices>", "<vertices><segment>")
            .replace("</vertices></segment>", "</segment></vertices>")
            .getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA);
        assertCategory(xml.replace("<fault_info>", "<fault_info>unexpected-text")
            .getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA);
        assertCategory(xml.replace(">34.01</lat>", "><unexpected/>34.01</lat>")
            .getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA);
    }

    @Test void finiteFaultVertexLimitIsEnforcedIndependentlyOfGlobalXmlLimits() {
        String xml = text(faultMinimal());
        String vertex = "<vertex><lat units=\"deg\">34</lat>"
            + "<lon units=\"deg\">-117</lon><depth units=\"km\">0</depth></vertex>";
        String many = vertex.repeat(257);
        String oversizedFault = xml.replaceFirst("(?s)<vertex>.*?</vertex>\\s*<vertex>.*?</vertex>",
            java.util.regex.Matcher.quoteReplacement(many));
        assertCategoryWith(new ShakeAlertEventParser.Limits(262144, 5000, 32, 5000, 262144),
            oversizedFault.getBytes(StandardCharsets.UTF_8),
            ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD);
    }

    @Test void exactIdentityAndCoreFieldsAreExtracted() throws Exception {
        ShakeAlertEventUpdate update = LIMITS.parse(envelope(later(), "ID:later", false, Map.of()));
        assertEquals("fixture-event:7:UPDATE", update.updateIdentity());
        assertEquals("4.6", update.coreInfo().magnitude().toPlainString());
        assertEquals(5, update.coreInfo().stationCount());
        assertEquals(1, update.contributors().size());
    }

    @Test void provenanceIsPreservedFromEnvelope() throws Exception {
        MessageEnvelope envelope = envelope(later(), "ID:provenance", true, Map.of());
        ShakeAlertEventUpdate.Provenance provenance = LIMITS.parse(envelope).provenance();
        assertEquals("capture-fixture", provenance.captureId());
        assertEquals(envelope.payloadSha256(), provenance.payloadSha256());
        assertEquals(Instant.parse("2026-08-18T15:42:30Z"), provenance.receivedAtUtc());
        assertEquals("scenario.eew.shakealert.org:61612", provenance.endpointIdentity());
        assertEquals(TOPIC, provenance.exactDestination());
        assertEquals("ID:provenance", provenance.jmsMessageId());
        assertTrue(provenance.redelivered());
        assertEquals(42, provenance.activationGeneration());
    }

    @Test void deterministicDuplicateRulesPreserveDeliveriesButSuppressDomainProcessing() {
        ShakeAlertEventProcessor processor = new ShakeAlertEventProcessor(LIMITS);
        MessageEnvelope first = envelope(later(), "ID:same", false, Map.of());
        MessageEnvelope brokerDuplicate = envelope(early(), "ID:same", true, Map.of());
        MessageEnvelope contentDuplicate = envelope(later(), "ID:different", false, Map.of());

        assertNull(processor.process(first).rejection());
        assertEquals(ShakeAlertEventParser.FailureCategory.DUPLICATE_DELIVERY,
            processor.process(brokerDuplicate).rejection());
        ShakeAlertEventProcessor.Outcome duplicate = processor.process(contentDuplicate);
        assertEquals(ShakeAlertEventParser.FailureCategory.DUPLICATE_DELIVERY, duplicate.rejection());
        assertTrue(duplicate.domainProcessingSuppressed());
    }

    @Test void expectedParserFailurePreservesCaptureAndContinues(@TempDir Path directory) throws Exception {
        Path capture = directory.resolve("committed.json");
        Files.writeString(capture, "preserved");
        ShakeAlertEventProcessor processor = new ShakeAlertEventProcessor(LIMITS);
        assertEquals(ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD,
            processor.process(envelope("<bad>".getBytes(StandardCharsets.UTF_8), null, false, Map.of())).rejection());
        assertEquals(ShakeAlertEventProcessor.State.RUNNING, processor.state());
        assertEquals("preserved", Files.readString(capture));
        assertNull(processor.process(envelope(early(), "ID:next", false, Map.of())).rejection());
    }

    @Test void unexpectedParserFailureLatchesFailedWithoutRestart(@TempDir Path directory) throws Exception {
        Path capture = directory.resolve("committed.json");
        Files.writeString(capture, "preserved");
        int[] calls = {0};
        ShakeAlertEventProcessor processor = new ShakeAlertEventProcessor(envelope -> {
            calls[0]++; throw new IllegalStateException("programming defect");
        });
        assertEquals(ShakeAlertEventParser.FailureCategory.PARSER_FAILURE,
            processor.process(envelope(early(), null, false, Map.of())).rejection());
        assertEquals(ShakeAlertEventProcessor.State.FAILED, processor.state());
        processor.process(envelope(early(), null, false, Map.of()));
        assertEquals(1, calls[0]);
        assertEquals("preserved", Files.readString(capture));
        assertFalse(processor.failureCategory().contains("programming defect"));
    }

    @Test void envelopeCannotRetainJmsOrTransportNativeObjects() {
        for (Field field : MessageEnvelope.class.getDeclaredFields()) {
            assertFalse(Message.class.isAssignableFrom(field.getType()));
            assertFalse(field.getType().getName().startsWith("org.apache.activemq"));
        }
    }

    private static void assertCategory(byte[] payload, ShakeAlertEventParser.FailureCategory expected) {
        assertCategoryWith(new ShakeAlertEventParser.Limits(65536, 100, 12, 100, 65536), payload, expected);
    }

    private static void assertCategoryWith(ShakeAlertEventParser.Limits limits, byte[] payload,
            ShakeAlertEventParser.FailureCategory expected) {
        ShakeAlertEventParser.ExpectedFailure failure = assertThrows(
            ShakeAlertEventParser.ExpectedFailure.class,
            () -> new ShakeAlertEventParser(limits).parse(envelope(payload, null, false, Map.of())));
        assertEquals(expected, failure.category());
        assertEquals(expected.name(), failure.getMessage());
    }

    private static MessageEnvelope envelope(byte[] payload, String jmsId, boolean redelivered,
            Map<String, String> metadata) {
        return new MessageEnvelope(payload, Instant.parse("2026-08-18T15:42:30Z"),
            "capture-fixture", "fixture/capture.json", "scenario",
            "scenario.eew.shakealert.org:61612", TOPIC, "QuakeLogic-SA1", jmsId,
            Instant.parse("2026-08-18T15:42:29Z"), redelivered, metadata, 42);
    }

    private static byte[] early() { return resource("/westmoreland-event-early.xml"); }
    private static byte[] later() { return resource("/westmoreland-event-later.xml"); }
    private static byte[] faultMinimal() { return resource("/scenario-event-fault-minimal.xml"); }
    private static byte[] faultLater() { return resource("/scenario-event-fault-later.xml"); }
    private static String between(String text, String start, String end) {
        int first = text.indexOf(start);
        int last = text.indexOf(end, first);
        if (first < 0 || last < 0) throw new AssertionError("fixture markers missing");
        return text.substring(first, last + end.length()) + "\n";
    }
    private static byte[] resource(String name) {
        try (var input = ShakeAlertEventParserTest.class.getResourceAsStream(name)) {
            if (input == null) throw new AssertionError("missing fixture " + name);
            return input.readAllBytes();
        } catch (IOException error) { throw new AssertionError(error); }
    }
    private static String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder();
        for (byte item : value) out.append(String.format("%02x", item));
        return out.toString();
    }
}
