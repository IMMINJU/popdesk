package io.popdesk.helpdesk.account;

import io.popdesk.ApiException;
import io.popdesk.PublicAddress;
import io.popdesk.PublicAddress.App;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import io.popdesk.helpdesk.account.OidcProvider.Metadata;
import io.popdesk.helpdesk.account.OidcRequestRepository.RequestRow;
import io.popdesk.helpdesk.audit.AuditAction;
import io.popdesk.helpdesk.audit.AuditLog;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OIDC sign-in (AUTH-6, D-64, design.md 0절 3 to 6): the start, the callback, and which account it reaches.
 *
 * <p>The start makes a sign-in request (a {@code state}, a nonce, a browser value for the cookie, a PKCE verifier)
 * and sends the browser to the provider (S11-AC-04). The callback takes the request of its {@code state} out of the
 * table, so it is used once, and goes on only when the browser's cookie, the app, and the 10 minutes match
 * (S11-AC-11); then the code is exchanged, the ID token checked (S11-AC-10), and the account matched (S11-AC-15 to
 * S11-AC-18). Every way it ends is a page the browser goes to: no JSON error but the 404 of an app without OIDC.
 *
 * <p>Logs hold an account ID and the name of a reason, never a code, a {@code state}, a nonce, a PKCE value, a token,
 * the client secret, a {@code sub}, an email, or the provider's {@code error_description} (S11-AC-25).
 */
@Service
class OidcSignInService {

    /** How long a sign-in request may be used (S11-AC-04, S11-AC-11). */
    static final Duration REQUEST_LIFETIME = Duration.ofMinutes(10);

    /** At most this many sign-in requests under 10 minutes old in the whole installation (S11-AC-08). */
    static final int MAX_OPEN_REQUESTS = 10_000;

    static final String SCOPE = "openid email profile";

    /** A provider's {@code error} that may go into the log as it is (S11-AC-25). */
    private static final Pattern ERROR_CODE = Pattern.compile("[a-z_]{1,64}");

    private static final Logger log = LoggerFactory.getLogger(OidcSignInService.class);

    /**
     * Where the browser goes next. {@code browserValue} is the cookie's value for a start that goes to the provider,
     * else null; {@code accountId} is the account to start a session for after a callback that matched, else 0.
     */
    record Step(String location, String browserValue, long accountId) {

        /** The sign-in page of the app: without a query for null, else with {@code oidc=value}. */
        static Step backAtLogin(String value) {
            return new Step(value == null ? "/login" : "/login?oidc=" + value, null, 0);
        }
    }

    /** An account the token does not let in (S11-AC-17); {@code account} when one was matched, for the audit. */
    private static final class NoMatch extends RuntimeException {

        private final transient AccountRow account;

        NoMatch(AccountRow account, String reason) {
            super(reason, null, false, false);
            this.account = account;
        }
    }

    private final OidcSetup setup;
    private final OidcProvider provider;
    private final OidcStatus status;
    private final OidcRequestRepository requests;
    private final OidcLinkRepository links;
    private final AccountRepository accounts;
    private final InvitationService invitations;
    private final AuditLog audit;
    private final PublicAddress address;
    private final Transactions transactions;
    private final IpRateLimit rateLimit;
    private final Clock clock;

    OidcSignInService(OidcSetup setup, OidcProvider provider, OidcStatus status, OidcRequestRepository requests,
            OidcLinkRepository links, AccountRepository accounts, InvitationService invitations, AuditLog audit,
            PublicAddress address, Transactions transactions, Clock clock) {
        this.setup = setup;
        this.provider = provider;
        this.status = status;
        this.requests = requests;
        this.links = links;
        this.accounts = accounts;
        this.invitations = invitations;
        this.audit = audit;
        this.address = address;
        this.transactions = transactions;
        this.rateLimit = new IpRateLimit(clock);
        this.clock = clock;
    }

    /** Whether OIDC sign-in is on for {@code app}. */
    boolean on(App app) {
        return setup.on(app);
    }

