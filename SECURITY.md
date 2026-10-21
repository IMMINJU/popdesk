# Security Policy

## Reporting a vulnerability

Please **do not** open a public issue, discussion, or pull request for a security problem.

Report it privately through GitHub: open the repository's **Security** tab and choose **Report a vulnerability**. This creates a private advisory that only the maintainers can see.

Include what you can:

- the affected version or commit,
- how to reproduce it (steps, requests, or a small proof of concept),
- what an attacker could do with it,
- whether it is already public.

## What to expect

- We acknowledge a report within 7 days.
- We tell you whether we accept it, and our plan, within 30 days.
- We fix accepted issues in a release and credit you in the advisory, unless you ask us not to.

popdesk is maintained by volunteers, so these are goals, not guarantees.

## Supported versions

Until 1.0, only the latest release receives security fixes. Upgrade to the latest release before reporting, if you can.

## Scope

In scope: the popdesk server, the customer portal, the agent console, and the container image built from this repository, and the compose files.

Out of scope: issues that need an already compromised host or database, missing hardening on a deployment that does not follow the operating guide, and reports produced only by automated scanners without a demonstrated impact.

Things we treat as serious even if they look small: a customer seeing data of another customer company, internal notes or tasks reaching a customer, and account takeover.
