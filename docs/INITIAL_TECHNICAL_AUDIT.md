# Launcher Lite — Initial Technical Audit

Date: 2026-11-25
Branch: rebuild/product-grade-foundation
Baseline commit: 7edb7d3c275bbe3c8eaab3a14088593879f08e8f

## Scope and evidence

This audit is based on the repository source inspected on this date and the user's prior device diagnostics. It is not a complete APK decompilation and does not claim to know Monet's private implementation. Monet's distributed repository explicitly says its source is not included; its public product description documents an optional AccessibilityService used for launcher behavior. The supplied Monet 1.1.2 APK was previously inspected for DEX strings, which revealed concepts related to HOME recovery, boot, accessibility lifecycle, window state, and restart handoff. String names alone do not prove execution order or implementation details.

## Findings in the current Lite branch

### 1. MainActivity is an oversized monolith
- MainActivity.kt is 1,791 lines.
- It currently owns UI composition, app catalog rendering, channel favorites, setup wizard, HOME role request, accessibility settings, weather network request, notifications UI, screensaver, focus, and app launching.
- This concentration makes regressions hard to isolate and unit-test.

### 2. HOME handling is not yet an engineered subsystem
- Manifest declares MainActivity as ACTION_MAIN + CATEGORY_HOME + CATEGORY_DEFAULT, as well as LEANBACK_LAUNCHER and LAUNCHER.
- MainActivity requests ROLE_HOME via RoleManager on API 29+, but HomeRoleController only reads the role.
- The prior device observations show that the reported HOME role holder and the actual foreground activity did not always agree on this OEM firmware.
- Therefore, isRoleHeld() and resolveActivity() must be treated as diagnostic signals, not proof of actual key dispatch.
- Android's KeyEvent reference states KEYCODE_HOME is handled by the framework and is never delivered to normal application activities. Do not build a normal Activity key handler expecting to receive HOME.
- AccessibilityService can observe/filter key events via onKeyEvent, but the device-specific delivery and side effects must be measured; it is not a universal guarantee or a reason to relaunch the Activity blindly.

### 3. Accessibility service currently observes window transitions only
- XML subscribes to typeWindowStateChanged and declares canRetrieveWindowContent=false.
- The service logs selected package transitions and does not reclaim foreground. This is safer than the previous behavior that relaunched MainActivity when Google TV appeared, but it does not implement Monet-equivalent HOME key observation/recovery.
- No HOME key event callback is currently implemented.
- Do not add recovery behavior until a target-device trace demonstrates the failure mode and proves the proposed action will not close OEM panels.

### 4. Focus restoration is fragile and incomplete
- Focus is remembered by a string stored in View.tag.
- The coordinator searches the current view tree and requests focus when the Activity regains window focus.
- renderApps() removes and recreates all app cards, then requests focus by a numeric child index. This can invalidate view identity and may restore focus to a different app after list changes.
- renderChannels() also removes and recreates its children when refreshed.
- Stable item identity and explicit screen state should replace index-only restoration.

### 5. Activity lifecycle currently performs UI/data work directly
- onResume() refreshes notifications, renders channels, may reopen setup wizard, queries installed apps, and may rebuild app cards.
- There is no explicit state machine for return-from-app, system panel, standby, boot, or process recreation.
- Delayed UI tasks and network work need lifecycle ownership/cancellation.

### 6. Platform/configuration concerns
- compileSdk=35, minSdk=23, targetSdk=28, Android Gradle Plugin 8.7.3, Kotlin 2.0.21.
- The app intentionally retains targetSdk 28 with a lint suppression. This needs a documented compatibility/security review rather than a casual target SDK bump or indefinite legacy retention.
- The manifest includes INTERNET, an AccessibilityService, and a NotificationListenerService. Both optional services must remain isolated from core HOME startup.
- Weather lookup is hard-coded to latitude/longitude and directly uses a raw Thread + HttpURLConnection inside MainActivity. This is not production-ready location/configuration architecture.
- App catalog repository returns ResolveInfo objects, coupling data layer to PackageManager model types; use app-domain models instead.
- Favorite channels use SharedPreferences JSON and currently silently return an empty list on malformed data; add migration/backup/error handling tests.

### 7. OEM gear/settings side panel
- The gear key and side panel are controlled by the device firmware/system UI.
- Launcher must not intercept or simulate the gear action, relaunch itself on System UI window changes, or restore focus while an OEM panel is visible.
- Need capture of exact key/event trace on this firmware before implementing any related integration.

## Architecture direction

Build an independently owned launcher core, retaining Android platform APIs and optional service integrations behind narrow interfaces.

Proposed modules (logical boundaries; Gradle modules only where justified):
- app-shell: Activity lifecycle and window integration only.
- home-contract: HOME intent/role diagnostics and device capability snapshot.
- input-observer: optional accessibility key observation; no foreground action by default.
- navigation-state: deterministic state machine for launcher, external app, system overlay, standby, and recovery.
- focus-engine: stable semantic focus keys and restoration policy.
- app-catalog: app-domain models, query, launch resolution, package-change invalidation.
- persistence: versioned settings, favorites, migration and corruption recovery.
- widgets/features: clock, weather, notifications, channels and screensaver isolated from launcher core.
- diagnostics: structured, bounded logs and exportable device report.
- quality: unit tests, instrumentation tests where supported, and a manual Realme regression suite.

Do not split into many Gradle modules merely for appearance. First establish interfaces and tests; split build modules when dependency boundaries justify the maintenance cost.

## First critical investigation

The first implementation target is not the UI. It is the HOME dispatch/recovery contract on the Realme Stick:
1. Record role holder, resolved HOME candidates, foreground package/activity, and accessibility service state.
2. Capture HOME press behavior with Accessibility disabled and enabled, without any automatic relaunch logic.
3. Compare the trace when Monet is selected and when Lite is selected, with Google TV left enabled.
4. Determine whether the firmware ignores the selected HOME handler, launches a stock HOME before the selected handler, or uses a vendor-specific input path.
5. Only then implement a bounded state machine with explicit suppression while System UI/OEM overlays are active.

## External references consulted

- Android KeyEvent API: KEYCODE_HOME is framework-handled and not delivered to ordinary applications.
- Android RoleManager API: ROLE_HOME availability and holder state are queryable; role grants are user-consented.
- Android AccessibilityService API: onKeyEvent can observe/filter events before normal delivery, but event-stream handling and device behavior must be tested.
- Monet public GitHub distribution repository: says source code is not included in that repository.
- Monet Play Store listing: describes its optional AccessibilityService as observing Home key presses and launcher window changes, and says the service is optional.

## Explicitly unresolved

- Exact Realme firmware HOME dispatch path.
- Whether AccessibilityService receives the HOME key on this firmware and under what settings.
- Monet's exact recovery state machine and timing.
- Gear button keycode/vendor handling and panel lifecycle.
- Whether prior branch focus behavior has regressions on the target device.
