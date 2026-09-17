# Implementation Plan - Floating Counter Stabilization and About Popup

This plan addresses the floating counter blinking issue during phone calls and replaces the external "About" website link with an in-app popup.

## User Review Required

> [!NOTE]
> The floating counter will now be hidden during active phone calls to ensure it doesn't interfere with call controls and to prevent the blinking issue reported.

## Proposed Changes

### Accessibility Service & Overlay Stabilization

#### [MODIFY] [ReelAccessibilityService.kt](file:///C:/Users/abhin/Downloads/ReelPulse/app/src/main/java/com/reelpulse/app/service/ReelAccessibilityService.kt)
- Add a `PhoneStateListener` (or `TelephonyCallback` for newer APIs) to detect active calls.
- Update `updateOverlayState` to hide the counter if a call is active.
- Refine the package switching logic to prevent rapid show/hide cycles when transient system events occur.

### UI Improvements

#### [MODIFY] [SettingsScreen.kt](file:///C:/Users/abhin/Downloads/ReelPulse/app/src/main/java/com/reelpulse/app/ui/SettingsScreen.kt)
- Add `showAboutDialog` state.
- Implement an `AlertDialog` for the "About" section containing app information, version, and mission.
- Update the "About" `SettingsItem` to trigger this dialog instead of opening a URL.

#### [MODIFY] [AndroidManifest.xml](file:///C:/Users/abhin/Downloads/ReelPulse/app/src/main/AndroidManifest.xml)
- Add `READ_PHONE_STATE` permission to support call detection.

## Verification Plan

### Automated Tests
- N/A (UI and Service behavior are best verified manually)

### Manual Verification
1. **Blinking Fix:** Start a reel, then initiate/receive a call. Verify the counter hides gracefully and doesn't blink.
2. **About Popup:** Navigate to Settings -> About. Verify the information popup appears correctly and no longer opens a browser.
