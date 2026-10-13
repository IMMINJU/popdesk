package io.popdesk.helpdesk.issue;

import io.popdesk.ApiError;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The {@code imageIds} of a request that saves an issue's content or a comment (S5-AC-20): the IDs of
 * temporary images to attach with the save, each once, as the request wrote them. Whether a value is
 * the ID of an image the caller may attach is not looked at here; {@link IssueAttachments#attachImages}
 * answers that for all of them at once, with one answer whatever is wrong (S5-AC-21).
 *
 * @param values the IDs as sent, without repeats, at most {@value #MAX}
 */
public record ImageIds(List<String> values) {

    /** The most images one request attaches. */
    public static final int MAX = 20;

    static final String FIELD = "imageIds";

    public static final ImageIds NONE = new ImageIds(List.of());

    /**
     * The IDs of the field as received. Left out, JSON {@code null}, and an empty list attach nothing.
     * Anything but a list of texts adds {@code INVALID_FORMAT} to {@code errors}, and more than
     * {@value #MAX} different texts {@code TOO_MANY} (acceptance.md of slice 5, section 5, 14); both
     * give {@link #NONE}. A text that is no UUID is no error here.
     */
    public static ImageIds of(JsonNode sent, List<ApiError.FieldError> errors) {
        if (sent == null || sent.isNull()) {
            return NONE;
        }
        Set<String> values = new LinkedHashSet<>();
        boolean texts = sent.isArray();
        if (texts) {
            for (JsonNode element : sent.values()) {
                if (element.isString()) {
                    values.add(element.stringValue());
                } else {
                    texts = false;
                }
            }
        }
        if (!texts) {
            errors.add(new ApiError.FieldError(FIELD, "INVALID_FORMAT"));
            return NONE;
        }
        if (values.size() > MAX) {
            errors.add(new ApiError.FieldError(FIELD, "TOO_MANY"));
            return NONE;
        }
        return new ImageIds(List.copyOf(values));
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
