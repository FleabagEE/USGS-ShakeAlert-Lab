import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Strict, offline-only parser for the observed ShakeAlert Event XML profile. */
final class ShakeAlertEventParser {
    enum FailureCategory {
        MALFORMED_PAYLOAD, UNKNOWN_MESSAGE_TYPE, UNSUPPORTED_SCHEMA,
        OVERSIZED_PAYLOAD, DUPLICATE_DELIVERY, PARSER_FAILURE
    }

    static final class ExpectedFailure extends Exception {
        private final FailureCategory category;
        ExpectedFailure(FailureCategory category) { super(category.name()); this.category = category; }
        ExpectedFailure(FailureCategory category, Throwable cause) {
            super(category.name(), cause); this.category = category;
        }
        FailureCategory category() { return category; }
    }

    record Limits(int maximumPayloadBytes, int maximumElements, int maximumDepth,
                  int maximumAttributes, int maximumTextCharacters) {
        Limits {
            if (maximumPayloadBytes <= 0 || maximumElements <= 0 || maximumDepth <= 0
                    || maximumAttributes <= 0 || maximumTextCharacters <= 0) {
                throw new IllegalArgumentException("all parser limits must be positive");
            }
        }
    }

    private static final String SUPPORTED_ALGORITHM_VERSION = "2.3.23 2020-04-01";
    private static final Set<String> ROOT_ATTRIBUTES = Set.of(
        "alg_vers", "category", "instance", "message_type", "orig_sys",
        "ref_id", "ref_src", "timestamp", "version");
    private static final Set<String> FINITE_FAULT_ATTRIBUTES = Set.of(
        "atten_geom", "segment_number", "segment_shape");
    private static final Set<String> UNITS_ATTRIBUTE = Set.of("units");
    private static final int MAXIMUM_FAULT_INFO = 1;
    private static final int MAXIMUM_FINITE_FAULTS = 1;
    private static final int MAXIMUM_SEGMENTS = 1;
    // The observed sequence grows to 12 vertices. 256 leaves ample evolution room while bounded.
    private static final int MAXIMUM_VERTICES_PER_SEGMENT = 256;
    private static final int MAXIMUM_TOTAL_VERTICES = 256;
    private static final BigDecimal MINIMUM_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAXIMUM_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MINIMUM_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAXIMUM_LONGITUDE = new BigDecimal("180");
    // Kilometres; allows shallow above-datum geometry and deep faults without unbounded values.
    private static final BigDecimal MINIMUM_DEPTH_KM = new BigDecimal("-20");
    private static final BigDecimal MAXIMUM_DEPTH_KM = new BigDecimal("1000");
    private final Limits limits;

    ShakeAlertEventParser(Limits limits) { this.limits = limits; }

    ShakeAlertEventUpdate parse(MessageEnvelope envelope) throws ExpectedFailure {
        byte[] payload = envelope.payload();
        if (payload.length > limits.maximumPayloadBytes()) {
            throw new ExpectedFailure(FailureCategory.OVERSIZED_PAYLOAD);
        }
        if (payload.length >= 3 && payload[0] == (byte) 0xef
                && payload[1] == (byte) 0xbb && payload[2] == (byte) 0xbf) {
            throw new ExpectedFailure(FailureCategory.MALFORMED_PAYLOAD);
        }
        requireStrictUtf8(payload);
        validateStructure(payload);
        Document document = parseDocument(payload);
        Element root = document.getDocumentElement();
        if (root == null || !"event_message".equals(root.getTagName())
                || (root.getNamespaceURI() != null && !root.getNamespaceURI().isEmpty())) {
            throw new ExpectedFailure(FailureCategory.UNKNOWN_MESSAGE_TYPE);
        }
        validateBounds(root, 1, new Counters());
        requireOnlyAttributes(root, ROOT_ATTRIBUTES);
        requireOnlyTopLevelChildren(root,
            Set.of("core_info", "contributors", "gm_info", "fault_info"));

        String algorithmVersion = requiredAttribute(root, "alg_vers");
        if (!SUPPORTED_ALGORITHM_VERSION.equals(algorithmVersion)) {
            throw new ExpectedFailure(FailureCategory.UNSUPPORTED_SCHEMA);
        }
        ShakeAlertEventUpdate.MessageType messageType = switch (requiredAttribute(root, "message_type")) {
            case "new" -> ShakeAlertEventUpdate.MessageType.NEW;
            case "update" -> ShakeAlertEventUpdate.MessageType.UPDATE;
            default -> throw new ExpectedFailure(FailureCategory.UNKNOWN_MESSAGE_TYPE);
        };

        try {
            int updateVersion = nonnegativeInt(requiredAttribute(root, "version"));
            Element core = requiredDirectChild(root, "core_info");
            Element contributorsElement = requiredDirectChild(root, "contributors");
            boolean gmInfo = optionalDirectChild(root, "gm_info") != null;
            Optional<FiniteFault> finiteFault = finiteFault(root);
            String eventId = requiredAttribute(core, "id");
            ShakeAlertEventUpdate.CoreInfo coreInfo = new ShakeAlertEventUpdate.CoreInfo(
                Instant.parse(requiredText(core, "orig_time")),
                decimal(requiredText(core, "lat")), decimal(requiredText(core, "lon")),
                decimal(requiredText(core, "depth")), decimal(requiredText(core, "mag")),
                decimal(requiredText(core, "likelihood")),
                nonnegativeInt(requiredText(core, "num_stations")));
            List<ShakeAlertEventUpdate.Contributor> contributors = contributors(contributorsElement);
            ShakeAlertEventUpdate.Provenance provenance = new ShakeAlertEventUpdate.Provenance(
                envelope.captureId(), envelope.payloadSha256(), envelope.receivedAtUtc(),
                envelope.sourceEnvironment(), envelope.endpointIdentity(), envelope.exactDestination(),
                envelope.accountIdentity(), envelope.jmsMessageId(), envelope.redelivered(),
                envelope.activationGeneration());
            return new ShakeAlertEventUpdate(
                provenance, messageType, updateVersion, eventId, algorithmVersion,
                requiredAttribute(root, "category"), requiredAttribute(root, "orig_sys"),
                requiredAttribute(root, "instance"),
                Instant.parse(requiredAttribute(root, "timestamp")), coreInfo,
                contributors, gmInfo, finiteFault);
        } catch (NumberFormatException | DateTimeParseException error) {
            throw new ExpectedFailure(FailureCategory.MALFORMED_PAYLOAD, error);
        }
    }

