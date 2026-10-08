package io.popdesk.support;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.stream.Stream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * The test certificate for TLS mail servers, and the JVM-wide trust in it.
 *
 * <p>{@code support/mail-server.p12} holds a self-signed key for {@code localhost} and
 * {@code 127.0.0.1} (made once with keytool, valid for 100 years); {@code support/mail-trust.p12}
 * holds its certificate. {@link #trustTestCertificate()} makes the JVM's default SSL context trust
 * that certificate besides the usual CAs, so the app connects with its normal TLS settings, without
 * a test-only switch.
 */
final class TestTls {

    private static final char[] PASSWORD = "popdesk-test".toCharArray();

    private static boolean trusted;

    private TestTls() {}

    /** A server-side SSL context with the test key. */
    static SSLContext serverContext() {
        try {
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(load("support/mail-server.p12"), PASSWORD);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Makes the JVM's default SSL context trust the test certificate as well as the JDK's CAs. */
    static synchronized void trustTestCertificate() {
        if (trusted) {
            return;
        }
        try {
            X509TrustManager jdk = trustManager(null);
            X509TrustManager test = trustManager(load("support/mail-trust.p12"));
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new Either(jdk, test)}, null);
            SSLContext.setDefault(context);
            trusted = true;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static X509TrustManager trustManager(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        return Arrays.stream(factory.getTrustManagers())
                .filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static KeyStore load(String resource) throws GeneralSecurityException {
        try (InputStream in = TestTls.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource " + resource);
            }
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, PASSWORD);
            return store;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Trusts a server when either manager does. */
    private record Either(X509TrustManager first, X509TrustManager second) implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                first.checkClientTrusted(chain, authType);
            } catch (CertificateException e) {
                second.checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                first.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                second.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return Stream.concat(Arrays.stream(first.getAcceptedIssuers()), Arrays.stream(second.getAcceptedIssuers()))
                    .toArray(X509Certificate[]::new);
        }
    }
}
