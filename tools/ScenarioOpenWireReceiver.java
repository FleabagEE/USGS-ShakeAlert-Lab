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
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.function.Consumer;

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
    private static final String AUTHORIZED_SCENARIO_HOST = "scenario.eew.shakealert.org";
    private static final int AUTHORIZED_SCENARIO_PORT = 61612;
    private static final Pattern ACCOUNT_ID = Pattern.compile("QuakeLogic-SA1");
    private static final Path SCENARIO_CREDENTIAL_ROOT = Path.of("credentials", "scenario");
    private static final String EXPECTED_CREDENTIAL_OWNER = "quakelogic";
    private static final Pattern EXACT_EVENT_TOPIC = Pattern.compile(
        "eew\\.test_[A-Za-z0-9][A-Za-z0-9-]{0,63}\\.dm\\.data");
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

    record Endpoint(String host, int port) {}

    record CredentialFiles(Path directory, Path username, Path password) {}

    record AuthenticatedSession(Connection connection, Session session) {}

    static final class NativeCaptureCommit {
        private final byte[] payload;
        private final Instant receivedAtUtc;
        private final String captureId;
        private final String captureReference;
        private final String jmsMessageId;
        private final Instant brokerTimestamp;
        private final boolean redelivered;

        NativeCaptureCommit(byte[] payload, Instant receivedAtUtc, String captureId,
                String captureReference, String jmsMessageId, Instant brokerTimestamp,
                boolean redelivered) {
            this.payload = payload.clone();
            this.receivedAtUtc = receivedAtUtc;
            this.captureId = captureId;
            this.captureReference = captureReference;
            this.jmsMessageId = jmsMessageId;
            this.brokerTimestamp = brokerTimestamp;
            this.redelivered = redelivered;
        }
        byte[] payload() { return payload.clone(); }
        Instant receivedAtUtc() { return receivedAtUtc; }
        String captureId() { return captureId; }
        String captureReference() { return captureReference; }
        String jmsMessageId() { return jmsMessageId; }
        Instant brokerTimestamp() { return brokerTimestamp; }
        boolean redelivered() { return redelivered; }
    }

    @FunctionalInterface
    interface ConnectionSupplier { Connection create() throws Exception; }

    @FunctionalInterface
    interface ConnectionConfigurer { void configure(Connection connection) throws Exception; }

    private static void lifecycle(String event, String accountId, String topic) {
        StringBuilder record = new StringBuilder("{\"timestamp_utc\":")
            .append(quote(Instant.now().toString()))
            .append(",\"event\":").append(quote(event))
            .append(",\"account_id\":").append(quote(accountId));
        if (topic != null) record.append(",\"destination\":").append(quote(topic));
        System.out.println(record.append('}'));
        System.out.flush();
    }

    static Endpoint validatedEndpoint(String host, String portText) {
        if (!AUTHORIZED_SCENARIO_HOST.equals(host)) {
            throw new IllegalArgumentException("host is not the authorized Scenario endpoint");
        }
        final int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("port must be an integer", error);
        }
        if (port != AUTHORIZED_SCENARIO_PORT) {
            throw new IllegalArgumentException("port is not the authorized Scenario OpenWire port");
        }
        return new Endpoint(host, port);
    }

    private static int validatedMaximumPayloadBytes(String value) {
        final int maximum;
        try {
            maximum = Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("maximum-payload-bytes must be an integer", error);
        }
        if (maximum <= 0 || maximum > 16777216) {
            throw new IllegalArgumentException("maximum-payload-bytes must be between 1 and 16777216");
        }
        return maximum;
    }

    static String brokerUrl(Endpoint endpoint) {
        return "ssl://" + endpoint.host() + ":" + endpoint.port()
            + "?socket.verifyHostName=true&wireFormat.maxInactivityDuration=30000"
            + "&jms.watchTopicAdvisories=false&jms.useAsyncSend=false";
    }

    static Path accountCredentialDirectory(Path root, String accountId) {
        if (!ACCOUNT_ID.matcher(accountId).matches()) {
            throw new IllegalArgumentException("account-id is not an approved explicit account identity");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path directory = normalizedRoot.resolve(accountId).normalize();
        if (!directory.getParent().equals(normalizedRoot) || !directory.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("account credential directory escapes the Scenario credential root");
        }
        return directory;
    }

    static boolean exactEventTopic(String topic) {
        return topic != null && EXACT_EVENT_TOPIC.matcher(topic).matches();
    }

    static AuthenticatedSession establishAuthenticatedSession(
        ConnectionSupplier supplier, ConnectionConfigurer configurer, Consumer<String> events
    ) throws Exception {
        Connection connection = supplier.create();
        events.accept("CONNECTED");
        try {
            configurer.configure(connection);
            Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            events.accept("AUTHENTICATED");
            events.accept("SESSION_CREATED");
            return new AuthenticatedSession(connection, session);
        } catch (Exception error) {
            connection.close();
            throw error;
        }
    }

    static MessageConsumer createPassiveTopicConsumer(Session session, String topic) throws Exception {
        Topic destination = session.createTopic(topic);
        return session.createConsumer(destination, null, false);
    }

    static void beforePayloadValidation(Consumer<String> events, Runnable validation) {
        events.accept("MESSAGE_CALLBACK");
        validation.run();
    }

    private static CredentialFiles credentialFiles(String accountId) throws IOException {
        return credentialFiles(SCENARIO_CREDENTIAL_ROOT, accountId, EXPECTED_CREDENTIAL_OWNER);
    }

    static CredentialFiles credentialFiles(Path root, String accountId, String expectedOwner) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path rootReal = requireProtectedDirectory(normalizedRoot, expectedOwner);
        Path directoryReal = requireProtectedDirectory(accountCredentialDirectory(normalizedRoot, accountId), expectedOwner);
        if (!directoryReal.getParent().equals(rootReal) || !directoryReal.startsWith(rootReal)) {
            throw new IOException("account credential directory escapes the Scenario credential root");
        }
        Path usernameFile = requireProtected(directoryReal.resolve("username"), expectedOwner);
        Path passwordFile = requireProtected(directoryReal.resolve("password"), expectedOwner);
        if (!usernameFile.getParent().equals(directoryReal) || !passwordFile.getParent().equals(directoryReal)) {
            throw new IOException("credential artifact escapes the account credential directory");
        }
        byte[] expected = accountId.getBytes(StandardCharsets.UTF_8);
        byte[] actual = Files.readAllBytes(usernameFile);
        boolean equal = MessageDigest.isEqual(actual, expected);
        java.util.Arrays.fill(actual, (byte) 0);
        java.util.Arrays.fill(expected, (byte) 0);
        if (!equal) throw new IOException("credential username does not exactly match account-id");
        return new CredentialFiles(directoryReal, usernameFile, passwordFile);
    }

    static ActiveMQConnectionFactory connectionFactory(String username, String password, String url) {
        return new ActiveMQConnectionFactory(username, password, url);
    }

    private static void authenticateOnly(Endpoint endpoint, String accountId) throws Exception {
        CredentialFiles credentialFiles = credentialFiles(accountId);
        Path usernameFile = credentialFiles.username();
        Path passwordFile = credentialFiles.password();

        char[] username = readSecret(usernameFile);
        char[] password = readSecret(passwordFile);
        AtomicBoolean diagnosticReported = new AtomicBoolean();
        ActiveMQConnectionFactory factory = connectionFactory(
            new String(username), new String(password), brokerUrl(endpoint));
        factory.setWatchTopicAdvisories(false);
        factory.setUseAsyncSend(false);
        factory.setAlwaysSyncSend(true);
        factory.setTrustAllPackages(false);

        Connection connection = null;
        Session session = null;
        try {
            AuthenticatedSession authenticated = establishAuthenticatedSession(
                factory::createConnection, ignored -> {}, event -> lifecycle(event, accountId, null));
            connection = authenticated.connection();
            session = authenticated.session();
            System.out.println("PROTOCOL=ActiveMQ OpenWire");
            System.out.println("HOST=" + endpoint.host());
            System.out.println("PORT=" + endpoint.port());
            System.out.println("AUTHENTICATION=success");
            System.out.println("SESSION=created");
            System.out.println("SUBSCRIPTION=not_created");
            System.out.println("LISTENER_STATE=closed_after_authentication");
            System.out.flush();
        } catch (Exception error) {
            reportBrokerFailure(error, username, password, diagnosticReported);
            throw error;
        } finally {
            if (session != null) session.close();
            if (connection != null) {
                connection.close();
                lifecycle("DISCONNECTED", accountId, null);
            }
            java.util.Arrays.fill(username, '\0');
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length == 2 && "--authenticate-only".equals(args[0])) {
            Endpoint endpoint = new Endpoint(AUTHORIZED_SCENARIO_HOST, AUTHORIZED_SCENARIO_PORT);
            authenticateOnly(endpoint, args[1]);
            return;
        }
        if ((args.length != 7 && args.length != 9) || !"--subscribe".equals(args[0])) {
            throw new IllegalArgumentException(
                "expected either --authenticate-only account-id "
                + "or --subscribe host port account-id exact-event-topic "
                + "capture-root maximum-payload-bytes [health-file rejection-directory]");
        }
        Endpoint endpoint = validatedEndpoint(args[1], args[2]);
        String accountId = args[3];
        if (!ACCOUNT_ID.matcher(accountId).matches()) {
            throw new IllegalArgumentException("account-id is not an approved explicit account identity");
        }
        String topic = args[4];
        if (!exactEventTopic(topic)) {
            throw new IllegalArgumentException(
                "exact-event-topic is not an approved non-wildcard Event destination");
        }
        CredentialFiles credentialFiles = credentialFiles(accountId);
        Path usernameFile = credentialFiles.username();
        Path passwordFile = credentialFiles.password();
        Path captureDirectory = Path.of(args[5]).resolve(accountId).resolve(topic);
        int maximumPayloadBytes = validatedMaximumPayloadBytes(args[6]);
        Files.createDirectories(captureDirectory);

        char[] username = readSecret(usernameFile);
        char[] password = readSecret(passwordFile);
        AtomicBoolean diagnosticReported = new AtomicBoolean();
        ActiveMQConnectionFactory factory = connectionFactory(
            new String(username), new String(password), brokerUrl(endpoint));
        factory.setWatchTopicAdvisories(false);
        factory.setUseAsyncSend(false);
        factory.setAlwaysSyncSend(true);
        factory.setTrustAllPackages(false);

        ShakeAlertEventParser parser = new ShakeAlertEventParser(
            new ShakeAlertEventParser.Limits(maximumPayloadBytes, 50000, 32, 100000,
                maximumPayloadBytes));
        ShakeAlertEventProcessor processor = new ShakeAlertEventProcessor(parser);
        LocalHealthStatus healthStatus = args.length == 9
            ? new LocalHealthStatus(Path.of(args[7]), processor) : null;
        SanitizedRejectionStore rejectionStore = args.length == 9
            ? new SanitizedRejectionStore(Path.of(args[8]),
                new SanitizedRejectionStore.Retention(1000, 67108864L, Duration.ofDays(30)))
            : null;
        ScenarioReceiverService service = null;
        Thread shutdownHook = null;
        try {
            ScenarioReceiverService.InstanceLock instanceLock =
                ScenarioReceiverService.acquireInstanceLock(
                    args.length == 9
                        ? Path.of(args[7]).toAbsolutePath().normalize().getParent()
                            .resolve("scenario-receiver.lock")
                        : Path.of(args[5]).resolve(".scenario-receiver.lock"));
            service = new ScenarioReceiverService(
                factory, accountId, "scenario-openwire", topic,
                (message, generation) -> {
                    beforePayloadValidation(
                        event -> lifecycle(event, accountId, topic), () -> {});
                    NativeCaptureCommit committed = capture(
                        message, captureDirectory, topic, maximumPayloadBytes);
                    lifecycle("CAPTURE_COMMITTED", accountId, topic);
                    return new MessageEnvelope(
                        committed.payload(), committed.receivedAtUtc(), committed.captureId(),
                        committed.captureReference(), "scenario",
                        endpoint.host() + ":" + endpoint.port(), topic, accountId,
                        committed.jmsMessageId(), committed.brokerTimestamp(),
                        committed.redelivered(),
                        Map.of("protocol", "ActiveMQ OpenWire", "protocol_version", "12"),
                        generation);
                },
                envelope -> {
                    ShakeAlertEventProcessor.Outcome outcome = processor.process(envelope);
                    lifecycle(outcome.rejection() == null ? "EVENT_PARSED"
                        : "EVENT_REJECTED_" + outcome.rejection().name(), accountId, topic);
                    if (outcome.rejection() != null
                            && outcome.rejection() != ShakeAlertEventParser.FailureCategory.PARSER_FAILURE
                            && rejectionStore != null) {
                        rejectionStore.record(envelope, outcome);
                    }
                },
                healthStatus == null ? snapshot -> {} : healthStatus,
                instanceLock);
            ScenarioReceiverService ownedService = service;
            shutdownHook = new Thread(() -> {
                ownedService.requestShutdown();
                try {
                    ownedService.awaitCoordinatorTeardown(Duration.ofSeconds(35));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "scenario-receiver-shutdown-request");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            service.start();
            System.out.println("PROTOCOL=ActiveMQ OpenWire");
            System.out.println("PROTOCOL_VERSION=12");
            System.out.println("HOST=" + endpoint.host());
            System.out.println("PORT=" + endpoint.port());
            System.out.println("AUTHENTICATION=success");
            System.out.println("SUBSCRIPTION=" + topic);
            System.out.println("LISTENER_STATE=connected_authenticated_subscribed_waiting");
            System.out.println("CAPTURE_DIRECTORY=" + captureDirectory.toAbsolutePath());
            System.out.flush();
            service.awaitShutdownRequest();
            ScenarioReceiverService.LifecycleState finalState =
                service.stop(Duration.ofSeconds(30));
            if (finalState == ScenarioReceiverService.LifecycleState.FAILED) {
                throw new IOException("Scenario receiver service failed");
            }
        } catch (Exception error) {
            reportBrokerFailure(error, username, password, diagnosticReported);
            throw error;
        } finally {
            if (service != null && service.state() != ScenarioReceiverService.LifecycleState.STOPPED) {
                service.requestShutdown();
                service.stop(Duration.ofSeconds(30));
            }
            if (shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown is already in progress; the hook only requests shutdown.
                }
            }
            java.util.Arrays.fill(username, '\0');
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static Path requireProtectedDirectory(Path path, String expectedOwner) throws IOException {
        if (!Files.isDirectory(path) || Files.isSymbolicLink(path)) {
            throw new IOException("credential directory is not a non-symlink directory");
        }
        if (!Files.getPosixFilePermissions(path).equals(Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))) {
            throw new IOException("credential directory mode is not 0700");
        }
        requireOwner(path, expectedOwner);
        return path.toRealPath();
    }

    private static Path requireProtected(Path path, String expectedOwner) throws IOException {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            throw new IOException("credential artifact is not a regular file");
        }
        if (!Files.getPosixFilePermissions(path).equals(Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE))) {
            throw new IOException("credential artifact mode is not 0600");
        }
        requireOwner(path, expectedOwner);
        Path real = path.toRealPath();
        if (Files.size(real) == 0) throw new IOException("credential artifact is empty");
        return real;
    }

    private static void requireOwner(Path path, String expectedOwner) throws IOException {
        String owner = Files.getOwner(path).getName();
        int separator = Math.max(owner.lastIndexOf('\\'), owner.lastIndexOf('/'));
        if (separator >= 0) owner = owner.substring(separator + 1);
        if (!expectedOwner.equals(owner)) {
            throw new IOException("credential artifact owner is not the expected service owner");
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

    static NativeCaptureCommit capture(
        Message message, Path directory, String topic, int maximumPayloadBytes
    ) throws Exception {
        Instant received = Instant.now();
        byte[] payload = payload(message, maximumPayloadBytes);
        byte[] nativeBody = nativeBody(message);
        if (nativeBody.length > maximumPayloadBytes) {
            throw new IOException("native message body exceeds configured maximum");
        }
        String destination = destination(message.getJMSDestination());
        if (!topic.equals(destination)) throw new IOException("unexpected destination");

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
        Instant brokerTimestamp = message.getJMSTimestamp() > 0
            ? Instant.ofEpochMilli(message.getJMSTimestamp()) : null;
        return new NativeCaptureCommit(
            payload, received, id, target.toAbsolutePath().normalize().toString(),
            message.getJMSMessageID(), brokerTimestamp, message.getJMSRedelivered());
    }

    private static byte[] payload(Message message, int maximumPayloadBytes) throws Exception {
        if (message instanceof TextMessage text) {
            byte[] payload = text.getText().getBytes(StandardCharsets.UTF_8);
            if (payload.length > maximumPayloadBytes) {
                throw new IOException("message payload exceeds configured maximum");
            }
            return payload;
        }
        if (message instanceof BytesMessage bytes) {
            if (bytes.getBodyLength() > maximumPayloadBytes) {
                throw new IOException("message payload exceeds configured maximum");
            }
            bytes.reset();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = bytes.readBytes(buffer)) != -1) {
                if (out.size() + count > maximumPayloadBytes) {
                    throw new IOException("message payload exceeds configured maximum");
                }
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
        if (message instanceof ActiveMQMessage active && active.getContent() != null) {
            byte[] payload = copy(active.getContent());
            if (payload.length > maximumPayloadBytes) {
                throw new IOException("message payload exceeds configured maximum");
            }
            return payload;
        }
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
