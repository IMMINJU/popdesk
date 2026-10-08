package io.popdesk.notification.web.console;

/** Outgoing channels (OPS-3 in part). */
public record SystemStatus(ChannelStatus mail, ChannelStatus webhook) {}
