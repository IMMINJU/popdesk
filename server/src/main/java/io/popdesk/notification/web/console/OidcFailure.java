package io.popdesk.notification.web.console;

import java.time.OffsetDateTime;

/** The last provider-side failure of OIDC sign-in. */
public record OidcFailure(OidcFailureKind kind, OffsetDateTime at) {}
