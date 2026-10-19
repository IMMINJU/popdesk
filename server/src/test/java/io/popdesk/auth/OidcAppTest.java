package io.popdesk.auth;

import io.popdesk.privacy.PrivacySteps;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.OidcProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The app of every server test that signs in with OIDC (slice 11): "connected" ({@link ConnectedAppTest}, so password
 * reset mails arrive) with OIDC sign-in on for both apps, against the provider of this JVM ({@link OidcProvider}).
 * {@code OIDC_ISSUER} is the provider's {@code /default} issuer, {@code OIDC_CLIENT_ID} and {@code OIDC_CLIENT_SECRET}
 * the provider's test client, {@code OIDC_APPS=both}. One context and one database of its own; the context without
 * OIDC (sign-in off, 404) is the shared bare one.
 *
 * <p>Tests share the app, its rate limits, and its clock: each one makes its own accounts with new emails and new
 * subjects ({@link OidcFlow#newSub}), and reads the audit log only for the IDs of the accounts it made
 * ({@link #auditOf}). The clock stands still at the app's own time, so ID tokens carry times from it
 * ({@link OidcFlow#claims}).
 */
@TestPropertySource(properties = {OidcAppTest.OWN_CONTEXT,
        "OIDC_CLIENT_ID=" + OidcProvider.CLIENT_ID,
        "OIDC_CLIENT_SECRET=" + OidcProvider.CLIENT_SECRET,
        "OIDC_APPS=both"})
public abstract class OidcAppTest extends ConnectedAppTest {

    /** A property the app does not read; it keeps this context apart from the other connected ones. */
    public static final String OWN_CONTEXT = "oidc.test.context=oidc";

    public static final String ACCOUNTS = "/api/console/accounts";

    @DynamicPropertySource
    static void oidcProvider(DynamicPropertyRegistry registry) {
        registry.add("OIDC_ISSUER", () -> OidcProvider.shared().issuer());
    }

    /** The sign-in steps against this context's provider. */
    protected OidcFlow oidc;

    @BeforeEach
    void setUpOidc() {
        oidc = new OidcFlow(OidcProvider.shared(), clock);
    }

    /** A new active account of {@code role}, accepted with {@code Fixtures.PASSWORD} and signed in. */
    protected Session person(Session admin, Role role) {
        return switch (role) {
            case CUSTOMER -> fixtures.customer(admin, fixtures.createCompany(admin).code());
            case AGENT -> fixtures.agent(admin);
            case ADMIN -> fixtures.admin(admin);
        };
    }

    /** {@code GET /api/console/accounts/{id}} as the admin; expects 200. */
    protected JsonNode account(Session admin, long accountId) {
        return admin.client().get(ACCOUNTS + "/" + accountId).expect(200).json();
    }

    /** Whether the account has an OIDC link, as {@code oidcLinked} of {@code GET /api/console/accounts/{id}}. */
    protected boolean linked(Session admin, long accountId) {
        JsonNode linked = account(admin, accountId).path("oidcLinked");
        if (!linked.isBoolean()) {
            throw new AssertionError("No boolean oidcLinked in account " + accountId + ": " + linked);
        }
        return linked.booleanValue();
    }

    /**
     * The audit entries that name the account as actor or as target, each once, oldest first (by {@code seq}). Other
     * tests write to the same chain; this sees only theirs if they touch this account.
     */
    protected List<JsonNode> auditOf(Session admin, long accountId) {
        Map<Long, JsonNode> byId = new LinkedHashMap<>();
        for (String filter : List.of("targetAccountId", "actorAccountId")) {
            for (JsonNode entry : PrivacySteps.entries(admin, filter, accountId)) {
                byId.putIfAbsent(entry.path("id").asLong(), entry);
            }
        }
        List<JsonNode> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparingLong(e -> e.path("seq").asLong()));
        return out;
    }

    /** The {@code action} of each entry, in order. */
    protected static List<String> actions(List<JsonNode> entries) {
        return entries.stream().map(e -> e.path("action").asString("")).toList();
    }
}