    /**
     * Starts a sign-in (S11-AC-04 to S11-AC-08, S11-AC-14). 404 when OIDC is off for the app. In this order: the
     * limit per client IP and the limit of open requests ({@code busy}), the discovery document
     * ({@code unavailable}), then the request, made in one transaction with the requests over 10 minutes old deleted.
     */
    Step start(App app, String clientIp, String next) {
        refuseOff(app);
        if (rateLimit.attempt(clientIp).isPresent()) {
            log.info("OIDC sign-in start refused: too many starts from one address.");
            return Step.backAtLogin("busy");
        }
        Instant now = now();
        if (requests.countMadeAfter(now.minus(REQUEST_LIFETIME)) >= MAX_OPEN_REQUESTS) {
            log.warn("OIDC sign-in start refused: {} sign-ins were started in the last 10 minutes.",
                    MAX_OPEN_REQUESTS);
            return Step.backAtLogin("busy");
        }
        Optional<Metadata> metadata = provider.metadata();
        if (metadata.isEmpty()) {
            return Step.backAtLogin("unavailable");
        }
        String state = Tokens.create();
        String nonce = Tokens.create();
        String browser = Tokens.create();
        String verifier = Tokens.create();
        String nextPath = SafeNext.of(next);
        transactions.run(() -> {
            requests.deleteMadeBefore(now.minus(REQUEST_LIFETIME));
            requests.insert(Tokens.hash(state), Tokens.hash(browser), Tokens.hash(nonce), verifier, app, nextPath,
                    now);
        });
        URI authorization = UriComponentsBuilder.fromUri(metadata.get().authorization())
                .queryParam("response_type", "code")
                .queryParam("client_id", setup.settings().clientId())
                .queryParam("redirect_uri", redirectUri(app))
                .queryParam("scope", SCOPE)
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("code_challenge", challenge(verifier))
                .queryParam("code_challenge_method", "S256")
                .encode()
                .build()
                .toUri();
        return new Step(authorization.toString(), browser, 0);
    }

    /**
     * The provider sends the browser back (S11-AC-09 to S11-AC-20). 404 when OIDC is off for the app. Every other
     * answer is a {@link Step}: signed in to the request's page, back at the sign-in page without a query when the
     * provider sent {@code error} (S11-AC-12), or with {@code oidc=failed} or {@code oidc=unavailable}.
     *
     * @param browserValue the value of the sign-in cookie, or null without one
     */
    Step callback(App app, String browserValue, String code, String state, String error) {
        refuseOff(app);
        String stateHash = Tokens.hash(state);
        String browserHash = Tokens.hash(browserValue);
        if (browserHash == null) {
            return failed(null, "no sign-in cookie");
        }
        if (stateHash == null) {
            return failed(null, "no state, or a state popdesk never gave");
        }
        RequestRow request = transactions.run(() -> requests.take(stateHash)).orElse(null);
        if (request == null) {
            return failed(null, "no sign-in request has that state");
        }
        if (!request.browserHash().equals(browserHash)) {
            return failed(null, "the sign-in cookie is of another sign-in");
        }
        if (request.app() != app) {
            return failed(null, "the sign-in was started on the other app");
        }
        if (!now().isBefore(request.createdAt().plus(REQUEST_LIFETIME))) {
            return failed(null, "the sign-in request is over 10 minutes old");
        }
        if (error != null) {
            if (ERROR_CODE.matcher(error).matches()) {
                log.info("OIDC sign-in ended at the provider with the error {}.", error);
            } else {
                log.info("OIDC sign-in ended at the provider with an error of an unknown form.");
            }
            return Step.backAtLogin(null);
        }
        if (code == null || code.isEmpty()) {
            return failed(null, "no code");
        }
        Metadata metadata = provider.metadata().orElse(null);
        if (metadata == null) {
            return Step.backAtLogin("unavailable");
        }
        Map<String, Object> claims;
        try {
            String idToken = provider.idToken(metadata, code, state, request.codeVerifier(), redirectUri(app));
            claims = provider.signedClaims(metadata, idToken);
        } catch (OidcProvider.Unavailable e) {
            log.warn("OIDC sign-in is unavailable: {}.", e.getMessage());
            return Step.backAtLogin("unavailable");
        } catch (OidcProvider.Refused e) {
            return failed(null, e.getMessage());
        }
        OidcSettings settings = setup.settings();
        Optional<IdTokenClaims.Failure> wrong = IdTokenClaims.check(claims,
                new IdTokenClaims.Expected(settings.issuer(), settings.clientId(), request.nonceHash()),
                clock.instant());
        if (wrong.isPresent()) {
            provider.claimsFailed();
            return failed(null, "the ID token fails its check " + wrong.get());
        }
        long accountId;
        try {
            accountId = match(app, issuer(claims), (String) claims.get("sub"), claims);
        } catch (NoMatch e) {
            AccountRow account = e.account;
            if (account != null) {
                transactions.run(() -> audit.record(actor(account), AuditAction.SIGN_IN_FAILED, target(account.id()),
                        oidcMethod()));
            }
            return failed(account, e.getMessage());
        }
        status.signedIn();
        log.info("Account {} signed in to the {} by OIDC.", accountId, app == App.PORTAL ? "portal" : "console");
        return new Step(request.nextPath(), null, accountId);
    }

