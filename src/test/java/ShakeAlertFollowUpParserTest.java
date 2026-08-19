import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

final class ShakeAlertFollowUpParserTest {
    private static final String TOPIC = "eew.test_QuakeLogic-SA1.dm.data";
    private static final ShakeAlertEventParser.Limits LIMITS =
        new ShakeAlertEventParser.Limits(65536, 500, 16, 500, 65536);
    private static final ShakeAlertMessageParser PARSER = new ShakeAlertMessageParser(LIMITS);

    @Test void dispatcherKeepsNormalEventProfileTypedAndUnchanged() throws Exception {
        ShakeAlertMessage message = PARSER.parse(envelope(resource("/westmoreland-event-later.xml")));
        assertInstanceOf(ShakeAlertEventUpdate.class, message);
        ShakeAlertEventUpdate update = (ShakeAlertEventUpdate) message;
        assertEquals(7, update.updateVersion());
        assertEquals("2.3.23 2020-04-01", update.algorithmVersion());
    }

    @Test void validFollowUpParsesIntoTypedImmutableDomain() throws Exception {
        String xml = fixture();
        ShakeAlertMessage parsed = PARSER.parse(envelope(bytes(xml)));
        ShakeAlertFollowUp followUp = assertInstanceOf(ShakeAlertFollowUp.class, parsed);
        assertEquals("synthetic-follow-up-event:900:FOLLOW_UP", followUp.messageIdentity());
        assertEquals(900, followUp.version());
        assertEquals("1.1.1 2019-04-17", followUp.algorithmVersion());
        assertEquals(2, followUp.contributors().size());
        assertEquals(2, followUp.notices().size());
        assertEquals(FollowUpNotice.Type.SHORT_REVIEW, followUp.notices().getFirst().type());
        assertEquals(textBetween(xml, "<message_text type=\"short_review\">", "</message_text>"),
            followUp.notices().getFirst().text());
        assertEquals(4, followUp.contours().size());
        assertEquals(9, followUp.contours().getFirst().polygon().size());
        assertEquals(followUp.contours().getFirst().polygon().getFirst(),
            followUp.contours().getFirst().polygon().getLast());
        assertThrows(UnsupportedOperationException.class, () -> followUp.notices().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> followUp.contours().getFirst().polygon().clear());
    }

    @Test void secondSafeFollowUpFixtureAlsoParses() throws Exception {
        ShakeAlertFollowUp followUp = assertInstanceOf(ShakeAlertFollowUp.class,
            PARSER.parse(envelope(resource("/scenario-follow-up-900-alternate.xml"))));
        assertEquals("synthetic-follow-up-alternate", followUp.eventId());
        assertEquals("5.1", followUp.coreInfo().magnitude().toPlainString());
        assertEquals("4.5",
            followUp.contours().getLast().modifiedMercalliIntensity().toPlainString());
    }

    @Test void unsupportedDiscriminatorCombinationsFailClosed() {
        String xml = fixture();
        assertCategory(xml.replace("message_type=\"follow_up\"", "message_type=\"update\""),
            ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA);
        assertCategory(xml.replace("version=\"900\"", "version=\"901\""),
            ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA);
        assertCategory(xml.replace("1.1.1 2019-04-17", "1.1.2 unsupported"),
            ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA);
    }

    @Test void normalEventParserDoesNotAcceptFollowUpProfile() {
        ShakeAlertEventParser parser = new ShakeAlertEventParser(LIMITS);
        ShakeAlertEventParser.ExpectedFailure failure = assertThrows(
            ShakeAlertEventParser.ExpectedFailure.class,
            () -> parser.parse(envelope(bytes(fixture()))));
        assertEquals(ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA, failure.category());
    }

    @Test void topLevelAndNamespaceContractsFailClosed() {
        String xml = fixture();
        assertCategory(xml.replace("<follow_up_info", "<unknown/><follow_up_info"),
            ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA);
        assertRejected(xml.replace("<follow_up_info", "<follow_up_info xmlns=\"urn:no\""));
        assertRejected(xml.replace("<event_message", "<event_message xmlns=\"urn:no\""));
    }

