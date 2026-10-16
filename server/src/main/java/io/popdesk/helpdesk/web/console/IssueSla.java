package io.popdesk.helpdesk.web.console;

/**
 * The two SLA goals of an issue (SLA-3, S8A-AC-30). The first response clock stops at the first public
 * comment of staff, or when the issue is resolved or closed without one, and never runs again. The
 * resolution clock runs while the issue is `RECEIVED`, `CONFIRMED`, or `IN_PROGRESS`. Both stop while the
 * issue is in the trash. Only business time counts.
 */
public record IssueSla(SlaGoal firstResponse, SlaGoal resolution) {}
