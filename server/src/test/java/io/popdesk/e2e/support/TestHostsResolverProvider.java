package io.popdesk.e2e.support;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.stream.Stream;

/**
 * Resolves the test host names to the loopback address, like a hosts file, so links from emails
 * and responses can be opened as they are: {@code *.e2e.test} (the proxy's published port) and
 * {@code *.localhost} (the app's published port, with the default host names). Everything else
 * goes to the system resolver. Registered in {@code META-INF/services}.
 */
public final class TestHostsResolverProvider extends InetAddressResolverProvider {

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                String h = host.toLowerCase(java.util.Locale.ROOT);
                if (h.endsWith(".e2e.test") || h.endsWith(".localhost")) {
                    return Stream.of(InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1}));
                }
                return builtin.lookupByName(host, policy);
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return builtin.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "popdesk-e2e-test-hosts";
    }
}
