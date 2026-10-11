package io.popdesk.notification.web.console;

/** Outgoing channels (OPS-3 in part). {@code auditForward} is the outside copy of the audit log (S1B-AC-33). */
public record SystemStatus(ChannelStatus mail, ChannelStatus webhook, ChannelStatus auditForward) {}
