package io.popdesk.helpdesk.audit;

import io.popdesk.ApiException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The filters of the audit log (S1B-AC-03 to S1B-AC-06); a null one does not narrow. {@code actions}
 * finds entries of any of the kinds, {@code from} is in the period and {@code to} is not.
 */
public record AuditFilter(List<AuditAction> actions, Long actorAccountId, String targetType, Long targetAccountId,
        String targetCode, Instant from, Instant to) {

    public AuditFilter {
        actions = actions == null || actions.isEmpty() ? null : List.copyOf(actions);
    }

    /** 400 with {@code FROM_AFTER_TO} on {@code from} when the period is turned around. */
    void check() {
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.invalid("from", "FROM_AFTER_TO");
        }
    }

    /** The filters that were sent, under their query names, as an export's audit entry keeps them. */
    Map<String, Object> sent() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (actions != null) {
            out.put("action", actions.stream().map(AuditAction::name).toList());
        }
        putIfSent(out, "actorAccountId", actorAccountId);
        putIfSent(out, "targetType", targetType);
        putIfSent(out, "targetAccountId", targetAccountId);
        putIfSent(out, "targetCode", targetCode);
        putIfSent(out, "from", from);
        putIfSent(out, "to", to);
        return out;
    }

    private static void putIfSent(Map<String, Object> out, String name, Object value) {
        if (value != null) {
            out.put(name, value);
        }
    }
}
