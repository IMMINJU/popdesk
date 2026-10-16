package io.popdesk.helpdesk.web.console;

/** The two SLA goals of a company's issues over the chosen days (SLA-7, S8B-AC-22). */
public record CompanySlaStat(SlaGoalStat firstResponse, SlaGoalStat resolution) {}
