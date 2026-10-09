package io.popdesk.ops;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ways a value can show up in a log line (S0 premortem 4): as it is, percent-encoded
 * ({@code %40} for {@code @}, in a URL), and JSON-escaped (inside a JSON log field, with or without
 * {@code \/} and {@code \}{@code uXXXX} for non-ASCII).
 */
final class LogForms {

    private static final JsonMapper PLAIN = JsonMapper.builder().build();
    private static final JsonMapper ASCII = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();

    private LogForms() {}

    static Set<String> of(String value) {
        Set<String> forms = new LinkedHashSet<>();
        forms.add(value);
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        forms.add(encoded);
        forms.add(encoded.replace("+", "%20"));
        for (JsonMapper mapper : List.of(PLAIN, ASCII)) {
            String json = mapper.writeValueAsString(value);
            String inner = json.substring(1, json.length() - 1);
            forms.add(inner);
            forms.add(inner.replace("/", "\\/"));
        }
        return forms;
    }

    /** Every form of every value that {@code log} contains, as {@code value -> form}. */
    static List<String> foundIn(String log, List<String> values) {
        List<String> found = new ArrayList<>();
        for (String value : values) {
            for (String form : of(value)) {
                if (log.contains(form)) {
                    found.add(value + " as " + form);
                }
            }
        }
        return found;
    }

    /** The part of an email after its first character, which a masked email never shows. */
    static String afterFirstCharacter(String email) {
        int at = email.indexOf('@');
        return email.substring(1, at);
    }
}
