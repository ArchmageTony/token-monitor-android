# Android v0.77.0 r1

I updated the desktop baseline to v0.77.0 and reduced work during dashboard
updates while keeping the layout, animations and update frequency.

- New API accounts show reported Today, Month, rolling seven-day and All-time
  spend in their native currency. Missing periods stay absent.
- I checked Cline account separation and unknown Cursor quotas against the
  released Hub, alongside the existing protocol regressions.
- Complete updates avoid repeated JSON conversion and reuse unchanged data.
  Home sessions, history preparation and startup cache decoding run off the UI thread.
- Dashboard instances keep separate Live ownership; Refresh restores the
  visible dashboard without letting Settings acquire a stream.
- Widgets share cache reads and avoid duplicate redraws during Live or Refresh.
  The responsive chart uses its measured image size with the existing layout.

I have not phone-tested this build. The Pages
widget redraw after an in-place install still needs checking on my phone;
emulators recovered without reopening the app. These checks do not establish
physical-phone battery savings.

Local validation passed: 271 JVM tests with the released-Hub responses supplied,
67 tests on each API 36 and API 37 emulator, lint, debug and optimized builds,
14 tooling tests, and an optimized API 26 install/launch/resume check. I reviewed
the full diff and the native spend-row capture. The optimized smoke APK used an
emulator-only signing key, separate from the production release signing key.

The unresolved phone update check is tracked in
[issue #60](https://github.com/The-Minion-oOo/token-monitor-android/issues/60).

See [validation](https://github.com/The-Minion-oOo/token-monitor-android/blob/main/docs/VALIDATION.md)
and [upstream compatibility](https://github.com/The-Minion-oOo/token-monitor-android/blob/main/docs/UPSTREAM_SYNC.md).
