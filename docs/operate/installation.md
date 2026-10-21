# Installation

How to install popdesk with Docker Compose, start it for the first time, and set up the first people and customer companies.

This page describes the code on the `main` branch, which will be released as 0.5.0. The latest release is 0.4.0, and the steps below install it; they were checked on 0.4.0. A few settings named here are new on `main`; the [0.4.0 configuration](https://github.com/IMMINJU/popdesk/blob/v0.4.0/docs/operate/configuration.md) lists what 0.4.0 reads.

## What you need

- Docker Engine 25 or later with Compose v2 ([Docker Compose](configuration.md#docker-compose)).
- Git, to check out the release tag. No image is published yet, so you build it yourself.
- About 1 GB of memory for the two containers. Measured before the first release: the app used up to about 0.6 GB and PostgreSQL up to about 0.35 GB ([Memory and JVM options](configuration.md#memory-and-jvm-options)).

The Compose file runs two containers: `app` (popdesk) and `db` (PostgreSQL 16). To use a database you run yourself, read [A database you run yourself](configuration.md#a-database-you-run-yourself) first.

## Get the release and build the image

Take the Compose file from the tag of the release you install, not from the `main` branch. The Compose file of a release names the image of that exact version, so build the image under that name:

```
git clone https://github.com/IMMINJU/popdesk.git
cd popdesk
git checkout v0.4.0
docker build -t ghcr.io/imminju/popdesk:0.4.0 .
```

## Write the settings

Put the settings in an `.env` file next to the Compose file, `deploy/.env`. Three are needed for the first start:

```
POPDESK_DB_PASSWORD=choose-a-database-password
POPDESK_ADMIN_EMAIL=admin@example.com
POPDESK_ADMIN_PASSWORD=choose-a-long-admin-password
```

- `POPDESK_DB_PASSWORD` is used by both containers. Compose does not start without it.
- `POPDESK_ADMIN_EMAIL` and `POPDESK_ADMIN_PASSWORD` create the first admin when the database has none. The password must be 10 to 72 characters and not the same as the email. After the first start you can remove `POPDESK_ADMIN_PASSWORD`.
- The host names default to `portal.localhost` (customer portal) and `console.localhost` (agent console). These names only work in a browser on the same machine. To open popdesk from other machines, set `POPDESK_PORTAL_HOST` and `POPDESK_CONSOLE_HOST` to two names that point at the server; both apps are served on the same port and told apart by the host name.

Every other setting, with its default, is in [Environment variables](configuration.md#environment-variables). Keep the secrets out of the Compose file ([Secrets](configuration.md#secrets)). Without `SMTP_HOST` popdesk sends no email; see [email.md](email.md).

## Start it

```
docker compose -f deploy/compose.yml up -d
docker compose -f deploy/compose.yml ps
```

The app is ready when `ps` shows it as `healthy`. On the first start popdesk runs its database migrations and creates the first admin before it opens its ports, so health checks cannot connect for a while. Compose gives it 3 minutes; on a first start without a first admin, the app turns `unhealthy` within 5 minutes ([Health checks while starting](configuration.md#health-checks-while-starting)).

If the app stays unhealthy, read its log with `docker compose -f deploy/compose.yml logs app`. A missing first admin, or a value popdesk does not take for `POPDESK_TIMEZONE`, `POPDESK_LOCALE`, or `POPDESK_AUDIT_RETENTION`, keeps it running without serving requests; the log names the setting. Fix the value in `deploy/.env` and run `docker compose -f deploy/compose.yml up -d` again.

Then open `http://console.localhost:8080` and sign in as the admin. Customers use `http://portal.localhost:8080`.

## Put a TLS proxy in front

popdesk serves plain HTTP only, on port `8080`, and the Compose file opens that port on all of the host's network interfaces. Until a proxy is in front, keep the port closed to the outside with your firewall. Before real people use it, put a reverse proxy in front that terminates TLS, and set `POPDESK_PUBLIC_SCHEME=https` and `POPDESK_PUBLIC_PORT=443`. Read [TLS](configuration.md#tls) for the trusted proxy addresses and a Caddy and an nginx example, and give the proxy a body limit of at least 55 MB ([Request body size at the proxy](configuration.md#request-body-size-at-the-proxy)).

## First steps in the console

Signed in as the admin:

popdesk has four roles: admins run the installation, agents answer issues (requests from customers) of the companies they are assigned to, customers file and follow their company's issues in the portal, and a customer manager is a customer who may also invite people of their company. Staff means admins and agents.

1. **Invite staff.** Invite the agents, and other admins if you need them. Each gets an invitation email with a link that works for 7 days. Without `SMTP_HOST`, the console shows the link so you can pass it on yourself.
2. **Create the customer companies.** Each company has a code of 2 to 10 capital letters and digits. It goes into issue keys such as `ACME-12` and cannot be changed later, so choose it with care.
3. **Assign agents to companies.** An agent works only on the companies assigned to them. Admins see every company.
4. **Invite customers.** Each customer belongs to at least one company. You can mark a customer as manager of a company; a manager can then invite more customers of that company in the portal.

Who may do what is in [permissions.md](../use/permissions.md#accounts-and-sign-in) and [Customer companies](../use/permissions.md#customer-companies).

## Next

- [Back up](backup.md) before you rely on it.
- Set up [email](email.md) and, if you like, the [team webhook](../integrate/webhooks.md).
- [Size and rate limits](limits.md).
- [Upgrading](upgrade.md) to a new release.
