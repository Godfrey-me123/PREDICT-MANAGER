
# SCREEN EARNER — COMPLETE KOTLIN REPAIR AND UI IMPROVEMENT

## Objective

Repair the existing Screen Earner Android application using its actual Kotlin source code.

The application currently has these problems:

1. Android Accessibility Settings reports: **“This app isn't working correctly. Turn it off above, then turn it on again, to restart it.”**
2. Automation starts, but no visible Bet or Cash Out actions occur.
3. The automation detected the actual game balance once and attempted a bet. The game correctly rejected the stake because the available balance was insufficient. Subsequent attempts did not reliably repeat the action.
4. The automation does not reliably determine when to click Bet or Cash Out.
5. The floating Bet and Cash Out controls sometimes cover the game's own buttons.
6. The Home screen and Settings screens have poor contrast, unreadable text, clipping, and layout problems.
7. Stake and strategy settings may conflict or fail to update the running automation.
8. The application sometimes appears to start successfully but remains silent without explaining its state.

Do not simply redesign the interface. Repair the underlying state management, service lifecycle, gesture execution, game-state observation, and action reporting.

## General implementation rules

- Inspect the existing project before editing.
- Use Kotlin and the existing architecture.
- Do not replace the entire project or introduce a second automation engine.
- Preserve unrelated functionality.
- Keep the existing floating bubble and draggable Bet/Cash Out controls.
- Do not introduce hardcoded balances, stakes, game states, or fake success messages.
- Do not report a click as successful merely because `dispatchGesture()` returned `true`.
- Add logs and user-visible status for every meaningful automation step.
- Keep all user-configurable strategy parameters connected to the actual engine.
- Use safe defaults and validate settings before automation starts.
- Do not bypass a game's security restrictions or anti-automation protections. If an interaction is rejected or unsupported, report it and stop or wait according to the user's configured strategy.

---

# SECTION 1 — ACCESSIBILITY SERVICE HEALTH

### Files to inspect

- `app/src/main/java/com/example/service/AccessibilityController.kt`
- `app/src/main/java/com/example/service/ScreenEarnerAccessibilityService.kt`
- `app/src/main/res/xml/accessibility_service_config.xml`
- `app/src/main/AndroidManifest.xml`

### Required corrections

1. Investigate why Android marks the accessibility service as not working correctly.
2. Inspect every overridden lifecycle method:
   - `onServiceConnected()`
   - `onAccessibilityEvent()`
   - `onInterrupt()`
   - `onDestroy()`
3. Ensure the service initializes its dependencies safely.
4. Ensure an exception in event processing cannot permanently stop the automation service.
5. Add structured error logging with the exception and operation that failed.
6. Verify the manifest service declaration and accessibility metadata.
7. Verify that gesture capability and window-content access are declared correctly for the features that need them.
8. Do not repeatedly restart the service in a tight loop.
9. Ensure `onServiceConnected()` does not unexpectedly display overlays or start betting automatically.
10. Expose the real accessibility connection status to the Home screen and floating panel.

### Required status values

Use an explicit service state, such as:

- `DISCONNECTED`
- `CONNECTING`
- `CONNECTED`
- `ERROR`

Do not infer service connectivity from whether an overlay is visible.

---

# SECTION 2 — GESTURE DISPATCH AND CLICK CONFIRMATION

### File

`app/src/main/java/com/example/service/AccessibilityController.kt`

### Required corrections

Review and repair `dispatchTouchGesture()` and all related coordinate-click functions.

The implementation must:

1. Validate that coordinates are finite and inside the actual display bounds.
2. Account for display rotation and window/display coordinate differences.
3. Avoid using stale coordinates after screen rotation, window resizing, or game layout changes.
4. Prevent multiple gestures from being dispatched concurrently.
5. Queue or sequence gestures so that the next action waits for the previous gesture callback.
6. Handle all three outcomes:
   - Gesture accepted for dispatch.
   - Gesture completed.
   - Gesture cancelled or rejected.
7. Report the difference between:
   - Android accepted the gesture.
   - Android completed the gesture.
   - The target application visibly changed state.
8. Always restore overlay touch behavior after the gesture completes, fails, or times out.
9. Ensure the watchdog cannot restore the overlay too early and interfere with a gesture that is still in progress.
10. Cancel pending gestures when the user presses Stop or Emergency Stop.
11. Ensure no main-thread blocking operations are used for gesture timing.
12. Add a diagnostic mode that can test a tap on a user-selected coordinate without starting the betting strategy.

### Important

