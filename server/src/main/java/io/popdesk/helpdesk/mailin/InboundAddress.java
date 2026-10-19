package io.popdesk.helpdesk.mailin;

import java.net.IDN;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The company code a customer adds to {@code MAIL_INBOUND_ADDRESS} as {@code +CODE} (S12-AC-19, D-16), and whether an
 * address is that address. Pure. Addresses are addr-specs (no display name); the part before {@code @} is compared in
 * ASCII without letter case, the domain as its IDN A-label in lower case.
 */
final class InboundAddress {

    /** What a code given in the address stands for when it is not of the code form: no company has it. */
    static final String NO_SUCH_CODE = "";

    /** The form of a company code (CompanyService). */
    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{2,10}");

    private InboundAddress() {}

    /**
     * The code of the first of {@code addresses} that is {@code inboundAddress} with {@code +} and something after
     * the first {@code +} of its local part, in upper case. Addresses without a {@code +}, at another local part or
     * another domain are passed over.
     *
     * @param addresses the addr-specs of {@code To} and then of {@code Cc}, in their order; may be empty, no nulls
     * @param inboundAddress {@code MAIL_INBOUND_ADDRESS}, without a {@code +}; null gives empty
     * @return empty when no address has a code; the code when it is of the code form ({@code [A-Z0-9]{2,10}});
     *     {@link #NO_SUCH_CODE} when the first address with one has anything else after the {@code +}
     */
    static Optional<String> code(List<String> addresses, String inboundAddress) {
        if (inboundAddress == null) {
            return Optional.empty();
        }
        String inbound = normal(inboundAddress);
        int inboundAt = inbound.lastIndexOf('@');
        for (String address : addresses) {
            String a = normal(address);
            int at = a.lastIndexOf('@');
            int plus = a.indexOf('+');
            if (at < 0 || plus < 0 || plus > at) {
                continue;
            }
            if (a.substring(0, plus).equals(inbound.substring(0, inboundAt))
                    && a.substring(at).equals(inbound.substring(inboundAt))) {
                String code = address.strip().substring(plus + 1, address.strip().lastIndexOf('@'))
                        .toUpperCase(Locale.ROOT);
                return Optional.of(CODE.matcher(code).matches() ? code : NO_SUCH_CODE);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code address} is {@code inboundAddress}, or it with a {@code +} and anything after it in the local
     * part (S12-AC-11).
     *
     * @param address an addr-spec; never null
     * @param inboundAddress {@code MAIL_INBOUND_ADDRESS}; null gives false
     */
    static boolean isInbound(String address, String inboundAddress) {
        if (inboundAddress == null) {
            return false;
        }
        String a = normal(address);
        String inbound = normal(inboundAddress);
        int at = a.lastIndexOf('@');
        int plus = a.indexOf('+');
        if (plus >= 0 && at > plus) {
            a = a.substring(0, plus) + a.substring(at);
        }
        return a.equals(inbound);
    }

    /**
     * The address as compared: trimmed, the local part in ASCII lower case, the domain as its IDN A-label in lower
     * case (left as it is when it cannot be one). An address without {@code @} is all local part.
     */
    static String normal(String address) {
        String a = address.strip();
        int at = a.lastIndexOf('@');
        if (at < 0) {
            return asciiLower(a);
        }
        String domain = a.substring(at + 1);
        try {
            domain = IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            // Not a domain IDN can write; compared as it is.
            domain = a.substring(at + 1);
        }
        return asciiLower(a.substring(0, at)) + "@" + domain.toLowerCase(Locale.ROOT);
    }

    private static String asciiLower(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return out.toString();
    }
}
