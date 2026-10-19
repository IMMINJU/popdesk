package io.popdesk.notification.web.console;

/**
 * A provider-side failure of OIDC sign-in (S11-AC-30): the discovery document or the JWKS could not be read or
 * did not match, the token endpoint could not be used, or the ID token did not pass its checks.
 */
public enum OidcFailureKind {
    DISCOVERY,
    TOKEN,
    ID_TOKEN
}
