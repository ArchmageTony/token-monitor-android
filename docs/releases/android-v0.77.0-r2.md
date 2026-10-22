# Android v0.77.0 r2

While a coding session is active, the desktop Hub sends a complete update every
few seconds. On my setup that was 132 complete updates in five minutes, about
260 KB each. The dashboard now applies at most one complete update every five
seconds and always ends on the newest one, which cuts decoding and redraw work
on the phone.

- The first update after a quiet period still shows at once.
- The Hub still sends every update, so this doesn't reduce data received. I've
  asked about that on the desktop side in
  [Javis603/token-monitor#1166](https://github.com/Javis603/token-monitor/issues/1166).
- Widget Live, the layout, animations and permissions are unchanged.
- Desktop compatibility stays at v0.77.0.

I haven't phone-tested this build. The Pages widget check after an in-place
update is still open in
[issue #60](https://github.com/The-Minion-oOo/token-monitor-android/issues/60).

Local validation passed: 277 JVM tests, lint, documentation checks, and all 69
instrumentation tests on the API 37 emulator, including two new tests against a
fixture Hub. Both API 36 and API 37 suites also ran in CI.

To update, see
[Installing and updating](https://github.com/The-Minion-oOo/token-monitor-android/blob/main/docs/INSTALL.md).
For what was checked, see
[validation](https://github.com/The-Minion-oOo/token-monitor-android/blob/main/docs/VALIDATION.md).
