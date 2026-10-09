# Test support

Shared helpers for the server tests of every slice. Tests talk to the app only through HTTP and its setting keys; they never use classes from `server/src/main`. Only the `s0-t0-base` tab (and later the main session) changes this package. If you need another helper, put it in your own package and mention it in your report.

## Start from a named setup

Extend one of these. Classes that extend the same setup share one Spring context (one app, one PostgreSQL container), so the suite stays fast and fits in memory.

| Class | Setup name | SMTP | Webhook |
|---|---|---|---|
| `BareAppTest` | bare | off: invitation responses carry the link, the app logs a line per mail | off |
| `ConnectedAppTest` | connected | GreenMail plain SMTP, `SMTP_ALLOW_PLAINTEXT=true` | `WebhookReceiver` |
| `StartTlsAppTest` | starttls | a server that requires STARTTLS, plain text not allowed | `WebhookReceiver` |

All setups set:

| Key | Value |
|---|---|
| `POPDESK_PORTAL_HOST` | `portal.popdesk.test` (`AppTest.PORTAL_HOST`) |
| `POPDESK_CONSOLE_HOST` | `console.popdesk.test` (`AppTest.CONSOLE_HOST`) |
| `POPDESK_PUBLIC_SCHEME`, `POPDESK_PUBLIC_PORT` | `http`, `80` (links have no port) |
| `POPDESK_ADMIN_EMAIL`, `POPDESK_ADMIN_PASSWORD` | `admin@popdesk.test`, `admin-Password-1` |