    /**
     * The account the checked token reaches, in one transaction (design.md 0절 5): by its link, or the first time by
     * its verified ASCII email, which links it. The rows that decide are read again under the account's lock: the
     * email, the status, the role, the invitation. Throws {@link NoMatch}, with nothing written, otherwise.
     */
    private long match(App app, String issuer, String subject, Map<String, Object> claims) {
        return transactions.run(() -> {
            Long linked = links.findAccount(issuer, subject).orElse(null);
            String email = null;
            AccountRow seen;
            if (linked != null) {
                seen = accounts.findById(linked).orElse(null);
            } else {
                email = IdTokenClaims.verifiedEmail(claims).orElse(null);
                if (email == null) {
                    throw new NoMatch(null, "no link, and no verified ASCII email");
                }
                seen = accounts.findByEmail(email).orElse(null);
            }
            if (seen == null) {
                throw new NoMatch(null, "no account has the email");
            }
            if (seen.status() == AccountStatus.INVITED) {
                // The invitation and the account, locked in the order accepting by a link takes them.
                accounts.lockInvitation(seen.id());
            }
            AccountRow account = accounts.lockById(seen.id()).orElseThrow(() -> new NoMatch(null, "no account"));
            if (linked == null && !IdTokenClaims.sameEmail(account.email(), email)) {
                throw new NoMatch(null, "the account's email changed meanwhile");
            }
            if (account.anonymized() || account.status() == AccountStatus.SUSPENDED) {
                throw new NoMatch(account, "the account is suspended");
            }
            if (SessionFilter.appOf(account.role()) != app) {
                throw new NoMatch(account, "the account is of the other app");
            }
            Instant now = now();
            if (linked == null) {
                String other = links.findSubject(account.id(), issuer).orElse(null);
                if (other != null && !other.equals(subject)) {
                    throw new NoMatch(account, "the account is linked to another subject of the issuer");
                }
                if (other == null) {
                    if (links.insert(account.id(), issuer, subject, now)) {
                        Map<String, Object> changes = new LinkedHashMap<>();
                        changes.put("issuer", issuer);
                        audit.record(actor(account), AuditAction.OIDC_LINKED, target(account.id()), changes);
                    } else if (!Objects.equals(links.findAccount(issuer, subject).orElse(null), account.id())) {
                        throw new NoMatch(account, "the subject was linked to another account meanwhile");
                    }
                }
            }
            if (account.status() == AccountStatus.INVITED && !invitations.activateByOidc(actor(account))) {
                throw new NoMatch(account, "the invitation is over 7 days old");
            }
            audit.record(actor(account), AuditAction.SIGN_IN_SUCCEEDED, target(account.id()), oidcMethod());
            return account.id();
        });
    }

    private void refuseOff(App app) {
        if (!setup.on(app)) {
            throw ApiException.notFound();
        }
    }

    private static Step failed(AccountRow account, String reason) {
        if (account == null) {
            log.info("OIDC sign-in failed: {}.", reason);
        } else {
            log.info("OIDC sign-in failed for account {}: {}.", account.id(), reason);
        }
        return Step.backAtLogin("failed");
    }

    /** The callback address of the app, registered at the provider (docs/operate/configuration.md). */
    private String redirectUri(App app) {
        return address.origin(app) + "/api/public/oidc/callback";
    }

    /** The token's {@code iss}, which {@link IdTokenClaims#check} found equal to {@code OIDC_ISSUER}. */
    private static String issuer(Map<String, Object> claims) {
        Object iss = claims.get("iss");
        return iss instanceof URL url ? url.toExternalForm() : (String) iss;
    }

    /** The PKCE {@code S256} challenge: base64url without padding of the SHA-256 of the verifier. */
    private static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from the JVM", e);
        }
    }

    private static Map<String, Object> oidcMethod() {
        return Map.of("method", "OIDC");
    }

    private static AuditLog.Actor actor(AccountRow account) {
        return new AuditLog.Actor(account.id(), account.name(), account.email());
    }

    private static Map<String, Object> target(long accountId) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", "ACCOUNT");
        target.put("accountId", accountId);
        return target;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
