import java.util.Set;

import org.w3c.dom.Element;

/** Strict profile dispatcher; unsupported discriminator combinations fail closed. */
final class ShakeAlertMessageParser {
    static final String EVENT_ALGORITHM_VERSION = "2.3.23 2020-04-01";
    static final String FOLLOW_UP_ALGORITHM_VERSION = "1.1.1 2019-04-17";
    static final String FOLLOW_UP_VERSION = "900";

    private final ShakeAlertEventParser eventParser;
    private final ShakeAlertFollowUpParser followUpParser;
    private final ShakeAlertEventParser.Limits limits;

    ShakeAlertMessageParser(ShakeAlertEventParser.Limits limits) {
        this.limits = limits;
        this.eventParser = new ShakeAlertEventParser(limits);
        this.followUpParser = new ShakeAlertFollowUpParser();
    }

    ShakeAlertMessage parse(MessageEnvelope envelope)
            throws ShakeAlertEventParser.ExpectedFailure {
        Element root = ShakeAlertXmlSupport.parse(envelope, limits).getDocumentElement();
        if (root == null || !"event_message".equals(root.getTagName())
                || hasNamespace(root)) {
            throw new ShakeAlertEventParser.ExpectedFailure(
                ShakeAlertEventParser.FailureCategory.UNKNOWN_MESSAGE_TYPE);
        }
        String messageType = requiredAttribute(root, "message_type");
        String algorithmVersion = requiredAttribute(root, "alg_vers");
        String version = requiredAttribute(root, "version");

        if (Set.of("new", "update").contains(messageType)
                && EVENT_ALGORITHM_VERSION.equals(algorithmVersion)) {
            return eventParser.parse(envelope);
        }
        if ("follow_up".equals(messageType)
                && FOLLOW_UP_VERSION.equals(version)
                && FOLLOW_UP_ALGORITHM_VERSION.equals(algorithmVersion)) {
            return followUpParser.parse(envelope, root);
        }
        throw ShakeAlertXmlSupport.unsupported();
    }

    private static String requiredAttribute(Element element, String name)
            throws ShakeAlertEventParser.ExpectedFailure {
        if (!element.hasAttribute(name) || element.getAttribute(name).isEmpty()) {
            throw ShakeAlertXmlSupport.malformed();
        }
        return element.getAttribute(name);
    }

    private static boolean hasNamespace(Element element) {
        return element.getNamespaceURI() != null && !element.getNamespaceURI().isEmpty();
    }
}
