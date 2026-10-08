package io.popdesk;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** One page of a list (docs/integrate/conventions.md "Pagination", COM-4). */
public record Paged<T>(
        List<T> items,
        @Schema(description = "Page number, starting at 1.") int page,
        @Schema(description = "Items per page, 1 to 100.") int size,
        long totalElements,
        int totalPages) {}
