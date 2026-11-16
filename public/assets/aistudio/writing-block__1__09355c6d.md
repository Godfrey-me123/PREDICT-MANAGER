
# SECTION 12 — REPAIR AWAKE MODE (SCREEN AND CPU POWER MANAGEMENT)

### Problem

The Awake switch can be turned ON, but the phone still automatically turns off its display while Screen Earner is running over Chrome or BetPawa.

The current Awake control must be connected to actual Android power-management APIs. It must not merely change a Boolean value or update its button color.

### Files to inspect

- `app/src/main/java/com/example/service/ScreenEarnerOverlayService.kt`
- `app/src/main/java/com/example/service/FloatingOverlayService.kt`
- `app/src/main/java/com/example/service/ScreenEarnerAccessibilityService.kt`
- `app/src/main/java/com/example/service/AutomationController.kt`
- `app/src/main/AndroidManifest.xml`
- Existing Awake toggle, Settings screen, and preference repository.

### A. Keep the display awake

1. Implement screen-awake behavior using `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` on the appropriate visible Screen Earner overlay window.
2. When the user enables Awake, immediately apply the flag to the active overlay window.
3. When Awake is disabled, immediately remove the flag.
4. Keep the screen awake while the user has explicitly enabled Awake and the floating utility is active over another application.
5. Do not depend exclusively on the MainActivity window because the user minimizes Screen Earner and operates BetPawa in Chrome.
6. Ensure the overlay's window flags are updated when its layout parameters change.
7. Ensure reopening the floating menu does not accidentally remove the screen-on flag.
8. Ensure hiding or recreating the overlay does not leave Awake in a false ON state.
9. Verify the behavior on Samsung Android devices.

### B. Keep the processor awake when required

Use a separate, properly managed `PowerManager.PARTIAL_WAKE_LOCK` only when the automation requires continued CPU operation with the display off.

1. Add `android.permission.WAKE_LOCK` to the manifest.
2. Acquire the partial wake lock when the user enables the appropriate background-operation mode and the foreground automation service is active.
3. Release it immediately when automation stops, Awake/background mode is disabled, or the service is destroyed.
4. Prevent duplicate wake-lock acquisitions.
5. Use a bounded timeout as a safety measure.
6. Do not hold a partial wake lock indefinitely.
7. Do not confuse CPU-awake status with screen-awake status.

### C. Connect the toggle to real state

Create one authoritative Awake state.

The toggle must:

- Turn ON the actual screen-awake behavior.
- Turn OFF the actual screen-awake behavior.
- Persist the user's preference.
- Restore the correct state when the overlay service restarts.
- Display the real state of the applied power-management behavior.
- Show an error if Android prevents the requested behavior.

Do not display `Awake: ON` unless the required screen-on flag has actually been applied successfully.

### D. Lifecycle and safety

- Apply the screen-on flag when the overlay window is added.
- Reapply it when the overlay window is recreated.
- Remove it when Awake is disabled.
- Release CPU wake locks in all service shutdown paths.
- Handle exceptions and log failures.
- Do not turn the screen on after the user manually presses the Power button.
- Do not bypass the lock screen.
- Do not prevent the user from manually turning the screen off.
- Avoid unnecessary battery consumption.

### E. Add diagnostic information

In the floating utility, show:

- `AWAKE: ON — SCREEN HELD`
- `AWAKE: OFF`
- `CPU: ACTIVE` or `CPU: RELEASED`
- `AWAKE ERROR` if the requested behavior could not be applied.

Keep the display and CPU states separate.

### F. Acceptance tests

1. Enable Awake in Screen Earner.
2. Minimize Screen Earner and open Chrome.
3. Open BetPawa.
4. Leave the phone untouched beyond its normal screen timeout.
5. Verify that the display remains on while Awake is enabled.
6. Disable Awake and verify that the normal Android screen timeout works again.
7. Restart the overlay service and verify that the saved Awake preference is correctly restored.
8. Stop automation and verify that unnecessary CPU wake locks are released.
9. Test with the screen manually turned off to verify that the app does not falsely claim that the display is being held awake.

Do not mark Awake as fixed until the tests have been performed on a real device.
