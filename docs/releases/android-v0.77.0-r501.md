# Android v0.77.0 r501 (Remote)

The first release of this fork signed with its own key.

- Public HTTPS route: a Hub published behind an HTTPS reverse proxy can be paired
  by its `https://` address. Plain `http://` is refused for public hosts, and the
  certificate is checked against Android's system trust store. Tailscale and home
  Wi-Fi routes work as before.
- Fork updates: Settings → App updates checks only this fork's releases and
  installs only APKs signed with this fork's certificate.
- Background widget refresh: while a widget is placed, it refreshes every 30
  minutes by default (15, 30 or 60, or off, under Settings → Home-screen widget).
  No notification, no wake lock. A failed refresh keeps the saved numbers and
  marks them STALE.
- Version code 770501. Desktop compatibility stays at v0.77.0.

If a debug build of this fork is installed, uninstall it once before installing
this APK, then pair again. Android refuses to update across signing keys.
