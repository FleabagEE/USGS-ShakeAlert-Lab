import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.Topic;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ScenarioOpenWireReceiverTest {
    @Test void runtimeAsyncFailureKeepsSanitizedTerminalCategory() {
        Exception failure = new ScenarioOpenWireReceiver.TerminalServiceException(
            "INACTIVITY_TIMEOUT");
        assertEquals("INACTIVITY_TIMEOUT",
            ScenarioOpenWireReceiver.terminalFailureCategory(failure));
        assertNotEquals("startup", ScenarioOpenWireReceiver.terminalFailureCategory(failure));
        assertEquals("startup", ScenarioOpenWireReceiver.terminalFailureCategory(
            new java.io.IOException("private startup detail")));
    }

    private static final String ACCOUNT = "QuakeLogic-SA1";

    @Test void usernameAndPasswordHaveCorrectSemanticOrder() {
        ActiveMQConnectionFactory factory = ScenarioOpenWireReceiver.connectionFactory(
            "user-marker", "password-marker", "vm://offline");
        assertEquals("user-marker", factory.getUserName());
        assertEquals("password-marker", factory.getPassword());
    }

    @Test void connectionAloneCannotEmitAuthenticationSuccess() {
        List<String> events = new ArrayList<>();
        AtomicBoolean created = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        Connection connection = connectionProxy(null, new JMSException("offline session failure"), closed);

        assertThrows(JMSException.class, () -> ScenarioOpenWireReceiver.establishAuthenticatedSession(
            () -> { created.set(true); return connection; }, ignored -> {}, events::add));

        assertTrue(created.get());
        assertTrue(closed.get());
        assertEquals(List.of("CONNECTED"), events);
        assertFalse(events.contains("AUTHENTICATED"));
    }

    @Test void sessionFailurePreventsAuthenticationSuccess() {
        List<String> events = new ArrayList<>();
        AtomicBoolean configured = new AtomicBoolean();
        Connection connection = connectionProxy(null, new JMSException("offline session failure"), new AtomicBoolean());

        assertThrows(JMSException.class, () -> ScenarioOpenWireReceiver.establishAuthenticatedSession(
            () -> connection, ignored -> configured.set(true), events::add));

        assertTrue(configured.get());
        assertEquals(List.of("CONNECTED"), events);
    }

    @Test void sessionSuccessIsRequiredForAuthenticationEvents() throws Exception {
        List<String> events = new ArrayList<>();
        Session session = proxy(Session.class, (method, args) -> defaultValue(method.getReturnType()));
        Connection connection = connectionProxy(session, null, new AtomicBoolean());

        ScenarioOpenWireReceiver.AuthenticatedSession authenticated =
            ScenarioOpenWireReceiver.establishAuthenticatedSession(
                () -> connection, ignored -> {}, events::add);

        assertSame(connection, authenticated.connection());
        assertSame(session, authenticated.session());
        assertEquals(List.of("CONNECTED", "AUTHENTICATED", "SESSION_CREATED"), events);
    }

    @Test void accountCredentialSelectionEnforcesFilesystemBoundary(@TempDir Path temporary) throws Exception {
        Path root = temporary.resolve("credentials/scenario");
        Files.createDirectories(root);
        mode(root, 0700);
        String owner = owner(root);

        ScenarioOpenWireReceiver.CredentialFiles selected = credentialTree(root, owner, ACCOUNT.getBytes(StandardCharsets.UTF_8));
        assertEquals(root.toRealPath().resolve(ACCOUNT), selected.directory());
        assertTrue(selected.directory().startsWith(root.toRealPath()));
        assertEquals(selected.directory().resolve("username"), selected.username());
        assertEquals(selected.directory().resolve("password"), selected.password());

        assertThrows(IllegalArgumentException.class,
            () -> ScenarioOpenWireReceiver.credentialFiles(root, "../" + ACCOUNT, owner));
        assertThrows(IllegalArgumentException.class,
            () -> ScenarioOpenWireReceiver.credentialFiles(root, temporary.resolve(ACCOUNT).toString(), owner));

        mode(selected.directory(), 0755);
        assertThrows(IOException.class,
            () -> ScenarioOpenWireReceiver.credentialFiles(root, ACCOUNT, owner));
        mode(selected.directory(), 0700);
        mode(selected.password(), 0644);
        assertThrows(IOException.class,
            () -> ScenarioOpenWireReceiver.credentialFiles(root, ACCOUNT, owner));
        mode(selected.password(), 0600);

        assertThrows(IOException.class,
            () -> ScenarioOpenWireReceiver.credentialFiles(root, ACCOUNT, owner + "-wrong"));

        Files.write(selected.username(), (ACCOUNT + "\n").getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class,
            () -> ScenarioOpenWireReceiver.credentialFiles(root, ACCOUNT, owner));
        Files.write(selected.username(), ACCOUNT.getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(ACCOUNT.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(selected.username()));

        Path linkedRoot = temporary.resolve("linked/scenario");
        Files.createDirectories(linkedRoot);
        mode(linkedRoot, 0700);
        Path outside = temporary.resolve("outside-account");
        Files.createDirectories(outside);
        mode(outside, 0700);
        Files.createSymbolicLink(linkedRoot.resolve(ACCOUNT), outside);
        assertThrows(IOException.class,
            () -> ScenarioOpenWireReceiver.credentialFiles(linkedRoot, ACCOUNT, owner(linkedRoot)));

        Path fileLinkRoot = temporary.resolve("file-link/scenario");
        ScenarioOpenWireReceiver.CredentialFiles fileLink = credentialTree(
            fileLinkRoot, ownerAfterCreate(fileLinkRoot), ACCOUNT.getBytes(StandardCharsets.UTF_8));
        Path usernameTarget = fileLink.username().resolveSibling("username-target");
        Files.move(fileLink.username(), usernameTarget);
        Files.createSymbolicLink(fileLink.username(), usernameTarget.getFileName());
        assertThrows(IOException.class,
            () -> ScenarioOpenWireReceiver.credentialFiles(fileLinkRoot, ACCOUNT, owner(fileLinkRoot)));

        Path legacyRoot = temporary.resolve("legacy/scenario");
        Files.createDirectories(legacyRoot);
        mode(legacyRoot, 0700);
        Files.writeString(legacyRoot.resolve("username"), ACCOUNT);
        Files.writeString(legacyRoot.resolve("password"), "password-marker");
        mode(legacyRoot.resolve("username"), 0600);
        mode(legacyRoot.resolve("password"), 0600);
        assertThrows(IOException.class,
            () -> ScenarioOpenWireReceiver.credentialFiles(legacyRoot, ACCOUNT, owner(legacyRoot)));
    }

    @Test void consumerIsExactNonDurableTopicWithNoSelectorAndNoLocalFalse() throws Exception {
        List<String> calls = new ArrayList<>();
        Topic topic = proxy(Topic.class, (method, args) ->
            method.getName().equals("getTopicName") ? "eew.test_QuakeLogic-SA1.dm.data" : defaultValue(method.getReturnType()));
        MessageConsumer consumer = proxy(MessageConsumer.class, (method, args) -> defaultValue(method.getReturnType()));
        Session session = proxy(Session.class, (method, args) -> {
            calls.add(method.getName());
            if (method.getName().equals("createTopic")) {
                assertEquals("eew.test_QuakeLogic-SA1.dm.data", args[0]);
                return topic;
            }
            if (method.getName().equals("createConsumer")) {
                assertSame(topic, args[0]);
                assertNull(args[1]);
                assertEquals(false, args[2]);
                return consumer;
            }
            fail("unexpected JMS method: " + method.getName());
            return null;
        });
        assertSame(consumer, ScenarioOpenWireReceiver.createPassiveTopicConsumer(
            session, "eew.test_QuakeLogic-SA1.dm.data"));
        assertEquals(List.of("createTopic", "createConsumer"), calls);
        assertFalse(calls.contains("createQueue"));
        assertFalse(calls.stream().anyMatch(name -> name.contains("Durable")));
    }

    @Test void topicValidationIsExactAndRejectsWildcards() {
        assertTrue(ScenarioOpenWireReceiver.exactEventTopic("eew.test_QuakeLogic-SA1.dm.data"));
        for (String invalid : List.of("eew.test_*.dm.data", " eew.test_QuakeLogic-SA1.dm.data",
                "eew.test_QuakeLogic-SA1.dm.data ", "eew.test_QuakeLogic-SA1.dm.data.extra",
                "queue://eew.test_QuakeLogic-SA1.dm.data")) {
            assertFalse(ScenarioOpenWireReceiver.exactEventTopic(invalid));
        }
    }

    @Test void productionEndpointAndPort61617AreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> ScenarioOpenWireReceiver.validatedEndpoint("production.eew.shakealert.org", "61612"));
        assertThrows(IllegalArgumentException.class,
            () -> ScenarioOpenWireReceiver.validatedEndpoint("scenario.eew.shakealert.org", "61617"));
    }

    @Test void callbackLifecycleOccursBeforePayloadValidation() {
        List<String> order = new ArrayList<>();
        ScenarioOpenWireReceiver.beforePayloadValidation(
            order::add, () -> order.add("PAYLOAD_VALIDATION"));
        assertEquals(List.of("MESSAGE_CALLBACK", "PAYLOAD_VALIDATION"), order);
    }

    @Test void receiverHasNoRetryFallbackPublishingProductionOrClientIdPath() throws Exception {
        String source = Files.readString(Path.of("tools", "ScenarioOpenWireReceiver.java"))
            + Files.readString(Path.of("tools", "ScenarioReceiverService.java"));
        for (String forbidden : List.of("createProducer", "MessageProducer", "setClientID",
                "failover:", "reconnect", "production.eew", "credential-directory", "credential-root")) {
            assertFalse(source.contains(forbidden));
        }
    }

    private static ScenarioOpenWireReceiver.CredentialFiles credentialTree(
        Path root, String owner, byte[] username
    ) throws Exception {
        Path account = root.resolve(ACCOUNT);
        Files.createDirectories(account);
        mode(root, 0700);
        mode(account, 0700);
        Files.write(account.resolve("username"), username);
        Files.writeString(account.resolve("password"), "password-marker");
        mode(account.resolve("username"), 0600);
        mode(account.resolve("password"), 0600);
        return ScenarioOpenWireReceiver.credentialFiles(root, ACCOUNT, owner);
    }

    private static String ownerAfterCreate(Path root) throws IOException {
        Files.createDirectories(root);
        mode(root, 0700);
        return owner(root);
    }

    private static String owner(Path path) throws IOException {
        String value = Files.getOwner(path).getName();
        int separator = Math.max(value.lastIndexOf('\\'), value.lastIndexOf('/'));
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private static void mode(Path path, int mode) throws IOException {
        Set<PosixFilePermission> permissions = new java.util.HashSet<>();
        if ((mode & 0400) != 0) permissions.add(PosixFilePermission.OWNER_READ);
        if ((mode & 0200) != 0) permissions.add(PosixFilePermission.OWNER_WRITE);
        if ((mode & 0100) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE);
        if ((mode & 0040) != 0) permissions.add(PosixFilePermission.GROUP_READ);
        if ((mode & 0004) != 0) permissions.add(PosixFilePermission.OTHERS_READ);
        Files.setPosixFilePermissions(path, permissions);
    }

    private static Connection connectionProxy(
        Session session, JMSException sessionError, AtomicBoolean closed
    ) {
        return proxy(Connection.class, (method, args) -> {
            if (method.getName().equals("createSession")) {
                if (sessionError != null) throw sessionError;
                return session;
            }
            if (method.getName().equals("close")) { closed.set(true); return null; }
            return defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(), new Class<?>[]{type},
            (instance, method, args) -> invocation.call(method, args));
    }

    private interface Invocation {
        Object call(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