Android's `dispatchGesture()` callback confirms the gesture's dispatch result. It does not prove that a game accepted a bet or cash-out action.

Use observed game state or a verified result to determine whether the action succeeded.

Reference:
https://developer.android.com/reference/kotlin/android/accessibilityservice/AccessibilityService

---

# SECTION 3 — BET AND CASH OUT STATE DETECTION

### Files to inspect

- `app/src/main/java/com/example/service/ScreenEarnerAccessibilityService.kt`
- `app/src/main/java/com/example/service/AccessibilityController.kt`
- `app/src/main/java/com/example/data/engine/AutomationEngine.kt`
- Any existing UI detector, game-state model, or target-interaction class.

### Required corrections

The application must distinguish these states:

- Game not detected.
- Game detected.
- Bet button visible.
- Bet button enabled.
- Bet action dispatched.
- Bet action confirmed.
- Bet rejected.
- Round in progress.
- Cash Out button visible.
- Cash Out button enabled.
- Cash Out action dispatched.
- Cash Out confirmed.
- Round completed.
- Game state unknown.

Do not treat `BETTING_OPEN` as proof that a Bet button is visible and clickable.

Do not treat a detected Cash Out button as proof that cash-out has completed.

### Detection rules

1. Use accessibility node information when the game exposes meaningful nodes.
2. Check node visibility, bounds, enabled state, and clickability.
3. If the game uses a canvas or custom-rendered surface, recognize that accessibility nodes may not expose the button.
4. Use the existing supported visual detection method only if it is already available and appropriate.
5. If the button cannot be reliably detected, report `TARGET_NOT_FOUND`.
6. Re-evaluate the current game state before each action.
7. Do not reuse an old button coordinate after the game state changes.
8. Add detection confidence and the timestamp of the last successful observation.
9. Ensure that an old Bet target cannot be used as a Cash Out target.
10. Ensure that a Cash Out target cannot be clicked when no active bet is confirmed.

### Moving game state

The detector must continue observing while the game is in motion.

If the Bet or Cash Out control remains visible and enabled, the automation should be able to attempt the permitted interaction even while the aircraft animation is moving.

Do not wait for the animation to stop merely because the game is visually moving.

However:

- Do not assume that a moving animation means Bet is available.
- Do not assume that a visible control is enabled.
- Do not click a hidden or stale target.
- Do not repeatedly click an unchanged target without state confirmation.
- Do not bypass any restriction imposed by the game.

---

# SECTION 4 — AUTOMATION ENGINE MUST NOT REMAIN SILENT

### File

`app/src/main/java/com/example/data/engine/AutomationEngine.kt`

### Required corrections

Inspect the complete betting and cash-out execution flow.

The engine must have explicit states, for example:

- `IDLE`
- `STARTING`
- `WAITING_FOR_GAME`
- `WAITING_FOR_BET`
- `VALIDATING_STAKE`
- `DISPATCHING_BET`
- `VERIFYING_BET`
- `BET_ACTIVE`
- `WAITING_FOR_CASH_OUT`
- `DISPATCHING_CASH_OUT`
- `VERIFYING_CASH_OUT`
- `ROUND_COMPLETED`
- `PAUSED`
- `STOPPED`
- `ERROR`

Every transition must be observable.

### Bet sequence

The Bet flow must follow this order:

1. Confirm that automation is enabled.
2. Confirm that Emergency Stop is not active.
3. Confirm that the game is detected.
4. Confirm that the current game state permits betting.
5. Detect the current Bet target.
6. Confirm that the target is visible and enabled.
7. Read the current configured stake.
8. Read the latest verified balance.
9. Validate that the stake is positive and does not exceed the available balance.
10. Apply the stake through the existing interaction mechanism.
11. Verify that the stake field reflects the requested value.
12. Dispatch the Bet action.
13. Wait for the gesture callback.
14. Observe the game to confirm whether the bet was accepted.
15. Update the internal state only after confirmation.

If the stake is greater than the available balance, display a clear message such as:

`BET BLOCKED: Insufficient balance. Required: 400 TZS. Available: 8.11 TZS.`

Do not silently retry an invalid stake.

Do not deduct from the balance merely because the Bet gesture was dispatched.

### Cash Out sequence

The Cash Out flow must:

1. Confirm that a bet is active.
2. Confirm that the current game state allows cash-out.
3. Detect the current Cash Out target.
4. Verify that the target is visible and enabled.
5. Use the configured target multiplier or cash-out condition.
6. Dispatch the action.
7. Wait for the gesture result.
8. Observe whether the game confirms the cash-out.
9. Update the round state and balance only after confirmation.

