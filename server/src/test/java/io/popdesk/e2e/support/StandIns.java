package io.popdesk.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;

/**
 * Real stand-ins on the installation's Compose network: Mailpit (SMTP on 1025, API on 8025), a
 * webhook receiver, and Caddy in front of {@code app:8080}. The webhook receiver's container also
 * reads the management port, which Compose does not publish.
 */
public final class StandIns implements AutoCloseable {

    public static final String MAIL_HOST = "e2e-mail";
    public static final String HOOK_HOST = "e2e-hook";

    private static final Duration WAIT = Duration.ofSeconds(60);

    private final GenericContainer<?> mail;
    private final GenericContainer<?> hook;
    private final GenericContainer<?> proxy;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private StandIns(String network) {
        Network net = new ExistingNetwork(network);
        mail = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.31.4"))
                .withNetwork(net).withNetworkAliases(MAIL_HOST)
                .withEnv("MP_SMTP_AUTH_ACCEPT_ANY", "true").withEnv("MP_SMTP_AUTH_ALLOW_INSECURE", "true")
                .withExposedPorts(8025).waitingFor(Wait.forHttp("/api/v1/messages").forPort(8025));
        hook = new GenericContainer<>(DockerImageName.parse("python:3.14.8-alpine"))
                .withNetwork(net).withNetworkAliases(HOOK_HOST)
                .withCopyToContainer(MountableFile.forClasspathResource("e2e/hook_receiver.py"), "/hook_receiver.py")
                .withCommand("python", "-u", "/hook_receiver.py")
                .withExposedPorts(8000).waitingFor(Wait.forHttp("/").forPort(8000));
        proxy = new GenericContainer<>(DockerImageName.parse("caddy:2.11.7-alpine"))
                .withNetwork(net)
                .withCopyToContainer(MountableFile.forClasspathResource("e2e/Caddyfile"), "/etc/caddy/Caddyfile")
                .withExposedPorts(443).waitingFor(Wait.forLogMessage(".*serving initial configuration.*", 1));
    }

    /** Starts the three containers on {@code network}. */
    public static StandIns start(String network) {
        StandIns s = new StandIns(network);
        s.mail.start();
        s.hook.start();
        s.proxy.start();
        return s;
    }

    /** The host port of the proxy's HTTPS listener: the public port of the installation in this test. */
    public int proxyPort() {
        return proxy.getMappedPort(443);
    }

    public String proxyLogs() {
        return proxy.getLogs();
    }

    /** Trusts the certificates Caddy issued with its internal CA. */
    public SSLContext proxyTrust() {
        String path = "/data/caddy/pki/authorities/local/root.crt";
        try {
            Awaitility.await("Caddy root certificate").atMost(WAIT).pollInterval(Duration.ofMillis(500)).pollInSameThread()
                    .until(() -> proxy.execInContainer("test", "-f", path).getExitCode() == 0);
            byte[] pem = proxy.copyFileFromContainer(path, in -> in.readAllBytes());
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("caddy-local-root",
                    CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem)));
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, tmf.getTrustManagers(), null);
            return ssl;
        } catch (Exception e) {
            throw new AssertionError("could not read Caddy's root certificate", e);
        }
    }

    /** Reads {@code /actuator/prometheus} on the management port from inside the network. */
    public Prometheus metrics() {
        Container.ExecResult r = exec(hook, "python", "-c",
                "import urllib.request,sys;r=urllib.request.urlopen('http://app:8081/actuator/prometheus',timeout=20);"
                        + "sys.stdout.write(str(r.status)+'\\n'+r.read().decode())");
        assertThat(r.getExitCode()).as("reading http://app:8081/actuator/prometheus failed: %s", r.getStderr()).isZero();
        String out = r.getStdout();
        assertThat(out).as("status of /actuator/prometheus").startsWith("200\n");
        return new Prometheus(out.substring(4));
    }

    /** Webhook requests received so far. */
    public List<JsonNode> hookRequests() {
        JsonNode all = Json.parse(get("http://" + hook.getHost() + ":" + hook.getMappedPort(8000) + "/"));
        List<JsonNode> list = new ArrayList<>();
        all.forEach(list::add);
        return list;
    }

    /** Waits for at least {@code count} webhook requests matching {@code test}, and returns them all. */
    public List<JsonNode> awaitHookRequests(String what, int count, Predicate<JsonNode> test) {
        try {
            return Awaitility.await(what).atMost(WAIT).pollInterval(Duration.ofMillis(500)).pollInSameThread()
                    .until(() -> hookRequests().stream().filter(test).toList(), l -> l.size() >= count);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("timed out after " + WAIT + " waiting for " + what + "; received: " + hookRequests(), e);
        }
    }

    public record Mail(String id, List<String> to, String subject, String text, String html) {

        public String body() {
            return text + "\n" + html;
        }
    }

    /** Every message Mailpit holds, newest first. */
    public List<Mail> mails() {
        String api = "http://" + mail.getHost() + ":" + mail.getMappedPort(8025) + "/api/v1/";
        JsonNode list = Json.parse(get(api + "messages?limit=500")).get("messages");
        List<Mail> mails = new ArrayList<>();
        for (JsonNode summary : list) {
            JsonNode m = Json.parse(get(api + "message/" + summary.get("ID").asString()));
            List<String> to = new ArrayList<>();
            m.get("To").forEach(a -> to.add(a.get("Address").asString()));
            mails.add(new Mail(m.get("ID").asString(), to, m.get("Subject").asString(), m.get("Text").asString(),
                    m.get("HTML").asString()));
        }
        return mails;
    }

    /** Waits for a message to {@code address} that matches {@code test}. */
    public Mail awaitMail(String what, String address, Predicate<Mail> test) {
        try {
            return Awaitility.await(what).atMost(WAIT).pollInterval(Duration.ofMillis(500)).pollInSameThread()
                    .until(() -> mails().stream().filter(m -> m.to().stream().anyMatch(address::equalsIgnoreCase))
                            .filter(test).findFirst().orElse(null), m -> m != null);
        } catch (ConditionTimeoutException e) {
            List<String> seen = mails().stream().map(m -> m.to() + " " + m.subject()).toList();
            throw new AssertionError("timed out after " + WAIT + " waiting for " + what + "; Mailpit holds: " + seen, e);
        }
    }

    private String get(String url) {
        try {
            var r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(WAIT).GET().build(), BodyHandlers.ofString());
            assertThat(r.statusCode()).as("GET %s", url).isEqualTo(200);
            return r.body();
        } catch (IOException e) {
            throw new AssertionError("GET " + url + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    private static Container.ExecResult exec(GenericContainer<?> c, String... command) {
        try {
            return c.execInContainer(command);
        } catch (IOException e) {
            throw new AssertionError("exec in " + c.getDockerImageName() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    @Override
    public void close() {
        proxy.stop();
        hook.stop();
        mail.stop();
    }

    /** The Compose network, which Compose made and removes; Testcontainers only joins it. */
    private record ExistingNetwork(String getId) implements Network {
        @Override
        public void close() {}
    }
}
