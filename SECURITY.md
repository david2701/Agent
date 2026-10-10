# Security policy

Kairo is a self-hosted assistant that holds personal data: notes, tasks, calendars, recordings and conversations. We
take security reports seriously.

## Supported versions

Kairo does not have long-term support branches. Security fixes are made on the `main` branch and in the latest
release. If you run an older version, update before you report a problem, if you can.

| Version | Supported |
|---|---|
| Latest release and `main` | Yes |
| Anything older | No |

## Reporting a vulnerability

**Please do not open a public issue, pull request or discussion for a security problem.**

Report it privately with GitHub's security advisories:

1. Go to the **Security** tab of the repository.
2. Click **Report a vulnerability** (or open `https://github.com/david2701/Agent/security/advisories/new`).
3. Describe the problem. Include the affected part (backend, dashboard, Android, iOS), the version or commit, the steps
   to reproduce it, and the impact as you understand it.

What to expect:
- We will confirm that we received your report as soon as we can.
- We will work with you to understand and fix the problem, and we will keep you informed of progress.
- When a fix is ready, we publish it together with an advisory. We are happy to credit you, if you want.

Please give us a reasonable amount of time to fix the problem before you disclose it publicly. Do not access, change
or delete data that is not yours while you test. Test against your own server.

Never include real secrets, other people's personal data or your Nan API key in a report.

## Threat model in short

Kairo is meant to run on a server you control, behind HTTPS, for you and the people you trust with an invitation.
The main risks we design against are:

### Prompt injection between family members and from outside content

Anyone in a household can write shared items, and the assistant also reads links, shared pages, images, Google
Calendar events and results from MCP servers. All of this is treated as **data, never as commands**:

- Content from other people or from outside is marked as data before it reaches the model, and member, list and place
  names are cleaned and shortened.
- If the assistant read such content in a conversation, sensitive actions are not executed. These are sharing, sending
  notices, writing to shared items or another member's items, scheduling reminders or tasks for someone else, saving
  to memory, using MCP tools, and deleting or replacing content.
- Instead, the server asks for confirmation. It runs the action only if your next message in that conversation is a
  plain "yes". The pending action expires after 10 minutes, and the assistant cannot confirm for you.
- Deleting several things in one step also needs confirmation, even without outside content.

See [`docs/FAMILY.md`](docs/FAMILY.md#protection-against-hidden-instructions).

### Server-side request forgery (SSRF)

Kairo fetches URLs on behalf of users (link previews, MCP servers, push endpoints). To stop it from reaching your
internal network:

- Link previews only download from public addresses. Every address a host resolves to is checked, redirects are
  limited and checked again, and downloads are limited in size and time.
- MCP server URLs are validated when you save them and before each request. Only `http` and `https` are allowed.
  Credentials in the URL, unusual IP notations, redirects and loopback or link-local addresses (such as cloud
  metadata) are rejected. Private networks are only allowed if the operator sets `KAIRO_MCP_ALLOW_PRIVATE=true`, and
  extra exact hosts only through `KAIRO_MCP_ALLOWED_HOSTS`.
- Push endpoints must be `https` and public, unless the operator allows otherwise with `KAIRO_PUSH_ALLOW_PRIVATE` or
  `KAIRO_PUSH_ALLOW_HTTP`.
- Uploaded files must be real images, and files are served isolated from the dashboard with a restrictive
  content-security policy.

### Authentication and sessions

- Passwords are hashed with bcrypt.
- Access tokens are short-lived (one hour). Refresh tokens rotate on every use, are bound to a device, and expire after
  90 days without use. Reusing a rotated token signs that device out.
- On the web, the refresh token is in an `HttpOnly`, `SameSite=Strict` cookie. Apps store it encrypted (Android
  Keystore, iOS keychain).
- Repeated failed sign-ins for an email are blocked for up to 15 minutes. Watch pairing codes are single-use and are
  cancelled after repeated failures. Invitation codes are single-use and expire. Access codes (password resets) are
  single-use, expire after 24 hours, notify the whole family when generated and used, and sign the member out.
- Changing a password signs out the other sessions. Removing a member ends their sessions immediately.
- Each person can see and sign out their devices in *Settings > Devices*.
- AI requests are rate-limited per person, which also limits the damage from a stolen session.

### Data at rest and in transit

- Google tokens, MCP server headers and push keys are stored encrypted with AES-256-GCM, using `KAIRO_ENCRYPTION_KEY`.
- Push through UnifiedPush is encrypted end to end (RFC 8291). Apple push (APNs) is not end-to-end encrypted: Apple can
  see the notification text.
- The server container runs without root privileges and only listens on `127.0.0.1`. HTTPS, HSTS and security headers
  come from the reverse proxy and the server.

### What Kairo does not protect against

- **Whoever runs the server.** The operator has the database and the `.env` file, so they can technically read
  everything. Choose your administrator and your host accordingly.
- **A compromised phone, browser or Nan account.** Audio and text needed to answer you are sent to Nan.
- **Family administrators can reset a member's password.** This is visible to the whole family by design (see
  above), but it is not blocked.
- Third-party services you connect, such as MCP servers, run with the access you give them.

## What operators must do

- Run Kairo behind HTTPS (the example Caddy config in [`deploy/Caddyfile.example`](deploy/Caddyfile.example) does this).
  Open only ports 22, 80 and 443 on the server. Do not put another proxy in front of Caddy without checking how
  client IPs are read.
- Use your own random secrets. Kairo refuses to start with the example values for `KAIRO_JWT_SECRET`,
  `KAIRO_ENCRYPTION_KEY` or the initial password, so use `./scripts/generate-env.sh`.
- Change the initial administrator password straight away, and use a **new** Nan API key.
- Back up `.env` and the `./backups` folder somewhere safe and off the server. Without `KAIRO_ENCRYPTION_KEY`, stored
  tokens in a backup cannot be read.
- Keep Kairo and the server's operating system up to date (see [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md#8-maintenance)).
- Leave `KAIRO_MCP_ALLOW_PRIVATE`, `KAIRO_MCP_ALLOWED_HOSTS`, `KAIRO_PUSH_ALLOW_PRIVATE` and `KAIRO_PUSH_ALLOW_HTTP` off
  unless you need them, and only add hosts you trust.
- Only invite people you trust with an invitation, and watch the family notices for access codes you did not ask for.
- Never commit `.env`, keystores or `.p8` keys. They are in `.gitignore`, but check before you push.
- Sign your Android builds with your own keystore and keep it safe.