The SMTP setups also set `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `MAIL_FROM`, and `NOTIFY_WEBHOOK_URL`. Received mail and webhook requests are cleared before each test.

To change a key for one class, add `@TestPropertySource(properties = "KEY=value")`. Each different set of keys starts another context and another database, so do this only when the criterion is about that key. Keys set with `@DynamicPropertySource` in a setup (SMTP host and port, webhook URL) win over `@TestPropertySource`, and also over a `@DynamicPropertySource` in your subclass; to change one, put a `DynamicPropertyRegistrar` bean in a `@TestConfiguration` and `@Import` it on your class (`notify/StrictTransportTest`). At most 3 contexts stay open (`src/test/resources/spring.properties`).

Within a context, data from other test classes stays in the database. Never assume an empty table or a count from zero; use the unique values the fixtures make (`Unique`).

## What a test gets

| Member | What it is |
|---|---|
| `portal()`, `console()`, `client(host)` | A new `ApiClient`: one browser on one host |
| `fixtures` | `Fixtures`: test data through the API |
| `clock` | `TestClock`, the app's `Clock` bean. Stands still; `clock.advance(Duration)` moves it forward |
| `log` | `AppLog`: the app's standard output since this context started |
| `db` | `DbInspector`: read-only JDBC for schema, `pg_locks`, and single rows. It works in the app's schema, `current_schema()` of the app's `DataSource` (`db.schema()`), and sets it on its own connections, so unqualified table names work |
| `mail()` | `MailServer`: GreenMail, `awaitMessageTo(email)` |
| `webhook()` | `WebhookReceiver`: requests the app posted, `respondWith(500)` to make it fail |
| `port` | The app's HTTP port |

### ApiClient

```java
ApiResponse r = portal().get("/api/portal/issues").query("page", 2).send();
r.status(); r.header("Cache-Control"); r.json().path("items"); r.body(); r.sha256();
console().post("/api/console/companies").json(Map.of("code", "AB")).expect(400);
```

- Sends `Host: <host>` (without a port) to 127.0.0.1 on the app's port. `.header("Host", "<svg onload=1>")` sends any value.
- Sends `X-Forwarded-For` with an address of its own from 198.18.0.0/15. Loopback is a trusted proxy by default (`POPDESK_TRUSTED_PROXIES`), so the app counts every client as a different IP, and fixtures that sign in many times stay under the sign-in limit per IP. `client.forwardedFor("203.0.113.9")` picks the address, `client.withoutForwardedFor()` sends none (the app then sees 127.0.0.1), and `.header("X-Forwarded-For", ip)` replaces it for one request. Tests about the limit or about trusted proxies fix the address one of these ways.
- Keeps its own cookies. `cookie(name)`, `setCookie`, `clearCookies()`; `response.setCookie(name)` is the raw `Set-Cookie` with its attributes.
- POST, PUT, PATCH, DELETE send the CSRF token in `X-XSRF-TOKEN`, from the `XSRF-TOKEN` cookie. Without that cookie the client first calls `GET /api/public/csrf` and expects 204. `.withoutCsrf()` and `.csrfToken("wrong")` change that for one request.
- Multipart: `.jsonPart("issue", map).file("files", name, type, bytes)`, `.part("commentId", "7")`. Range and HEAD: `.header("Range", "bytes=0-99")`, `client.head(path)`.
- `send()` returns any status. `expect(status)` and `expectStatus(status)` fail with an `AssertionError` that shows the request and the body. A request with no response at all throws `ApiRequest.NoResponse`, also an `AssertionError`.

### Fixtures

Every step expects one status and fails with an `AssertionError` otherwise. Until the slice is built every endpoint answers 501, so a test fails at its first fixture step, on an assertion; the red check (C-6) needs that.

```java
class CustomerSeesOwnCompanyIssuesTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-47 a customer lists the public issues of their own company")
    void customerListsOwnCompanyIssues() {
        Session admin = fixtures.signInAdmin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Session alice = fixtures.customer(admin, a.code());
        Session bob = fixtures.customer(admin, b.code());
        Issue ofA = fixtures.fileIssue(alice, a.code());
        Issue ofB = fixtures.fileIssue(bob, b.code());

        ApiResponse list = alice.client().get("/api/portal/issues").expect(200);

        assertThat(list.json().path("items").findValuesAsString("key"))
                .contains(ofA.key())
                .doesNotContain(ofB.key());
    }
}
```

| Step | Call | Expects |
|---|---|---|
| `signInAdmin()`, `signIn(app, email, password)` | `POST /api/public/sign-in`, then `GET /api/{app}/me` | 204, 200 |
| `createCompany(admin)` | `POST /api/console/companies` | 201 |
| `invite(admin, role, codes...)` | `POST /api/console/invitations`; the token comes from `inviteLink` (bare) or from the mail (SMTP setups) | 201 |
| `accept(invitation)` | `POST /api/public/invitations/accept` on the person's host; signs them in | 204 |
| `customer(admin, codes...)`, `agent(admin, codes...)`, `admin(admin)` | invite and accept; `agent` also assigns | as above |
| `assignAgent(admin, code, accountId)` | `PUT /api/console/companies/{code}/agents/{id}` | 200 |
| `fileIssue(customer, code)`, `fileIssue(customer, NewIssue.in(code).privateIssue().file(...))` | `POST /api/portal/issues`, JSON or multipart | 201 |
| `comment`, `internalComment` | `POST /api/{app}/issues/{key}/comments` | 201 |
| `attach`, `attachToComment` | `POST /api/{app}/issues/{key}/attachments` | 201 |

Invited accounts get the password `Fixtures.PASSWORD`. Emails, company codes, and titles are unique per JVM. When a test must find a value later (in a log, a mail, a webhook body), pass one it made itself.

### Waiting

Use `Wait.until`, `Wait.untilAsserted`, and `Wait.holds` (or the `await...` methods of `MailServer`, `WebhookReceiver`, and `AppLog`), never `Thread.sleep`. They end a timeout with an `AssertionError`; Awaitility's own timeout exception would count as an error in the red check. `Wait.holds("no webhook", Duration.ofSeconds(3), () -> webhook().containing(key).isEmpty())` checks that nothing happens.

### Mail over TLS

`StartTlsAppTest` points the app at a server that offers STARTTLS and refuses mail until the client switched. Its certificate (`src/test/resources/support/mail-server.p12`, self-signed for `localhost` and `127.0.0.1`) is trusted by the JVM's default SSL context, which the support code replaces once. `mail().startTlsConnections()` and `mail().plainTextRefusals()` show what happened. GreenMail's plain port offers no STARTTLS at all, so a context that points `SMTP_PORT` at `mail().plainPort()` without `SMTP_ALLOW_PLAINTEXT` checks that the app refuses to send.

### Log

`log.all()` holds everything since the context started, including the startup log. Take `AppLog.Mark m = log.mark()` before the request and read `log.since(m)` or `log.jsonLines(m)`. Other contexts' background work can write between, so search for your own values.

## Criterion IDs

Put the criterion ID at the start of `@DisplayName`: `@DisplayName("S0-AC-41 files an issue with key CODE-n")`. A test that checks several names them all: `@DisplayName("S0-AC-47, S0-AC-92 ...")`. Method names cannot hold `-`, so the ID goes in the display name only.

`scripts/ci/trace.py` counts every `S0-AC-<number>` written anywhere in a test file, comments included. Write an ID only where that file tests it, and never one the slice does not define.

## Rules

- No mocks, no `@Disabled`, no fixed waits. Check with `python scripts/ci/check-test-style.py --all-strict` from the repository root.
- No classes from `server/src/main`. The database (`db`) is only for what HTTP cannot show: schema, locks, row state. Create data with `fixtures`.
- Run only your tests: `./mvnw test -Dtest='io.popdesk.<your package>.**'`.
