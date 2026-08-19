import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Hardened, offline XML boundary shared by profile dispatch and follow-up parsing. */
final class ShakeAlertXmlSupport {
    private ShakeAlertXmlSupport() {}

    static Document parse(MessageEnvelope envelope, ShakeAlertEventParser.Limits limits)
            throws ShakeAlertEventParser.ExpectedFailure {
        byte[] payload = envelope.payload();
        if (payload.length > limits.maximumPayloadBytes()) {
            throw failure(ShakeAlertEventParser.FailureCategory.OVERSIZED_PAYLOAD);
        }
        if (payload.length >= 3 && payload[0] == (byte) 0xef
                && payload[1] == (byte) 0xbb && payload[2] == (byte) 0xbf) {
            throw malformed();
        }
        requireStrictUtf8(payload);
        validateStructure(payload, limits);
        Document document = parseDocument(payload);
        validateBounds(document.getDocumentElement(), 1, new Counters(), limits);
        return document;
    }

    private static void requireStrictUtf8(byte[] payload)
            throws ShakeAlertEventParser.ExpectedFailure {
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload));
        } catch (CharacterCodingException error) {
            throw new ShakeAlertEventParser.ExpectedFailure(
                ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD, error);
        }
    }

    private static void validateStructure(byte[] payload, ShakeAlertEventParser.Limits limits)
            throws ShakeAlertEventParser.ExpectedFailure {
        try {
            var factory = javax.xml.parsers.SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            var parser = factory.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Counters counters = new Counters();
            var handler = new org.xml.sax.helpers.DefaultHandler() {
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
                @Override public void endElement(String uri, String localName, String qName) {
                    depth--;
                }
                @Override public void characters(char[] value, int start, int length)
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
            throw new ShakeAlertEventParser.ExpectedFailure(
                ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD, error);
        }
    }

    private static Document parseDocument(byte[] payload)
            throws ShakeAlertEventParser.ExpectedFailure {
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
                @Override public void warning(org.xml.sax.SAXParseException error)
                        throws org.xml.sax.SAXException { throw error; }
                @Override public void error(org.xml.sax.SAXParseException error)
                        throws org.xml.sax.SAXException { throw error; }
                @Override public void fatalError(org.xml.sax.SAXParseException error)
                        throws org.xml.sax.SAXException { throw error; }
            });
            builder.setEntityResolver((publicId, systemId) ->
                new InputSource(new java.io.StringReader("")));
            return builder.parse(new ByteArrayInputStream(payload));
        } catch (Exception error) {
            throw new ShakeAlertEventParser.ExpectedFailure(
                ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD, error);
        }
    }

    private static void validateBounds(Element element, int depth, Counters counters,
            ShakeAlertEventParser.Limits limits) throws ShakeAlertEventParser.ExpectedFailure {
        if (element == null || depth > limits.maximumDepth()) throw malformed();
        if (++counters.elements > limits.maximumElements()) throw malformed();
        counters.attributes += element.getAttributes().getLength();
        if (counters.attributes > limits.maximumAttributes()) throw malformed();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element nested) {
                validateBounds(nested, depth + 1, counters, limits);
            } else if (child.getNodeType() == Node.TEXT_NODE
                    || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                counters.text += child.getNodeValue().length();
                if (counters.text > limits.maximumTextCharacters()) throw malformed();
            }
        }
    }

    static ShakeAlertEventParser.ExpectedFailure malformed() {
        return failure(ShakeAlertEventParser.FailureCategory.MALFORMED_PAYLOAD);
    }

    static ShakeAlertEventParser.ExpectedFailure unsupported() {
        return failure(ShakeAlertEventParser.FailureCategory.UNSUPPORTED_SCHEMA);
    }

    private static ShakeAlertEventParser.ExpectedFailure failure(
            ShakeAlertEventParser.FailureCategory category) {
        return new ShakeAlertEventParser.ExpectedFailure(category);
    }

    private static final class Counters { int elements; int attributes; int text; }
}
