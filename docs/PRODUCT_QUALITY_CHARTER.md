# Launcher Lite — Product Quality Charter

## Product commitment

Launcher Lite is developed as a commercial-grade Android TV product, not as a sequence of quick patches. The user's criticism is a permanent project requirement: previous work changed internal code and produced a successful APK build without fixing the observed device behavior. That is not an acceptable definition of completion.

## Non-negotiable rules

1. A successful compilation is necessary but never sufficient for release.
2. No speculative foreground-stealing, HOME relaunch loops, or Accessibility window-event reactions.
3. Google TV must remain installed and enabled. Do not disable, hide, uninstall, or replace its package.
4. The OEM Settings/gear side panel remains system-owned. Launcher code must not dismiss, cover, or reclaim focus from it.
5. Preserve the approved visual layout unless the user explicitly requests a design change.
6. Keep the implementation self-owned: no third-party launcher or helper app is required for core launcher behavior. Android platform APIs are allowed; optional integrations must be isolated and nonessential.
7. Research platform behavior and device-specific constraints before implementing a mechanism.
8. Every behavior change requires automated checks where feasible, a recorded manual test plan, and evidence from the target Realme Stick before release.
9. Never describe an untested behavior as fixed. Report limitations and test status honestly.
10. Keep the previous stable APK and Git branch intact; development happens on a dedicated branch.

## Release gates

- Build and static validation pass.
- Unit tests pass; add regression tests for every reproducible defect.
- Manifest, package visibility, permissions, signing identity, versioning, and upgrade path are checked.
- HOME role and actual HOME dispatch are verified separately; a role-holder query alone is not proof that the firmware dispatches HOME to this launcher.
- Remote navigation and focus restoration are verified after app launch/return and overlay dismissal.
- OEM Settings/gear panel remains open and usable.
- Cold boot, wake from standby, process recreation, and repeated HOME presses are tested.
- No crash, ANR, or unbounded recovery loop in the test run.
- APK is not called release-ready until the target device tests are recorded as passed.

## Target device baseline

- Device: Realme Stick / Android TV
- Android: 14, SDK 34
- Build fingerprint supplied by user: realme/HP4607_realme/YYT:14/UKG3.250826.001/C3.2.8_20260408:user/release-keys
- Google TV package remains enabled.
- Monet Launcher 1.1.2 is a behavioral reference and reportedly works correctly on this device.
- User reports the previous Launcher Lite build remained faulty; the previous iteration is not considered a successful release.

## Working method

1. Reproduce and document the failure.
2. Inspect source, manifest, runtime logs, and platform documentation.
3. Form a falsifiable hypothesis and identify side effects.
4. Implement one isolated change.
5. Run automated tests and compare logs.
6. Test on the Realme Stick.
7. Keep or revert based on evidence.
