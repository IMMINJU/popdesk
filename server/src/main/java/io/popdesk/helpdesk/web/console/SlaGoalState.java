package io.popdesk.helpdesk.web.console;

/** Where one SLA goal of an issue stands (SLA-5, S8A-AC-18). */
public enum SlaGoalState {
    OK,
    WARNING,
    BREACHED,
    MET,
    MISSED
}
