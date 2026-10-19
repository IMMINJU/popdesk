package io.popdesk.helpdesk.web.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;

/** The ways to sign in on the host the request came to (S11-AC-03). Never the provider's address or client ID. */
public record SignInOptions(
        @Schema(description = "OIDC sign-in is on for this host's app (`OIDC_APPS`): the sign-in page offers it.")
                boolean oidc) {}
