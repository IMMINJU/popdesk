package io.popdesk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * {@code page} and {@code size} of a list request (COM-4): page from 1, size 1 to 100. A page past
 * the last one is an empty page, not an error.
 */
public record PageRequest(int page, int size) {

    public static final int MAX_SIZE = 100;

    /** Checks the two values; 400 {@code OUT_OF_RANGE} names each one that is out of range. */
    public static PageRequest of(int page, int size) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        if (page < 1) {
            errors.add(new ApiError.FieldError("page", "OUT_OF_RANGE"));
        }
        if (size < 1 || size > MAX_SIZE) {
            errors.add(new ApiError.FieldError("size", "OUT_OF_RANGE"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        return new PageRequest(page, size);
    }

    public long offset() {
        return (long) (page - 1) * size;
    }

    /** The page of {@code items} (already this page's rows) out of {@code total}. */
    public <T> Paged<T> of(List<T> items, long total) {
        return new Paged<>(List.copyOf(items), page, size, total, (int) ((total + size - 1) / size));
    }

    public <S, T> Paged<T> map(List<S> items, long total, Function<S, T> mapper) {
        return of(items.stream().map(mapper).toList(), total);
    }
}
