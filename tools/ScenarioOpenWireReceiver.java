import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.command.ActiveMQMessage;
import org.apache.activemq.util.ByteSequence;

/** Passive, single-topic OpenWire receiver with capture-before-ack semantics. */
public final class ScenarioOpenWireReceiver {
    private static final String HOST = "scenario.eew.shakealert.org";
    private static final int PORT = 61617;
    private static final String TOPIC = "eew.test_QuakeLogic-SA1.dm.data";
    private static final DateTimeFormatter FILE_TIME =
        DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile(
        "(?im)(authorization\\s*[:=]\\s*)[^\\r\\n]*");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bbearer\\s+[^\\s,;]+");
    private static final Pattern NAMED_SECRET = Pattern.compile(
        "(?i)((?:password|passwd|token|secret)\\s*[=:]\\s*)[^\\s,;]+");

    private ScenarioOpenWireReceiver() {}

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception error) {
            System.err.println("LISTENER_STATE=failed");
            System.err.println("FAILURE_CATEGORY=" +
                (error instanceof javax.jms.JMSSecurityException ? "authentication" : "startup"));
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("expected username-file password-file capture-directory");
        }
        Path usernameFile = Path.of(args[0]);
        Path passwordFile = Path.of(args[1]);
        Path captureDirectory = Path.of(args[2]);
        requireProtected(usernameFile);
        requireProtected(passwordFile);
        Files.createDirectories(captureDirectory);

        char[] username = readSecret(usernameFile);
        char[] password = readSecret(passwordFile);
        AtomicBoolean diagnosticReported = new AtomicBoolean();
        String brokerUrl = "ssl://" + HOST + ":" + PORT
            + "?socket.verifyHostName=true&wireFormat.maxInactivityDuration=30000"
            + "&jms.watchTopicAdvisories=false&jms.useAsyncSend=false";
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(
            new String(password), new String(username), brokerUrl);
        factory.setWatchTopicAdvisories(false);
        factory.setUseAsyncSend(false);
        factory.setAlwaysSyncSend(true);
        factory.setTrustAllPackages(false);

        Connection connection = null;
        Session session = null;
        MessageConsumer consumer = null;
        try {
            connection = factory.createConnection();
            connection.setExceptionListener(error -> {
                System.err.println("LISTENER_STATE=failed");
                System.err.println("FAILURE_CATEGORY=connection");
                reportBrokerFailure(error, username, password, diagnosticReported);
            });
            session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            Topic topic = session.createTopic(TOPIC);
            consumer = session.createConsumer(topic);
            consumer.setMessageListener(message -> {
                try {
                    capture(message, captureDirectory);
                    message.acknowledge();
                } catch (Exception error) {
                    System.err.println("CAPTURE_STATE=failed");
                    System.err.println("FAILURE_CATEGORY=capture");
                }
            });
            connection.start();
            System.out.println("PROTOCOL=ActiveMQ OpenWire");
            System.out.println("PROTOCOL_VERSION=12");
            System.out.println("PORT=" + PORT);
            System.out.println("AUTHENTICATION=success");
            System.out.println("SUBSCRIPTION=" + TOPIC);
            System.out.println("LISTENER_STATE=connected_authenticated_subscribed_waiting");
            System.out.println("CAPTURE_DIRECTORY=" + captureDirectory.toAbsolutePath());
            System.out.flush();
            Object wait = new Object();
            synchronized (wait) {
                while (true) wait.wait();
            }
        } catch (Exception error) {
            reportBrokerFailure(error, username, password, diagnosticReported);
            throw error;
        } finally {
            if (consumer != null) consumer.close();
            if (session != null) session.close();
            if (connection != null) connection.close();
            java.util.Arrays.fill(username, '\0');
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static void requireProtected(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            throw new IOException("credential artifact is not a regular file");
        }
        var permissions = Files.getPosixFilePermissions(path);
        if (permissions.stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"))) {
            throw new IOException("credential artifact permissions are unsafe");
        }
    }

    private static char[] readSecret(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0 || bytes.length > 65536) throw new IOException("credential artifact size is invalid");
        String value = new String(bytes, StandardCharsets.UTF_8);
        java.util.Arrays.fill(bytes, (byte) 0);
        if (value.isEmpty()) throw new IOException("credential artifact is empty");
        return value.toCharArray();
    }


    private static void reportBrokerFailure(
        Exception error, char[] username, char[] password, AtomicBoolean diagnosticReported
    ) {
        if (!diagnosticReported.compareAndSet(false, true)) return;
        Exception linked = error instanceof javax.jms.JMSException jms ? jms.getLinkedException() : null;
        String errorCode = error instanceof javax.jms.JMSException jms ? jms.getErrorCode() : null;
        Throwable nested = firstNested(error, linked);
        String brokerReason = linked != null ? linked.getMessage() : error.getMessage();
        String trace = stackTrace(error, linked);

        System.err.println("BROKER_DIAGNOSTIC_BEGIN");
        System.err.println("JMS_EXCEPTION_CLASS=" + error.getClass().getName());
        System.err.println("JMS_EXCEPTION_MESSAGE=" + safeLine(error.getMessage(), username, password));
        System.err.println("LINKED_EXCEPTION_CLASS=" + className(linked));
        System.err.println("LINKED_EXCEPTION_MESSAGE=" + safeLine(message(linked), username, password));
        System.err.println("ACTIVEMQ_BROKER_ERROR_CODE=" + safeLine(errorCode, username, password));
        System.err.println("ACTIVEMQ_BROKER_REASON=" + safeLine(brokerReason, username, password));
        System.err.println("NESTED_CAUSE_CLASS=" + className(nested));
        System.err.println("NESTED_CAUSE_MESSAGE=" + safeLine(message(nested), username, password));
        System.err.println("AUTHENTICATION_CLASSIFICATION=" + classify(error, linked, errorCode, brokerReason));
        System.err.println("STACK_TRACE_BEGIN");
        System.err.print(redact(trace, username, password));
        if (!trace.endsWith(System.lineSeparator())) System.err.println();
        System.err.println("STACK_TRACE_END");
        System.err.println("BROKER_DIAGNOSTIC_END");
    }

    private static Throwable firstNested(Throwable error, Throwable linked) {
        if (linked != null && linked.getCause() != null) return linked.getCause();
        if (error != null && error.getCause() != linked) return error.getCause();
        return null;
    }

    private static String className(Throwable error) {
        return error == null ? "<none>" : error.getClass().getName();
    }

    private static String message(Throwable error) {
        return error == null ? null : error.getMessage();
    }

    private static String stackTrace(Throwable error, Throwable linked) {
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        error.printStackTrace(writer);
        if (linked != null && linked != error) {
            writer.println("Linked exception:");
            linked.printStackTrace(writer);
        }
        writer.flush();
        return buffer.toString();
    }

    private static String safeLine(String value, char[] username, char[] password) {
        if (value == null) return "<none>";
        return redact(value, username, password).replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String redact(String value, char[] username, char[] password) {
        if (value == null) return null;
        String redacted = replaceSecret(value, username);
        redacted = replaceSecret(redacted, password);
        redacted = AUTHORIZATION_HEADER.matcher(redacted).replaceAll("$1<redacted>");
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("Bearer <redacted>");
        return NAMED_SECRET.matcher(redacted).replaceAll("$1<redacted>");
    }

    private static String replaceSecret(String value, char[] secret) {
        if (secret == null || secret.length == 0) return value;
        return value.replace(new String(secret), "<redacted>");
    }

    private static String classify(
        Exception error, Exception linked, String errorCode, String brokerReason
    ) {
        if (error instanceof javax.jms.InvalidClientIDException
                || linked instanceof javax.jms.InvalidClientIDException) return "CLIENT_ID_REJECTED";
        String evidence = String.join(" ", error.getClass().getName(),
            linked == null ? "" : linked.getClass().getName(), errorCode == null ? "" : errorCode,
            brokerReason == null ? "" : brokerReason).toLowerCase(Locale.ROOT);
        if (containsAny(evidence, "account_disabled", "account disabled", "disabled account",
                "account locked", "locked account", "account inactive")) return "ACCOUNT_DISABLED";
        if (containsAny(evidence, "invalid_username", "invalid username", "username is invalid",
                "unknown user", "user does not exist", "no such user")) return "INVALID_USERNAME";
        if (containsAny(evidence, "username or password", "or password is invalid")) return "UNKNOWN";
        if (containsAny(evidence, "invalid_password", "invalid password", "password is invalid",
                "bad password", "incorrect password")) return "INVALID_PASSWORD";
        if (containsAny(evidence, "client_id_rejected", "invalid client id", "client id is invalid",
                "client id already", "duplicate client id")) return "CLIENT_ID_REJECTED";
        if (containsAny(evidence, "not_authorized", "not authorized", "unauthorized",
                "authorization denied", "permission denied", "not allowed to")) return "NOT_AUTHORIZED";
        if (containsAny(evidence, "broker_configuration", "broker configuration", "loginmodule",
                "login module", "jaas configuration", "security plugin", "authentication plugin")) {
            return "BROKER_CONFIGURATION";
        }
        return "UNKNOWN";
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static void capture(Message message, Path directory) throws Exception {
        Instant received = Instant.now();
        byte[] payload = payload(message);
        byte[] nativeBody = nativeBody(message);
        String destination = destination(message.getJMSDestination());
        if (!TOPIC.equals(destination)) throw new IOException("unexpected destination");

        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("JMSCorrelationID", message.getJMSCorrelationID());
        headers.put("JMSDeliveryMode", message.getJMSDeliveryMode());
        headers.put("JMSDestination", destination);
        headers.put("JMSExpiration", message.getJMSExpiration());
        headers.put("JMSMessageID", message.getJMSMessageID());
        headers.put("JMSPriority", message.getJMSPriority());
        headers.put("JMSRedelivered", message.getJMSRedelivered());
        headers.put("JMSReplyTo", destination(message.getJMSReplyTo()));
        headers.put("JMSTimestamp", message.getJMSTimestamp());
        headers.put("JMSType", message.getJMSType());
        List<String> names = new ArrayList<>();
        Enumeration<?> enumeration = message.getPropertyNames();
        while (enumeration.hasMoreElements()) names.add((String) enumeration.nextElement());
        Collections.sort(names);
        for (String name : names) headers.put("property:" + name, message.getObjectProperty(name));

        String id = UUID.randomUUID().toString();
        String json = "{"
            + "\"capture_id\":" + quote(id) + ","
            + "\"received_at_utc\":" + quote(received.toString()) + ","
            + "\"protocol\":\"ActiveMQ OpenWire\","
            + "\"protocol_version\":12,"
            + "\"exact_destination\":" + quote(destination) + ","
            + "\"message_id\":" + quote(message.getJMSMessageID()) + ","
            + "\"correlation_id\":" + quote(message.getJMSCorrelationID()) + ","
            + "\"payload_size\":" + payload.length + ","
            + "\"payload_sha256\":" + quote(hex(MessageDigest.getInstance("SHA-256").digest(payload))) + ","
            + "\"payload_base64\":" + quote(Base64.getEncoder().encodeToString(payload)) + ","
            + "\"native_marshaled_body_size\":" + nativeBody.length + ","
            + "\"native_marshaled_body_sha256\":" + quote(hex(MessageDigest.getInstance("SHA-256").digest(nativeBody))) + ","
            + "\"native_marshaled_body_base64\":" + quote(Base64.getEncoder().encodeToString(nativeBody)) + ","
            + "\"native_headers\":" + jsonMap(headers)
            + "}\n";
        byte[] record = json.getBytes(StandardCharsets.UTF_8);
        String base = FILE_TIME.format(received) + "_" + id + ".json";
        Path temporary = directory.resolve("." + base + ".tmp");
        Path target = directory.resolve(base);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(record));
            channel.force(true);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, target);
        }
        try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
    }

    private static byte[] payload(Message message) throws Exception {
        if (message instanceof TextMessage text) return text.getText().getBytes(StandardCharsets.UTF_8);
        if (message instanceof BytesMessage bytes) {
            bytes.reset();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = bytes.readBytes(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }
        if (message instanceof ActiveMQMessage active && active.getContent() != null) return copy(active.getContent());
        throw new IOException("unsupported message body type");
    }

    private static byte[] nativeBody(Message message) {
        if (message instanceof ActiveMQMessage active && active.getContent() != null) return copy(active.getContent());
        return new byte[0];
    }

    private static byte[] copy(ByteSequence sequence) {
        return java.util.Arrays.copyOfRange(sequence.data, sequence.offset, sequence.offset + sequence.length);
    }

    private static String destination(Destination destination) throws Exception {
        if (destination == null) return null;
        if (destination instanceof Topic topic) return topic.getTopicName();
        return destination.toString();
    }

    private static String jsonMap(Map<String, Object> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (var entry : values.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append(quote(entry.getKey())).append(':').append(jsonValue(entry.getValue()));
        }
        return out.append('}').toString();
    }

    private static String jsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof byte[] bytes) return quote(Base64.getEncoder().encodeToString(bytes));
        return quote(value.toString());
    }

    private static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c);
                }
            }
        }
        return out.append('\"').toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }
}
