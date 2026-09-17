# ReelPulse — a reel/shorts counter, built to be better than BrainPal

## How it works

Android does not give any app a "how many reels has the user watched" API.
The only way to get this signal without root access is:

1. Register an `AccessibilityService` scoped to specific packages
   (`accessibility_service_config.xml`).
2. Every time the foreground app fires a `TYPE_WINDOW_CONTENT_CHANGED` or
   `TYPE_VIEW_SCROLLED` event, inspect the current view hierarchy
   (`rootInActiveWindow`) for that app's reel-player container.
3. Compute a small "signature" that identifies *which* reel is currently on
   screen (author + position, or a video title, or just node bounds as a
   fallback) and only increment the counter when that signature changes.
4. Store each detected view as a row in a local Room database, so you can
   query "today", "this week", "by app", etc.

That's genuinely the whole trick — this is what's in
`ReelAccessibilityService.kt` and `AppDetectors.kt`.

## Why this implementation should undercount/overcount less than BrainPal

User reviews of BrainPal/BrainRot repeatedly mention count drift (e.g. "read
10 out of 9", inconsistent numbers). That happens when an app counts *every*
content-changed event instead of deduplicating by reel identity. The
signature-based dedup in `AppDetectors.kt` is specifically designed to fix
that class of bug. It's not perfect — Instagram/YouTube/Snapchat change
their internal view IDs on app updates, which is why `FallbackDetector`
exists as a graceful-degradation path instead of the count silently
dropping to zero.

**You will need to re-verify the resource IDs periodically** using
`adb shell uiautomator dump` while each target app is open on a test
device — this is ongoing maintenance, not a one-time setup, and is true of
every app in this category (it's why BrainPal ships frequent updates).

## Where to actually go beyond BrainPal

Ranked roughly by how much differentiation each buys you for the effort:

1. **User-added apps, not a hardcoded list.** BrainPal reviewers explicitly
   ask for more platforms. Let the user long-press any app in a picker and
   ReelPulse attempts the `FallbackDetector` heuristic on it automatically —
   turns "we support 4 apps" into "works on anything with a vertical video
   feed."
2. **Fix the group-challenge gap.** A top BrainPal review specifically asks
   for a shared leaderboard view for 3-4 friends instead of pairwise duels
   only. This is a backend + UI feature, not a detection problem — cheap
   win, direct response to a stated competitor weakness.
3. **Positive framing option.** BrainPal's whole aesthetic is a decaying
   brain — effective for some users, alienating for others. Offer a neutral
   "time you got back" framing (e.g. "≈42 minutes not spent scrolling
   today") as an alternative visual theme, selectable in onboarding.
4. **On-device weekly pattern insights**, not just a running total: which
   hour of day you scroll most, which app dominates, whether weekday vs.
   weekend differs. This is a `GROUP BY` query away (see `dailyCountsSince`
   in `ReelDao.kt`) but it's the difference between a counter and something
   that actually helps someone change a habit.
5. **True focus-mode blocking**, not just a cap notification: when a
   Pomodoro/focus session is active, intercept `TYPE_WINDOW_STATE_CHANGED`
   for the tracked packages and immediately send the user `home` via
   `performGlobalAction(GLOBAL_ACTION_HOME)`, rather than only counting.
6. **Minimal-permission mode.** BrainRot's own listing name-drops privacy as
   a selling point. Since `accessibility_service_config.xml` already scopes
   `packageNames` to an explicit list, lead with that in your Play Store
   listing: "we are architecturally unable to see anything outside the apps
   you choose," which is a stronger, more verifiable claim than a privacy
   policy paragraph.
7. **Export/CSV + no-account-required sync via a file the user controls**
   (e.g. export to Drive) instead of requiring a backend account for
   cross-device continuity.

## What's not in this scaffold yet (next steps)

- Onboarding flow that walks the user to Settings → Accessibility → ReelPulse
  and confirms the toggle turned on (Android gives no callback for this, so
  you poll `AccessibilityManager.isEnabled()` on `onResume`).
- Daily limit + notification/overlay when exceeded (`LimitEnforcer`,
  referenced as a comment in `ReelAccessibilityService.onNewReelDetected`).
- The friend-duel / group leaderboard backend (would need a lightweight
  server — Firebase or a small REST API — since it's the one feature that
  can't be purely on-device).
- Actual resource-id verification against current Instagram/YouTube/
  Snapchat/TikTok builds via `uiautomator dump` on a real device — the IDs
  in `AppDetectors.kt` are a realistic starting point, not guaranteed
  current, and this file is worth re-checking any time detection seems off.

## Running it

Standard Android Studio project layout. Open the `ReelPulse/` folder as a
project, let Gradle sync, run on a device (accessibility services generally
don't behave reliably on emulators — test on real hardware). After install,
go to Settings → Accessibility → ReelPulse and enable it, or tap the button
on the home screen to jump straight there.
