package io.popdesk;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The addresses whose {@code X-Forwarded-*} headers popdesk trusts ({@code POPDESK_TRUSTED_PROXIES},
 * D-46, S0-AC-18): IPs or CIDR ranges, comma-separated. Without the setting, loopback and the private
 * ranges. Only address literals are read, so no name is ever looked up.
 */
final class TrustedProxies {

    static final String DEFAULTS = "127.0.0.0/8,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,::1/128,fc00::/7";

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

    private record Range(byte[] network, int prefix) {

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (address[full] & mask) == (network[full] & mask);
        }
    }

    private final List<Range> ranges;

    private TrustedProxies(List<Range> ranges) {
        this.ranges = ranges;
    }

    /** Parses the setting; blank means the defaults. Fails on an entry that is not an address or range. */
    static TrustedProxies parse(String setting) {
        String value = setting == null || setting.isBlank() ? DEFAULTS : setting;
        List<Range> ranges = new ArrayList<>();
        for (String entry : value.split(",")) {
            String e = entry.strip();
            if (e.isEmpty()) {
                continue;
            }
            int slash = e.indexOf('/');
            String address = slash < 0 ? e : e.substring(0, slash);
            byte[] bytes = literal(address);
            if (bytes == null) {
                throw new IllegalArgumentException("POPDESK_TRUSTED_PROXIES has an entry that is not an IP address "
                        + "or CIDR range: " + e);
            }
            int prefix = slash < 0 ? bytes.length * 8 : Integer.parseInt(e.substring(slash + 1));
            if (prefix < 0 || prefix > bytes.length * 8) {
                throw new IllegalArgumentException("POPDESK_TRUSTED_PROXIES has a range with a wrong prefix: " + e);
            }
            ranges.add(new Range(bytes, prefix));
        }
        return new TrustedProxies(List.copyOf(ranges));
    }

    boolean trusts(String address) {
        byte[] bytes = literal(address);
        if (bytes == null) {
            return false;
        }
        for (Range range : ranges) {
            if (range.contains(bytes)) {
                return true;
            }
        }
        return false;
    }

    /** The bytes of an IPv4 or IPv6 literal, or null for anything else (never a name lookup). */
    static byte[] literal(String address) {
        if (address == null) {
            return null;
        }
        String a = address.strip();
        if (a.startsWith("[") && a.endsWith("]")) {
            a = a.substring(1, a.length() - 1);
        }
        int zone = a.indexOf('%');
        if (zone >= 0) {
            a = a.substring(0, zone);
        }
        if (!IPV4.matcher(a).matches() && !IPV6.matcher(a).matches()) {
            return null;
        }
        if (IPV4.matcher(a).matches()) {
            for (String part : a.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return null;
                }
            }
        }
        try {
            return InetAddress.getByName(a).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
