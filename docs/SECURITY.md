# Security and privacy

## Network boundary

The app accepts Tailscale addresses by default. Private LAN addresses require an
explicit setting. Upstream rejects every public target; this fork accepts a
public target only as an `https://` domain name (see
[Public HTTPS route (fork)](#public-https-route-fork)), so Hub credentials are
never sent to an internet host without TLS.

[Tailscale](https://tailscale.com/docs/reference/connection-types) normally
attempts a direct device-to-device connection and can fall back to an
end-to-end encrypted relay when network conditions require it. The app does not
operate a cloud relay or require the desktop Hub to be exposed on the public
internet.

The desktop Hub serves plain HTTP. Over Tailscale the traffic is encrypted by
Tailscale's WireGuard tunnel. On the optional home-network (LAN) route it is not
encrypted, so other devices on the same Wi-Fi could observe the Hub secret and
usage data. Use the LAN route only on a network you trust, and prefer Tailscale.

Before any request, including the identity check, the app confirms the route
fits the phone's current networks. A Tailscale address or MagicDNS name is
tried only while a VPN is connected, because without Tailscale the 100.64/10
range belongs to carrier-grade NAT. A home address is tried on Wi-Fi or
Ethernet, or through a VPN such as a Tailscale subnet router, and never directly
over mobile data. `.ts.net` names must resolve to Tailscale addresses and
`.local` names to private IPv4 addresses, so a hostile DNS answer cannot move
the secret to a public host.

The desktop Hub secret is sent as a bearer credential only to the validated Hub address. It is never logged or included in diagnostics.

The unauthenticated health response must identify a Token Monitor Hub before the
first protected request is sent. HTTP redirects are rejected so an authenticated
request cannot move the bearer secret to another host.

## Public HTTPS route (fork)

This fork adds a third route for a Hub published on a server behind an HTTPS
reverse proxy, for example `https://hub.example.com`.

- Only `https://` is accepted for a public host. A bare name such as
  `hub.example.com` becomes `https://hub.example.com`; the port defaults to 443,
  not the desktop Hub's 17321. `http://` public addresses are rejected when
  saved, and again before every request (route guard and HTTP client), so a
  tampered or stale record cannot send the secret in plain text.
- The host must be a public DNS name: IP literals, single-label names,
  `localhost`, and private-use suffixes such as `.local`, `.internal`, `.lan`
  and `.home.arpa` are refused. Credentials, query strings (for example
  `?secret=`) and paths are refused as for the other routes.
- TLS uses Android's system trust store only; user-installed CAs are not
  trusted and no certificate pinning is applied, so the proxy's normal
  certificate renewal keeps working. Certificate and hostname failures stop the
  request before the secret is sent and are reported as such.
- The public route is usable on any network, including mobile data. The
  optional home Wi-Fi fallback must still be a private address and is preferred
  while it answers.
- Cleartext stays enabled in the network security config only because the
  Tailscale and LAN routes use plain HTTP to IP literals, which the config
  cannot scope by address range. The HTTPS requirement for public hosts is
  enforced in code and covered by unit tests.
- The Hub secret also authorizes writes on the Hub. Use a long random secret,
  keep the Hub bound to loopback behind the proxy, and rotate the secret on the
  Hub and every desktop if the phone is lost.
- The in-app updater in fork release builds checks only the fork's own GitHub
  Releases and accepts only APKs signed with the fork's release certificate.
  Debug builds have no updater.

### Background widget refresh (fork)

While at least one widget is placed, a saved pairing exists and the user has not
switched it off, one periodic JobScheduler job (15, 30 or 60 minutes, default 30,
any network) loads one Hub snapshot with the same five read-only `GET` requests
and the same route and HTTPS checks as a manual Refresh. It shows no
notification, holds no wake lock of its own and gives up after 45 seconds. It
skips its turn while widget Live, a widget Refresh or the dashboard is active. A
rejected secret stops the schedule until the pairing is saved again or the Hub
accepts the secret in the foreground. A failure keeps the saved snapshot on the
widget, marked as stale. `RECEIVE_BOOT_COMPLETED` is held only so JobScheduler
can keep the job across a reboot; the app registers no boot receiver.

## Local storage

- The Hub URL and secret are encrypted with a non-exportable Android Keystore key.
- The app-private snapshot cache stores compressed Hub JSON responses, including usage and account/device/project metadata returned by the Hub. It is not separately encrypted by the app; Android app isolation protects access. Pairing credentials use the separate Keystore-backed store.
- Android backup/data-transfer rules exclude pairing and snapshot storage.
- A secret typed into the connection form is held only in memory, not saved in
  Activity instance state. Home-address repair reads the existing encrypted
  pairing and does not expose its secret back to the form.

## Read-only API use

The app calls only:

- `GET /api/health`
- `GET /api/stats`
- `GET /api/devices`
- `GET /api/history`
- `GET /api/subscriptions`
- `GET /api/stats/stream`

It does not call ingest, subscription mutation, or device deletion routes.

Two optional connection features operate only within private address ranges:

- The **home Wi-Fi fallback** is a second saved address that must pass the
  same private-network validation. The secret is sent only to whichever of the
  two saved addresses identifies itself as a Hub.
- **Find** searches the private `/24` network of the phone's current Wi-Fi or
  Ethernet connection (never mobile data or a VPN) for a Hub by reading the
  unauthenticated `/api/health` endpoint. It runs only when tapped, never sends
  the secret, and stops as soon as one Hub answers.

The Status view separately reads public service-health endpoints for Claude,
OpenAI, Cursor, and DeepSeek. Those requests do not include Hub credentials
or Token Monitor usage data.

In this fork, release builds check the fork's own repository and pinned fork
certificate, both fixed at build time; debug builds hide the panel.
Opening App updates checks this app's latest published GitHub release. A
download starts only after the user taps Download and install. These HTTPS
requests do not include the Hub secret or usage data. Before handing the APK to
Android, the app verifies the release checksum, package identity, version code,
and pinned release signing certificate. Android controls installation approval.

## Lifecycle and resource use

A user-started dataSync foreground service supports widget Live for one hour with
a 30-second refresh cadence, or a single refresh for up to 45 seconds. It shares
the dashboard repository, requests notification permission to expose Stop, and
stops without restarting after expiry, removal of the last widget, or a process
kill. There is no wake lock, boot receiver, analytics SDK, or advertising SDK.
The only scheduled work is the fork's optional periodic widget refresh described
above.

`ACCESS_NETWORK_STATE` lets an active dashboard or widget session reconnect after a route changes.
On Android 17 and newer, `ACCESS_LOCAL_NETWORK` is requested for a chosen LAN
operation. Denial or revocation excludes home routes without erasing pairing;
Tailscale routes remain eligible. Discovery sends no secret. Saving a replacement
home address requires a successful authenticated check of that address.
The home-screen widget reads saved data while Live is off and receives current snapshots during an explicit session. It
shows saved totals, estimated cost, quota windows, and a small usage chart according
to its size. Adding it makes those figures visible on the launcher; account emails,
project labels, session identifiers, and connection credentials are omitted.

## Data visible on the phone

Hub responses can include device names, model/tool attribution, project folder labels, session identifiers and titles, account email/plan metadata, costs, and normalized limits. Conversation titles can reveal sensitive text and appear in Home and Sessions when the Hub provides them; they are not displayed on the home-screen widgets. Account emails are hidden by default and can be shown only through an explicit display setting. The Hub does not send absolute project paths or prompt/response transcript bodies.

When **Show session titles** is off, search also excludes title text. The cached
Hub response may still contain titles; hiding them does not delete stored data.

## Reporting a problem

Do not include a Hub secret, unredacted account email, raw Hub response, or private tailnet address in a public issue. Reproduce with the sanitized fixture Hub when possible.