    @Test void followUpInfoCardinalityAndAttributeAreExact() {
        String xml = fixture();
        String section = textBetweenIncluding(xml, "  <follow_up_info", "  </follow_up_info>");
        assertRejected(xml.replace(section, ""));
        assertRejected(xml.replace("</event_message>", section + "\n</event_message>"));
        assertRejected(xml.replace("follow_up_type=\"true\"", "follow_up_type=\"false\""));
        assertRejected(xml.replace("follow_up_type=\"true\"",
            "follow_up_type=\"true\" extra=\"no\""));
    }

    @Test void messageTextTypesAndCardinalityAreExact() {
        String xml = fixture();
        String shortNotice = textBetweenIncluding(xml,
            "    <message_text type=\"short_review\">", "</message_text>");
        assertRejected(xml.replace(shortNotice, ""));
        assertRejected(xml.replace("type=\"wea\"", "type=\"short_review\""));
        assertRejected(xml.replace("type=\"wea\"", "type=\"unknown\""));
        assertRejected(xml.replace("type=\"wea\"", ""));
    }

    @Test void messageTextRejectsNestedEmptyMultilineAndOversizedContent() {
        String xml = fixture();
        String notice = textBetween(xml, "<message_text type=\"short_review\">", "</message_text>");
        assertRejected(xml.replace(notice, "<nested/>"));
        assertRejected(xml.replace(notice, ""));
        assertRejected(xml.replace(notice, "line one\nline two"));
        assertRejected(xml.replace(notice, "x".repeat(1025)));
    }

    @Test void gmContourPredictionMustBeUniqueAndCountExactlyFour() {
        String xml = fixture();
        String prediction = textBetweenIncluding(xml, "    <gmcontour_pred", "    </gmcontour_pred>");
        assertRejected(xml.replace(prediction, ""));
        assertRejected(xml.replace("  </gm_info>", prediction + "\n  </gm_info>"));
        assertRejected(xml.replace("gmcontour_pred number=\"4\"",
            "gmcontour_pred number=\"3\""));
        String contour = textBetweenIncluding(xml, "      <contour>", "</contour>");
        assertRejected(xml.replace(contour, ""));
    }

    @Test void contourChildrenAreExactAndRequired() {
        String xml = fixture();
        assertRejected(xml.replace("<MMI units=\"\">2.0</MMI>",
            "<unknown/><MMI units=\"\">2.0</MMI>"));
        for (String value : new String[] {
                "<MMI units=\"\">2.0</MMI>",
                "<PGA units=\"cm/s/s\">1.2</PGA>",
                "<PGV units=\"cm/s\">0.3</PGV>",
                "<polygon number=\"8\">33.00,-115.70 33.00,-115.60 33.00,-115.50 33.05,-115.45 33.10,-115.50 33.10,-115.60 33.10,-115.70 33.05,-115.75 33.00,-115.70</polygon>"
            }) {
            assertRejected(xml.replaceFirst(java.util.regex.Pattern.quote(value), ""));
        }
    }

    @Test void unitsAndAttributesAreExact() {
        String xml = fixture();
        assertRejected(xml.replace("<MMI units=\"\">", "<MMI units=\"mmi\">"));
        assertRejected(xml.replace("<PGA units=\"cm/s/s\">", "<PGA units=\"g\">"));
        assertRejected(xml.replace("<PGV units=\"cm/s\">", "<PGV units=\"m/s\">"));
        assertRejected(xml.replace("<PGA units=\"cm/s/s\">",
            "<PGA units=\"cm/s/s\" extra=\"no\">"));
    }

    @Test void groundMotionNumbersAreFiniteBoundedAndNonnegative() {
        String xml = fixture();
        for (String invalid : new String[] {"malformed", "NaN", "Infinity", "-1"}) {
            assertRejected(xml.replace(">1.2</PGA>", ">" + invalid + "</PGA>"));
            assertRejected(xml.replace(">0.3</PGV>", ">" + invalid + "</PGV>"));
        }
        assertRejected(xml.replace(">2.0</MMI>", ">13</MMI>"));
        assertRejected(xml.replace(">2.0</MMI>", ">NaN</MMI>"));
    }

