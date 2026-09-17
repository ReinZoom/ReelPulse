# Implementation Plan: History Overhaul & Precise Overlay Control

This plan addresses the two main requests:
1.  **7-Day History Overview**: Transforming the history tab from a raw event list to a structured daily summary for the past week.
2.  **Precise Overlay Visibility**: Ensuring the floating counter *only* appears when short-form content is actively being watched, and disappears immediately when leaving the app or the video player.

## Proposed Changes

### Data Layer

#### [MODIFY] [ReelRepository.kt](file:///C:/Users/abhin/Downloads/ReelPulse/app/src/main/java/com/reelpulse/app/data/ReelRepository.kt)
- Add a method to fetch a 7-day trend that includes app-specific breakdowns per day if needed, or stick to the current `last7DaysTrend` if it suffices. Actually, the user wants "overview of last 7 days history", which suggests seeing how much they rot each day.

### UI Components

#### [MODIFY] [MainActivity.kt](file:///C:/Users/abhin/Downloads/ReelPulse/app/src/main/java/com/reelpulse/app/ui/MainActivity.kt)
- **HistoryScreen**: Update to display a list of the last 7 days. Each item will show the date and the total count for that day.
- **HistoryItem**: Redesign to show daily summary statistics instead of individual "+1 Rot" events.

### Background Service

#### [MODIFY] [ReelAccessibilityService.kt](file:///C:/Users/abhin/Downloads/ReelPulse/app/src/main/java/com/reelpulse/app/service/ReelAccessibilityService.kt)
- **Package Whitelisting**: Add `com.android.systemui` to `launcherPackages` to ensure overlay hides when notification shade or recent apps are opened.
- **Active Detection Logic**: Update `onAccessibilityEvent` to check if the user is *currently* on a Reel/Short surface using the existing detectors.
- **Precise Hiding**:
    - If `isOnReel` is false, call `hideOverlay()` even if the app is still in the foreground.
    - This satisfies the "only show when short form content is being watched" requirement.
- **Efficiency**: Ensure `showOverlay()` is only called if not already visible, and `hideOverlay()` is only called if visible.

## Verification Plan

### Manual Verification
1.  **Overlay Behavior**:
    - Open Instagram. Verify counter does NOT show on Home feed.
    - Go to Reels tab. Verify counter appears.
    - Exit Instagram. Verify counter disappears immediately.
    - Pull down notification shade. Verify counter disappears.
2.  **History UI**:
    - Navigate to History tab.
    - Verify it shows "Today", "Yesterday", and previous days with their respective totals.
    - Ensure it no longer shows the long list of "+1 Rot" individual events.