Do not repeatedly cash out after a confirmed cash-out.

### Failure handling

Each failed step must produce a specific reason:

- `SERVICE_DISCONNECTED`
- `GAME_NOT_DETECTED`
- `TARGET_NOT_FOUND`
- `TARGET_DISABLED`
- `STAKE_UPDATE_FAILED`
- `INSUFFICIENT_BALANCE`
- `GESTURE_REJECTED`
- `GESTURE_CANCELLED`
- `ACTION_NOT_CONFIRMED`
- `STALE_TARGET`
- `EMERGENCY_STOP`
- `STRATEGY_INVALID`

Do not replace all failures with a generic “Waiting” status.

### Retry behavior

- Use bounded retries.
- Re-detect the target before retrying.
- Never retry an insufficient-balance action without a changed balance or stake.
- Never retry a confirmed action.
- Prevent duplicate bets within the same round.
- Ensure Stop and Emergency Stop cancel scheduled retries.
- Make the retry interval configurable where appropriate.

---

# SECTION 5 — STRATEGY AND STAKE SETTINGS

### Files to inspect

- `AutomationEngine.kt`
- Existing Strategy screen and ViewModel.
- Existing Settings screen and ViewModel.
- Risk settings and configuration repository.
- Any shared preference or database model holding strategy values.

### Required corrections

1. Identify why stake and strategy values conflict or fail to update.
2. Use one authoritative configuration model.
3. Ensure the Home screen, Settings screen, strategy engine, and floating overlay use the same configuration.
4. Remove duplicate state variables that can disagree.
5. Validate all numeric inputs.
6. Reject invalid values with clear validation messages.
7. Ensure changes made while automation is running are either safely applied or explicitly deferred until the next round.
8. Prevent a stale ViewModel value from overwriting a newer stored setting.
9. Persist settings correctly across process restarts.
10. Ensure the configured stake is the stake actually applied to the game.

### Risk controls

Verify that the following controls are enforced by the engine, not just displayed in the UI:

- Stop loss.
- Take profit.
- Maximum rounds.
- Maximum consecutive losses.
- Session limits.
- Strategy wait periods.
- Emergency Stop.

If a risk limit is reached, the engine must stop or pause according to the configured behavior and display the reason.

---

# SECTION 6 — OVERLAY TOUCH INTERFERENCE

### Files to inspect

- `app/src/main/java/com/example/service/ScreenEarnerOverlayService.kt`
- `app/src/main/java/com/example/service/FloatingOverlayService.kt`
- `app/src/main/java/com/example/service/AccessibilityController.kt`
- Any overlay window helper or drag controller.

### Required corrections

The screenshots show the floating Bet and Cash Out bubbles covering the game's own controls.

Correct this without removing the floating-control functionality.

1. Ensure overlay controls remain independently draggable.
2. Ensure the main menu can be opened and closed without blocking unrelated game controls.
3. Separate overlay touch handling from game-target touch handling.
4. Ensure overlays do not intercept touches when they should be pass-through.
5. Ensure the accessibility service and overlay service agree on the current touch mode.
6. Restore overlay touchability after every gesture.
7. Handle overlay removal and service shutdown safely.
8. Avoid creating duplicate overlay windows.
9. Ensure drag gestures are not mistaken for clicks.
10. Add a clear visual indication when a floating control is being repositioned.

### Overlay layout

- Keep the main Screen Earner bubble.
- Keep separate Bet and Cash Out task buttons.
- Keep each task button independently draggable.
- Prevent the buttons from covering the detected game target where practical.
- Do not automatically reposition the buttons in a way that changes the user's saved positions without permission.

Android 12 and later may impose restrictions on touches passing through overlays. Diagnose and report blocked or rejected interactions rather than assuming every synthetic touch reaches the game.

---

# SECTION 7 — HOME SCREEN UI REPAIR

### Inspect the existing Home screen and related Compose files.

The current Home screen has weak text contrast, awkward wrapping, and an unbalanced layout.

### Required design

Use a clean Material 3 layout.

1. Establish a consistent page background and surface hierarchy.
2. Use readable text colors in both light and dark themes.
3. Ensure labels remain visible against their backgrounds.
4. Correct text clipping and awkward line wrapping.
5. Use appropriate font sizes and weights.
6. Align section headings, status indicators, and values.
7. Use consistent spacing and corner radii.
8. Make the Emergency Stop control prominent but not excessively large.
9. Display accessibility status, overlay permission status, and automation status as distinct items.
10. Use a scrollable page layout so content does not overlap the bottom navigation.
11. Respect Android system insets.
12. Keep all existing working Home screen features.