    @Test void polygonDeclarationPairGrammarBoundsAndClosureAreExact() {
        String xml = fixture();
        assertRejected(xml.replaceFirst("polygon number=\"8\"", "polygon number=\"7\""));
        assertRejected(xml.replaceFirst("33.00,-115.70", "malformed"));
        assertRejected(xml.replaceFirst("33.00,-115.70", "91,-115.70"));
        assertRejected(xml.replaceFirst("33.00,-115.70", "33.00,-181"));
        String closed = "33.00,-115.70 33.00,-115.60 33.00,-115.50 33.05,-115.45 "
            + "33.10,-115.50 33.10,-115.60 33.10,-115.70 33.05,-115.75 33.00,-115.70";
        assertRejected(xml.replaceFirst(java.util.regex.Pattern.quote(closed),
            closed.substring(0, closed.length() - "33.00,-115.70".length()) + "33.01,-115.70"));
        assertRejected(xml.replaceFirst(java.util.regex.Pattern.quote(closed),
            closed + " 33.00,-115.70"));
    }

    @Test void duplicateRulesWorkAcrossTypedFollowUps() {
        ShakeAlertEventProcessor processor = new ShakeAlertEventProcessor(PARSER);
        MessageEnvelope first = envelope(bytes(fixture()), "ID:follow-up", false);
        MessageEnvelope brokerDuplicate = envelope(
            resource("/scenario-follow-up-900-alternate.xml"), "ID:follow-up", true);
        MessageEnvelope contentDuplicate = envelope(bytes(fixture()), "ID:other", false);
        assertNull(processor.process(first).rejection());
        assertEquals(ShakeAlertEventParser.FailureCategory.DUPLICATE_DELIVERY,
            processor.process(brokerDuplicate).rejection());
        assertEquals(ShakeAlertEventParser.FailureCategory.DUPLICATE_DELIVERY,
            processor.process(contentDuplicate).rejection());
    }

    private static void assertCategory(String xml,
            ShakeAlertEventParser.FailureCategory category) {
        ShakeAlertEventParser.ExpectedFailure failure = assertThrows(
            ShakeAlertEventParser.ExpectedFailure.class,
            () -> PARSER.parse(envelope(bytes(xml))));
        assertEquals(category, failure.category());
    }

    private static void assertRejected(String xml) {
        assertThrows(ShakeAlertEventParser.ExpectedFailure.class,
            () -> PARSER.parse(envelope(bytes(xml))));
    }

    private static MessageEnvelope envelope(byte[] payload) {
        return envelope(payload, "ID:fixture", false);
    }

    private static MessageEnvelope envelope(byte[] payload, String jmsId, boolean redelivered) {
        return new MessageEnvelope(payload, Instant.parse("2026-08-18T23:40:22Z"),
            "capture-follow-up", "fixture/follow-up.json", "scenario",
            "scenario.eew.shakealert.org:61612", TOPIC, "QuakeLogic-SA1", jmsId,
            Instant.parse("2026-08-18T23:40:21Z"), redelivered,
            Map.of("protocol", "ActiveMQ OpenWire"), 77);
    }

    private static String fixture() {
        return new String(resource("/scenario-follow-up-900.xml"), StandardCharsets.UTF_8);
    }

    private static byte[] resource(String name) {
        try (var input = ShakeAlertFollowUpParserTest.class.getResourceAsStream(name)) {
            if (input == null) throw new AssertionError("missing fixture " + name);
            return input.readAllBytes();
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static String textBetween(String text, String start, String end) {
        int first = text.indexOf(start);
        if (first < 0) throw new AssertionError("fixture start marker missing");
        first += start.length();
        int last = text.indexOf(end, first);
        if (last < 0) throw new AssertionError("fixture end marker missing");
        return text.substring(first, last);
    }

    private static String textBetweenIncluding(String text, String start, String end) {
        int first = text.indexOf(start);
        int last = text.indexOf(end, first);
        if (first < 0 || last < 0) throw new AssertionError("fixture markers missing");
        return text.substring(first, last + end.length());
    }
}