    private static void requireStrictUtf8(byte[] payload) throws ExpectedFailure {
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload));
        } catch (CharacterCodingException error) {
            throw new ExpectedFailure(FailureCategory.MALFORMED_PAYLOAD, error);
        }
    }

    private void validateStructure(byte[] payload) throws ExpectedFailure {
        try {
            javax.xml.parsers.SAXParserFactory factory = javax.xml.parsers.SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            javax.xml.parsers.SAXParser parser = factory.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Counters counters = new Counters();
            org.xml.sax.helpers.DefaultHandler handler = new org.xml.sax.helpers.DefaultHandler() {
                private int depth;
                @Override public void startElement(String uri, String localName, String qName,
                        org.xml.sax.Attributes attributes) throws org.xml.sax.SAXException {
                    depth++;
                    counters.elements++;
                    counters.attributes += attributes.getLength();
                    if (depth > limits.maximumDepth()
                            || counters.elements > limits.maximumElements()
                            || counters.attributes > limits.maximumAttributes()) {
                        throw new org.xml.sax.SAXException("structural limit exceeded");
                    }
                }
                @Override public void endElement(String uri, String localName, String qName) { depth--; }
                @Override public void characters(char[] characters, int start, int length)
                        throws org.xml.sax.SAXException {
                    counters.text += length;
                    if (counters.text > limits.maximumTextCharacters()) {
                        throw new org.xml.sax.SAXException("text limit exceeded");
                    }
                }
            };
            var reader = parser.getXMLReader();
            reader.setEntityResolver((publicId, systemId) ->
                new InputSource(new java.io.StringReader("")));
            reader.setErrorHandler(handler);
            reader.setContentHandler(handler);
            reader.parse(new InputSource(new ByteArrayInputStream(payload)));
        } catch (Exception error) {
            throw new ExpectedFailure(FailureCategory.MALFORMED_PAYLOAD, error);
        }
    }

    private static Document parseDocument(byte[] payload) throws ExpectedFailure {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void warning(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
                @Override public void error(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
            });
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new java.io.StringReader("")));
            return builder.parse(new ByteArrayInputStream(payload));
        } catch (Exception error) {
            throw new ExpectedFailure(FailureCategory.MALFORMED_PAYLOAD, error);
        }
    }

    private void validateBounds(Element element, int depth, Counters counters) throws ExpectedFailure {
        if (depth > limits.maximumDepth()) throw malformed();
        if (++counters.elements > limits.maximumElements()) throw malformed();
        counters.attributes += element.getAttributes().getLength();
        if (counters.attributes > limits.maximumAttributes()) throw malformed();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element nested) validateBounds(nested, depth + 1, counters);
            else if (child.getNodeType() == Node.TEXT_NODE
                    || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                counters.text += child.getNodeValue().length();
                if (counters.text > limits.maximumTextCharacters()) throw malformed();
            }
        }
    }

    private static List<ShakeAlertEventUpdate.Contributor> contributors(Element parent)
            throws ExpectedFailure {
        List<ShakeAlertEventUpdate.Contributor> result = new ArrayList<>();
        for (Element child : directChildren(parent)) {
            if (!"contributor".equals(child.getTagName())) throw malformed();
            result.add(new ShakeAlertEventUpdate.Contributor(
                requiredAttribute(child, "alg_name"), requiredAttribute(child, "alg_version"),
                requiredAttribute(child, "alg_instance"), requiredAttribute(child, "category"),
                requiredAttribute(child, "event_id"),
                nonnegativeInt(requiredAttribute(child, "version"))));
        }
        if (result.isEmpty()) throw malformed();
        return List.copyOf(result);
    }

    private static Optional<FiniteFault> finiteFault(Element root) throws ExpectedFailure {
        List<Element> faultInfos = namedDirectChildren(root, "fault_info");
        if (faultInfos.size() > MAXIMUM_FAULT_INFO) throw malformed();
        if (faultInfos.isEmpty()) return Optional.empty();

        Element faultInfo = faultInfos.getFirst();
        requireElementContract(faultInfo, "fault_info", Set.of());
        List<Element> finiteFaults = namedDirectChildren(faultInfo, "finite_fault");
        requireOnlyChildren(faultInfo, Set.of("finite_fault"));
        if (finiteFaults.size() != 1 || finiteFaults.size() > MAXIMUM_FINITE_FAULTS) {
            throw malformed();
        }

        Element finiteFault = finiteFaults.getFirst();
        requireElementContract(finiteFault, "finite_fault", FINITE_FAULT_ATTRIBUTES);
        if (!"true".equals(requiredAttribute(finiteFault, "atten_geom"))) {
            throw new ExpectedFailure(FailureCategory.UNSUPPORTED_SCHEMA);
        }
        int segmentNumber = positiveInt(requiredAttribute(finiteFault, "segment_number"));
        String segmentShape = requiredAttribute(finiteFault, "segment_shape");
        if (!"line".equals(segmentShape)) {
            throw new ExpectedFailure(FailureCategory.UNSUPPORTED_SCHEMA);
        }

        requireOnlyChildren(finiteFault, Set.of("segment"));
        List<Element> segmentElements = namedDirectChildren(finiteFault, "segment");
        if (segmentElements.isEmpty() || segmentElements.size() > MAXIMUM_SEGMENTS) throw malformed();
        List<FaultSegment> segments = new ArrayList<>();
        int totalVertices = 0;
        for (Element segmentElement : segmentElements) {
            requireElementContract(segmentElement, "segment", Set.of());
            requireOnlyChildren(segmentElement, Set.of("vertices"));
            Element verticesElement = requiredDirectChild(segmentElement, "vertices");
            requireElementContract(verticesElement, "vertices", Set.of());
            requireOnlyChildren(verticesElement, Set.of("vertex"));
            List<Element> vertexElements = namedDirectChildren(verticesElement, "vertex");
            if (vertexElements.isEmpty() || vertexElements.size() > MAXIMUM_VERTICES_PER_SEGMENT) {
                throw malformed();
            }
            totalVertices += vertexElements.size();
            if (totalVertices > MAXIMUM_TOTAL_VERTICES) throw malformed();
            List<FaultVertex> vertices = new ArrayList<>();
            for (Element vertexElement : vertexElements) vertices.add(faultVertex(vertexElement));
            segments.add(new FaultSegment(vertices));
        }
        return Optional.of(new FiniteFault(true, segmentNumber, segmentShape, segments));
    }

    private static FaultVertex faultVertex(Element vertex) throws ExpectedFailure {
        requireElementContract(vertex, "vertex", Set.of());
        requireOnlyChildren(vertex, Set.of("lat", "lon", "depth"));
        Element latitudeElement = requiredDirectChild(vertex, "lat");
        Element longitudeElement = requiredDirectChild(vertex, "lon");
        Element depthElement = requiredDirectChild(vertex, "depth");
        requireCoordinateContract(latitudeElement, "lat", "deg");
        requireCoordinateContract(longitudeElement, "lon", "deg");
        requireCoordinateContract(depthElement, "depth", "km");
        BigDecimal latitude = boundedDecimal(latitudeElement.getTextContent(),
            MINIMUM_LATITUDE, MAXIMUM_LATITUDE);
        BigDecimal longitude = boundedDecimal(longitudeElement.getTextContent(),
            MINIMUM_LONGITUDE, MAXIMUM_LONGITUDE);
        BigDecimal depth = boundedDecimal(depthElement.getTextContent(),
            MINIMUM_DEPTH_KM, MAXIMUM_DEPTH_KM);
        return new FaultVertex(latitude, longitude, depth);
    }

    private static void requireCoordinateContract(Element element, String name, String units)
            throws ExpectedFailure {
        requireElementContract(element, name, UNITS_ATTRIBUTE);
        requireNoElementChildren(element);
        if (!units.equals(requiredAttribute(element, "units"))) {
            throw new ExpectedFailure(FailureCategory.UNSUPPORTED_SCHEMA);
        }
    }

    private static void requireElementContract(Element element, String name, Set<String> attributes)
            throws ExpectedFailure {
        if (!name.equals(element.getTagName())
                || (element.getNamespaceURI() != null && !element.getNamespaceURI().isEmpty())) {
            throw new ExpectedFailure(FailureCategory.UNSUPPORTED_SCHEMA);
        }
        requireOnlyAttributes(element, attributes);
    }

    private static void requireOnlyChildren(Element element, Set<String> names)
            throws ExpectedFailure {
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element nested) {
                if (!names.contains(nested.getTagName())
                        || (nested.getNamespaceURI() != null
                            && !nested.getNamespaceURI().isEmpty())) {
                    throw new ExpectedFailure(FailureCategory.UNSUPPORTED_SCHEMA);
                }
            } else if ((child.getNodeType() == Node.TEXT_NODE
                    || child.getNodeType() == Node.CDATA_SECTION_NODE)
                    && !child.getNodeValue().isBlank()) {
                throw new ExpectedFailure(FailureCategory.UNSUPPORTED_SCHEMA);
            }
        }
    }

    private static void requireNoElementChildren(Element element) throws ExpectedFailure {
        for (Element ignored : directChildren(element)) {
            throw new ExpectedFailure(FailureCategory.UNSUPPORTED_SCHEMA);
        }
    }

    private static List<Element> namedDirectChildren(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Element child : directChildren(parent)) if (name.equals(child.getTagName())) result.add(child);
        return result;
    }

    private static Element requiredDirectChild(Element parent, String name) throws ExpectedFailure {
        Element result = optionalDirectChild(parent, name);
        if (result == null) throw malformed();
        return result;
    }

    private static Element optionalDirectChild(Element parent, String name) throws ExpectedFailure {
        Element result = null;
        for (Element child : directChildren(parent)) {
            if (name.equals(child.getTagName())) {
                if (result != null) throw malformed();
                result = child;
            }
        }
        return result;
    }

    private static List<Element> directChildren(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element) result.add(element);
        }
        return result;
    }

    private static String requiredText(Element parent, String name) throws ExpectedFailure {
        String value = requiredDirectChild(parent, name).getTextContent();
        if (value == null || value.isBlank()) throw malformed();
        return value.strip();
    }

    private static String requiredAttribute(Element element, String name) throws ExpectedFailure {
        if (!element.hasAttribute(name) || element.getAttribute(name).isEmpty()) throw malformed();
        return element.getAttribute(name);
    }

    private static void requireOnlyTopLevelChildren(Element root, Set<String> names)
            throws ExpectedFailure {
        for (Element child : directChildren(root)) {
            if (!names.contains(child.getTagName())
                    || (child.getNamespaceURI() != null && !child.getNamespaceURI().isEmpty())) {
                throw new ExpectedFailure(FailureCategory.UNSUPPORTED_SCHEMA);
            }
        }
    }

    private static void requireOnlyAttributes(Element element, Set<String> names) throws ExpectedFailure {
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            if (!names.contains(element.getAttributes().item(i).getNodeName())) {
                throw new ExpectedFailure(FailureCategory.UNSUPPORTED_SCHEMA);
            }
        }
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
    private static BigDecimal boundedDecimal(String value, BigDecimal minimum, BigDecimal maximum)
            throws ExpectedFailure {
        if (value == null || value.isBlank()) throw malformed();
        BigDecimal result = decimal(value.strip());
        if (result.compareTo(minimum) < 0 || result.compareTo(maximum) > 0) throw malformed();
        return result;
    }
    private static int positiveInt(String value) throws ExpectedFailure {
        int result = Integer.parseInt(value);
        if (result <= 0) throw malformed();
        return result;
    }
    private static int nonnegativeInt(String value) throws ExpectedFailure {
        int result = Integer.parseInt(value);
        if (result < 0) throw malformed();
        return result;
    }
    private static ExpectedFailure malformed() {
        return new ExpectedFailure(FailureCategory.MALFORMED_PAYLOAD);
    }
    private static final class Counters { int elements; int attributes; int text; }
}