### Required Home screen information

Display:

- Accessibility service connection status.
- Overlay permission status.
- Game detection status.
- Current detected balance.
- Configured stake.
- Current strategy.
- Automation state.
- Latest action result.
- Latest error or blocking reason.

Do not display a green “Connected” status when the service is disconnected or in an error state.

---

# SECTION 8 — SETTINGS AND STRATEGY UI REPAIR

### Inspect the existing Settings, Strategy, and Risk screens.

### Required corrections

1. Fix text contrast and visibility.
2. Fix clipping and overlapping controls.
3. Use consistent field labels and helper text.
4. Separate stake parameters from strategy parameters.
5. Separate strategy configuration from risk controls.
6. Use clear input validation.
7. Make the selected strategy visible.
8. Ensure Save and Reset buttons perform their actual operations.
9. Prevent one setting from accidentally resetting another setting.
10. Ensure all settings remain usable on small Android screens.
11. Use scrollable sections where required.
12. Do not create multiple competing sources of truth for the same configuration.

The interface must remain readable on the user's Samsung Note 20 5G and other supported Android screen sizes.

---

# SECTION 9 — AUTOMATION ACTIVITY LOG

### Files to inspect

- Existing Logs screen.
- Automation engine.
- Accessibility service.
- Overlay service.
- Existing notification implementation.

### Required corrections

Add an activity record for every meaningful step.

Examples:

- Automation started.
- Accessibility service connected.
- Game detected.
- Bet target detected.
- Stake validation started.
- Stake validation failed.
- Stake successfully applied.
- Bet gesture dispatched.
- Gesture completed.
- Bet confirmed.
- Bet rejected.
- Cash Out target detected.
- Cash Out dispatched.
- Cash Out confirmed.
- Retry scheduled.
- Retry cancelled.
- Emergency Stop activated.
- Automation stopped.

Each record should contain:

- Timestamp.
- Action.
- Current game state.
- Target type.
- Target coordinates when appropriate.
- Gesture result.
- Confirmation result.
- Failure reason when applicable.

Do not store unnecessary sensitive screen content.

Do not claim that a game action succeeded when only the gesture dispatch succeeded.

---

# SECTION 10 — NOTIFICATIONS AND STATUS FEEDBACK

### Required corrections

1. Make the automation status visible while running.
2. Show whether the app is waiting for a game, waiting for a target, executing an action, or verifying a result.
3. Show a meaningful error when the balance is insufficient.
4. Show a meaningful error when the accessibility service disconnects.
5. Show a meaningful error when a target cannot be detected.
6. Show when Emergency Stop is active.
7. Ensure notifications do not falsely report successful bets or cash-outs.
8. Ensure Stop cancels scheduled work and updates the status immediately.

Avoid excessive notifications for every repeated observation. Use the activity log for detailed diagnostic events.

---

# SECTION 11 — TESTING AND ACCEPTANCE CRITERIA

Do not mark the repair complete merely because the project compiles.

### Build checks

- Compile the Kotlin project.
- Resolve Kotlin compilation errors.
- Resolve manifest and accessibility XML errors.
- Verify that all changed imports and dependencies are correct.
- Do not introduce duplicate dependencies.
- Do not remove unrelated project files.

### Device checks

Test the following on a real Android device:

1. Accessibility service can be enabled and remains connected.
2. The service does not show the Android “isn't working correctly” warning.
3. Overlay permission can be granted.
4. Main bubble opens and closes correctly.
5. Bet and Cash Out task buttons can be dragged independently.
6. Overlay does not permanently block the game.
7. Game detection works after navigation to the supported game.
8. Bet target is detected while visible and enabled.
9. Bet action is not dispatched when the balance is insufficient.
10. Insufficient balance is reported clearly.
11. A valid permitted Bet action is dispatched and verified.
12. Cash Out is attempted only when the active game state allows it.
13. Automation does not remain silent after Start.
14. Stop cancels pending actions.
15. Emergency Stop prevents further actions.
16. Settings remain consistent after reopening the application.
17. Home and Settings remain readable on small screens.

### Final deliverable

Provide:

- The exact Kotlin files changed.
- The exact XML or manifest files changed.
- A brief explanation of each correction.
- Build results.
- Device-test results.
- Any remaining limitation.

Do not claim that device tests passed if they were not performed.

Preserve the existing project structure and unrelated features.
