import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Strict parser for the two independently observed Scenario version-900 follow-ups. */
final class ShakeAlertFollowUpParser {
    private static final Set<String> ROOT_ATTRIBUTES = Set.of(
        "alg_vers", "category", "instance", "message_type", "orig_sys",
        "ref_id", "ref_src", "timestamp", "version");
    private static final Set<String> CORE_CHILDREN = Set.of(
        "mag", "mag_uncer", "lat", "lat_uncer", "lon", "lon_uncer",
        "depth", "depth_uncer", "orig_time", "orig_time_uncer",
        "likelihood", "num_stations");
    private static final Set<String> CONTRIBUTOR_ATTRIBUTES = Set.of(
        "alg_name", "alg_version", "alg_instance", "category", "event_id", "version");
    private static final Set<String> CONTOUR_CHILDREN = Set.of("MMI", "PGA", "PGV", "polygon");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)");
    private static final int FOLLOW_UP_NOTICE_COUNT = 2;
    private static final int CONTRIBUTOR_COUNT = 2;
    private static final int CONTOUR_COUNT = 4;
    private static final int DECLARED_POLYGON_VERTICES = 8;
    private static final int POLYGON_COORDINATE_PAIRS = 9;
    private static final int MAXIMUM_NOTICE_CHARACTERS = 1024;
    private static final int MAXIMUM_POLYGON_CHARACTERS = 1024;
    private static final int MAXIMUM_DECIMAL_CHARACTERS = 32;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal TWELVE = new BigDecimal("12");
    private static final BigDecimal MINIMUM_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAXIMUM_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MINIMUM_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAXIMUM_LONGITUDE = new BigDecimal("180");
    private static final BigDecimal MINIMUM_DEPTH_KM = new BigDecimal("-20");
    private static final BigDecimal MAXIMUM_DEPTH_KM = new BigDecimal("1000");
    private static final BigDecimal MAXIMUM_ORIGIN_TIME_UNCERTAINTY_SECONDS =
        new BigDecimal("86400");

    ShakeAlertFollowUp parse(MessageEnvelope envelope, Element root)
            throws ShakeAlertEventParser.ExpectedFailure {
        try {
            requireElement(root, "event_message", ROOT_ATTRIBUTES);
            requireExactChildren(root,
                Set.of("core_info", "contributors", "gm_info", "follow_up_info"));

            requireExact(root, "message_type", "follow_up");
            requireExact(root, "version", ShakeAlertMessageParser.FOLLOW_UP_VERSION);
            requireExact(root, "alg_vers", ShakeAlertMessageParser.FOLLOW_UP_ALGORITHM_VERSION);
            requiredAttribute(root, "ref_id");
            requirePresent(root, "ref_src");

            Element core = requiredDirectChild(root, "core_info");
            Element contributors = requiredDirectChild(root, "contributors");
            Element gmInfo = requiredDirectChild(root, "gm_info");
            Element followUpInfo = requiredDirectChild(root, "follow_up_info");

            String eventId = requiredAttribute(core, "id");
            ShakeAlertFollowUp.CoreInfo coreInfo = parseCoreInfo(core);
            List<ShakeAlertEventUpdate.Contributor> contributorList =
                parseContributors(contributors);
            List<FollowUpNotice> notices = parseNotices(followUpInfo);
            List<GroundMotionContour> contours = parseContours(gmInfo);
            ShakeAlertEventUpdate.Provenance provenance = new ShakeAlertEventUpdate.Provenance(
                envelope.captureId(), envelope.payloadSha256(), envelope.receivedAtUtc(),
                envelope.sourceEnvironment(), envelope.endpointIdentity(),
                envelope.exactDestination(), envelope.accountIdentity(),
                envelope.jmsMessageId(), envelope.redelivered(), envelope.activationGeneration());

            return new ShakeAlertFollowUp(
                provenance, eventId, Integer.parseInt(requiredAttribute(root, "version")),
                requiredAttribute(root, "alg_vers"), requiredAttribute(root, "category"),
                requiredAttribute(root, "orig_sys"), requiredAttribute(root, "instance"),
                Instant.parse(requiredAttribute(root, "timestamp")), coreInfo,
                contributorList, notices, contours);
        } catch (NumberFormatException | DateTimeParseException error) {
            throw new ShakeAlertEventParser.ExpectedFailure(
                ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD, error);
        }
    }

    private static ShakeAlertFollowUp.CoreInfo parseCoreInfo(Element core)
            throws ShakeAlertEventParser.ExpectedFailure {
        requireElement(core, "core_info", Set.of("id"));
        requireExactChildren(core, CORE_CHILDREN);
        return new ShakeAlertFollowUp.CoreInfo(
            boundedScalar(core, "mag", "Mw", ZERO, TWELVE),
            boundedScalar(core, "mag_uncer", "Mw", ZERO, TWELVE),
            boundedScalar(core, "lat", "deg", MINIMUM_LATITUDE, MAXIMUM_LATITUDE),
            boundedScalar(core, "lat_uncer", "deg", ZERO, MAXIMUM_LATITUDE),
            boundedScalar(core, "lon", "deg", MINIMUM_LONGITUDE, MAXIMUM_LONGITUDE),
            boundedScalar(core, "lon_uncer", "deg", ZERO, MAXIMUM_LONGITUDE),
            boundedScalar(core, "depth", "km", MINIMUM_DEPTH_KM, MAXIMUM_DEPTH_KM),
            boundedScalar(core, "depth_uncer", "km", ZERO, MAXIMUM_DEPTH_KM),
            Instant.parse(scalarText(core, "orig_time", "UTC")),
            boundedScalar(core, "orig_time_uncer", "sec", ZERO,
                MAXIMUM_ORIGIN_TIME_UNCERTAINTY_SECONDS),
            boundedScalarNoUnits(core, "likelihood", ZERO, ONE),
            nonnegativeInt(scalarTextNoUnits(core, "num_stations")));
    }

    private static List<ShakeAlertEventUpdate.Contributor> parseContributors(Element parent)
            throws ShakeAlertEventParser.ExpectedFailure {
        requireElement(parent, "contributors", Set.of());
        requireOnlyChildren(parent, Set.of("contributor"));
        List<Element> elements = namedDirectChildren(parent, "contributor");
        if (elements.size() != CONTRIBUTOR_COUNT) throw ShakeAlertXmlSupport.malformed();
        List<ShakeAlertEventUpdate.Contributor> result = new ArrayList<>();
        for (Element element : elements) {
            requireElement(element, "contributor", CONTRIBUTOR_ATTRIBUTES);
            requireNoElementChildren(element);
            requireNoNonblankText(element);
            result.add(new ShakeAlertEventUpdate.Contributor(
                requiredAttribute(element, "alg_name"),
                requiredAttribute(element, "alg_version"),
                requiredAttribute(element, "alg_instance"),
                requiredAttribute(element, "category"),
                requiredAttribute(element, "event_id"),
                nonnegativeInt(requiredAttribute(element, "version"))));
        }
        return List.copyOf(result);
    }

    private static List<FollowUpNotice> parseNotices(Element parent)
            throws ShakeAlertEventParser.ExpectedFailure {
        requireElement(parent, "follow_up_info", Set.of("follow_up_type"));
        requireExact(parent, "follow_up_type", "true");
        requireOnlyChildren(parent, Set.of("message_text"));
        List<Element> elements = namedDirectChildren(parent, "message_text");
        if (elements.size() != FOLLOW_UP_NOTICE_COUNT) throw ShakeAlertXmlSupport.malformed();
        EnumSet<FollowUpNotice.Type> seen = EnumSet.noneOf(FollowUpNotice.Type.class);
        List<FollowUpNotice> result = new ArrayList<>();
        for (Element element : elements) {
            requireElement(element, "message_text", Set.of("type"));
            requireNoElementChildren(element);
            String typeText = requiredAttribute(element, "type");
            FollowUpNotice.Type type = switch (typeText) {
                case "short_review" -> FollowUpNotice.Type.SHORT_REVIEW;
                case "wea" -> FollowUpNotice.Type.WEA;
                default -> throw ShakeAlertXmlSupport.unsupported();
            };
            if (!seen.add(type)) throw ShakeAlertXmlSupport.malformed();
            String text = element.getTextContent();
            if (text == null || text.isBlank() || text.indexOf('\n') >= 0
                    || text.indexOf('\r') >= 0
                    || text.codePointCount(0, text.length()) > MAXIMUM_NOTICE_CHARACTERS) {
                throw ShakeAlertXmlSupport.malformed();
            }
            result.add(new FollowUpNotice(type, text));
        }
        if (!seen.equals(EnumSet.allOf(FollowUpNotice.Type.class))) {
            throw ShakeAlertXmlSupport.malformed();
        }
        return List.copyOf(result);
    }

    private static List<GroundMotionContour> parseContours(Element gmInfo)
            throws ShakeAlertEventParser.ExpectedFailure {
        requireElement(gmInfo, "gm_info", Set.of());
        requireExactChildren(gmInfo, Set.of("gmcontour_pred"));
        Element prediction = requiredDirectChild(gmInfo, "gmcontour_pred");
        requireElement(prediction, "gmcontour_pred", Set.of("number"));
        int declared = positiveInt(requiredAttribute(prediction, "number"));
        requireOnlyChildren(prediction, Set.of("contour"));
        List<Element> elements = namedDirectChildren(prediction, "contour");
        if (declared != elements.size() || declared != CONTOUR_COUNT) {
            throw ShakeAlertXmlSupport.malformed();
        }
        List<GroundMotionContour> result = new ArrayList<>();
        for (Element element : elements) result.add(parseContour(element));
        return List.copyOf(result);
    }

    private static GroundMotionContour parseContour(Element contour)
            throws ShakeAlertEventParser.ExpectedFailure {
        requireElement(contour, "contour", Set.of());
        requireExactChildren(contour, CONTOUR_CHILDREN);
        BigDecimal mmi = boundedScalar(contour, "MMI", "", ZERO, TWELVE);
        BigDecimal pga = nonnegativeScalar(contour, "PGA", "cm/s/s");
        BigDecimal pgv = nonnegativeScalar(contour, "PGV", "cm/s");
        Element polygon = requiredDirectChild(contour, "polygon");
        requireElement(polygon, "polygon", Set.of("number"));
        if (positiveInt(requiredAttribute(polygon, "number")) != DECLARED_POLYGON_VERTICES) {
            throw ShakeAlertXmlSupport.malformed();
        }
        requireNoElementChildren(polygon);
        String text = polygon.getTextContent();
        if (text == null || text.isBlank() || text.length() > MAXIMUM_POLYGON_CHARACTERS) {
            throw ShakeAlertXmlSupport.malformed();
        }
        String[] pairs = text.strip().split("\\s+");
        if (pairs.length != POLYGON_COORDINATE_PAIRS
                || pairs.length != DECLARED_POLYGON_VERTICES + 1) {
            throw ShakeAlertXmlSupport.malformed();
        }
        List<GeoCoordinate> coordinates = new ArrayList<>();
        for (String pair : pairs) {
            String[] values = pair.split(",", -1);
            if (values.length != 2) throw ShakeAlertXmlSupport.malformed();
            coordinates.add(new GeoCoordinate(
                boundedDecimal(values[0], MINIMUM_LATITUDE, MAXIMUM_LATITUDE),
                boundedDecimal(values[1], MINIMUM_LONGITUDE, MAXIMUM_LONGITUDE)));
        }
        GeoCoordinate first = coordinates.getFirst();
        GeoCoordinate last = coordinates.getLast();
        if (first.latitude().compareTo(last.latitude()) != 0
                || first.longitude().compareTo(last.longitude()) != 0) {
            throw ShakeAlertXmlSupport.malformed();
        }
        return new GroundMotionContour(mmi, pga, pgv, coordinates);
    }

    private static BigDecimal boundedScalar(Element parent, String name, String units,
            BigDecimal minimum, BigDecimal maximum)
            throws ShakeAlertEventParser.ExpectedFailure {
        return boundedDecimal(scalarText(parent, name, units), minimum, maximum);
    }

    private static BigDecimal boundedScalarNoUnits(Element parent, String name,
            BigDecimal minimum, BigDecimal maximum)
            throws ShakeAlertEventParser.ExpectedFailure {
        return boundedDecimal(scalarTextNoUnits(parent, name), minimum, maximum);
    }

    private static BigDecimal nonnegativeScalar(Element parent, String name, String units)
            throws ShakeAlertEventParser.ExpectedFailure {
        BigDecimal value = decimal(scalarText(parent, name, units));
        if (value.compareTo(ZERO) < 0) throw ShakeAlertXmlSupport.malformed();
        return value;
    }

    private static String scalarText(Element parent, String name, String units)
            throws ShakeAlertEventParser.ExpectedFailure {
        Element element = requiredDirectChild(parent, name);
        requireElement(element, name, Set.of("units"));
        if (!element.hasAttribute("units") || !units.equals(element.getAttribute("units"))) {
            throw ShakeAlertXmlSupport.unsupported();
        }
        return leafText(element);
    }

    private static String scalarTextNoUnits(Element parent, String name)
            throws ShakeAlertEventParser.ExpectedFailure {
        Element element = requiredDirectChild(parent, name);
        requireElement(element, name, Set.of());
        return leafText(element);
    }

    private static String leafText(Element element)
            throws ShakeAlertEventParser.ExpectedFailure {
        requireNoElementChildren(element);
        String value = element.getTextContent();
        if (value == null || value.isBlank()) throw ShakeAlertXmlSupport.malformed();
        return value.strip();
    }

    private static BigDecimal boundedDecimal(String value, BigDecimal minimum,
            BigDecimal maximum) throws ShakeAlertEventParser.ExpectedFailure {
        BigDecimal result = decimal(value);
        if (result.compareTo(minimum) < 0 || result.compareTo(maximum) > 0) {
            throw ShakeAlertXmlSupport.malformed();
        }
        return result;
    }

    private static BigDecimal decimal(String value)
            throws ShakeAlertEventParser.ExpectedFailure {
        if (value == null || value.length() > MAXIMUM_DECIMAL_CHARACTERS
                || !DECIMAL.matcher(value).matches()) {
            throw ShakeAlertXmlSupport.malformed();
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException error) {
            throw new ShakeAlertEventParser.ExpectedFailure(
                ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD, error);
        }
    }

    private static int positiveInt(String value)
            throws ShakeAlertEventParser.ExpectedFailure {
        try {
            int result = Integer.parseInt(value);
            if (result <= 0) throw ShakeAlertXmlSupport.malformed();
            return result;
        } catch (NumberFormatException error) {
            throw new ShakeAlertEventParser.ExpectedFailure(
                ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD, error);
        }
    }

    private static int nonnegativeInt(String value)
            throws ShakeAlertEventParser.ExpectedFailure {
        try {
            int result = Integer.parseInt(value);
            if (result < 0) throw ShakeAlertXmlSupport.malformed();
            return result;
        } catch (NumberFormatException error) {
            throw new ShakeAlertEventParser.ExpectedFailure(
                ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD, error);
        }
    }

    private static void requireElement(Element element, String name, Set<String> attributes)
            throws ShakeAlertEventParser.ExpectedFailure {
        if (!name.equals(element.getTagName()) || hasNamespace(element)) {
            throw ShakeAlertXmlSupport.unsupported();
        }
        requireOnlyAttributes(element, attributes);
    }

    private static void requireExactChildren(Element parent, Set<String> names)
            throws ShakeAlertEventParser.ExpectedFailure {
        requireOnlyChildren(parent, names);
        for (String name : names) {
            if (namedDirectChildren(parent, name).size() != 1) {
                throw ShakeAlertXmlSupport.malformed();
            }
        }
    }

    private static void requireOnlyChildren(Element parent, Set<String> names)
            throws ShakeAlertEventParser.ExpectedFailure {
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element) {
                if (!names.contains(element.getTagName()) || hasNamespace(element)) {
                    throw ShakeAlertXmlSupport.unsupported();
                }
            } else if ((node.getNodeType() == Node.TEXT_NODE
                    || node.getNodeType() == Node.CDATA_SECTION_NODE)
                    && !node.getNodeValue().isBlank()) {
                throw ShakeAlertXmlSupport.unsupported();
            }
        }
    }

    private static void requireOnlyAttributes(Element element, Set<String> names)
            throws ShakeAlertEventParser.ExpectedFailure {
        for (int index = 0; index < element.getAttributes().getLength(); index++) {
            if (!names.contains(element.getAttributes().item(index).getNodeName())) {
                throw ShakeAlertXmlSupport.unsupported();
            }
        }
    }

    private static void requireNoElementChildren(Element element)
            throws ShakeAlertEventParser.ExpectedFailure {
        if (!directChildren(element).isEmpty()) throw ShakeAlertXmlSupport.unsupported();
    }

    private static void requireNoNonblankText(Element element)
            throws ShakeAlertEventParser.ExpectedFailure {
        String text = element.getTextContent();
        if (text != null && !text.isBlank()) throw ShakeAlertXmlSupport.unsupported();
    }

    private static Element requiredDirectChild(Element parent, String name)
            throws ShakeAlertEventParser.ExpectedFailure {
        List<Element> elements = namedDirectChildren(parent, name);
        if (elements.size() != 1) throw ShakeAlertXmlSupport.malformed();
        return elements.getFirst();
    }

    private static List<Element> namedDirectChildren(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Element child : directChildren(parent)) {
            if (name.equals(child.getTagName())) result.add(child);
        }
        return result;
    }

    private static List<Element> directChildren(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element element) result.add(element);
        }
        return result;
    }

    private static String requiredAttribute(Element element, String name)
            throws ShakeAlertEventParser.ExpectedFailure {
        if (!element.hasAttribute(name) || element.getAttribute(name).isEmpty()) {
            throw ShakeAlertXmlSupport.malformed();
        }
        return element.getAttribute(name);
    }

    private static void requirePresent(Element element, String name)
            throws ShakeAlertEventParser.ExpectedFailure {
        if (!element.hasAttribute(name)) throw ShakeAlertXmlSupport.malformed();
    }

    private static void requireExact(Element element, String name, String expected)
            throws ShakeAlertEventParser.ExpectedFailure {
        if (!expected.equals(requiredAttribute(element, name))) {
            throw ShakeAlertXmlSupport.unsupported();
        }
    }

    private static boolean hasNamespace(Element element) {
        return element.getNamespaceURI() != null && !element.getNamespaceURI().isEmpty();
    }
}
