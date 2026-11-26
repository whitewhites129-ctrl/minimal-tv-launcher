# HOME and Accessibility — Research Findings

## Verified Android platform facts

1. Android's RoleManager exposes ROLE_HOME and whether an app currently holds it. This describes role assignment; it does not provide a vendor-independent guarantee about how a particular remote-control HOME key is routed by OEM firmware.
2. AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS requests that the service receive key events before applications. The service must declare canRequestFilterKeyEvents in its XML metadata; otherwise the flag is ignored.
3. AndroidX documentation explicitly notes that only one enabled accessibility service can filter key events at a time. If another service already owns filtering, this service will not receive those events.
4. Activity lifecycle and window focus are separate signals. A system panel or alert can take window input focus while the Activity remains resumed. Callback ordering is not guaranteed.
5. Monet's public Play Store description explicitly says its optional AccessibilityService detects Home-key presses and launcher window changes. Its public GitHub repository says the source code is not included; therefore its exact internal algorithm cannot be asserted from public source.

## Direct comparison with current Launcher Lite

- Current accessibility XML subscribes only to typeWindowStateChanged.
- It does not declare canRequestFilterKeyEvents.
- Current service does not override onKeyEvent.
- Therefore, the current Lite service is not configured to receive global filtered key events in the documented Android accessibility mechanism.
- Current service only logs selected window package transitions and intentionally performs no foreground action.

This is a concrete architectural gap relative to the publicly described Monet behavior. It is not yet proof that enabling key filtering will capture the HOME key on the Realme firmware, nor proof that the HOME key is filterable on this device.

## Safety decision

Do not enable key filtering in a user-facing build yet. First verify:
- whether the Realme firmware delivers HOME to an AccessibilityService requesting key filtering;
- whether another enabled service already owns key filtering;
- whether the physical gear key / OEM side panel is affected;
- whether a HOME event can be observed without consuming or duplicating navigation;
- whether a bounded recovery action is necessary after observing the event.

The first prototype should be diagnostic-only, with no startActivity(), no task manipulation, no role change, and no automatic focus reclaim. Only after logs prove event delivery and overlay suppression can recovery policy be considered.

## Sources

- Android AccessibilityServiceInfo API: FLAG_REQUEST_FILTER_KEY_EVENTS and canRequestFilterKeyEvents requirement.
- AndroidX AccessibilityServiceInfoCompat API: only one service can filter key events at a time.
- Android Activity API: window focus is independent from lifecycle; system windows can temporarily take focus.
- Android RoleManager API: ROLE_HOME.
- Monet Play Store listing: optional accessibility service observes Home key presses and launcher window changes.
- Monet GitHub distribution repository: source code not included.
