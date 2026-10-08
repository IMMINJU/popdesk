package io.popdesk.support;

/**
 * Lets {@link java.net.http.HttpClient} send our own {@code Host} header. The JDK reads this
 * property once, when its HTTP client first loads, so it is set before any Spring test context
 * starts ({@link SupportContextCustomizerFactory}) and again by {@link ApiClient}. The self test
 * checks that the header arrives as sent.
 */
final class JdkHttp {

    private JdkHttp() {}

    static void allowHostHeader() {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    }
}
