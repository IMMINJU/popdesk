package io.popdesk;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;

/** One page of a list (docs/integrate/conventions.md "Pagination", COM-4). */
public record Paged<T>(
        List<T> items,
        @Schema(description = "Page number, starting at 1.") int page,
        @Schema(description = "Items per page, 1 to 100.") int size,
        long totalElements,
        int totalPages) {

    /** The same page with each item turned into a response type. */
    public <R> Paged<R> map(Function<T, R> mapper) {
        return new Paged<>(items.stream().map(mapper).toList(), page, size, totalElements, totalPages);
    }
}
