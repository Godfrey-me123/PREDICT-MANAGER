
# SECTION 13 — FIX SAVE & CLOSE: DO NOT EXIT THE APPLICATION

### Problem

Pressing **Save & Close** closes the entire Screen Earner application instead of closing only the current editor, settings panel, or dialog.

### Files to inspect

- All Settings screens and dialogs.
- Strategy editor screens and dialogs.
- Overlay menu and its action handlers.
- Navigation controller or navigation graph.
- Any composable containing `Save & Close`.
- `AutomationController.kt` and service lifecycle handlers where relevant.

### Required corrections

1. Find the exact `onClick` callback for every Save & Close button.
2. Identify any calls to:
   - `finish()`
   - `finishAndRemoveTask()`
   - `stopService()`
   - `stopSelf()`
   - `exitProcess()`
   - Navigation to the Home screen or application exit.
3. Remove application-exit behavior from Save & Close.
4. Save and validate the current settings first.
5. Close only the current dialog, editor, or settings panel.
6. Return to the previous screen or overlay menu.
7. Keep the floating overlay service running.
8. Keep the accessibility service connected.
9. Preserve the current automation state unless the edited setting requires a safe pause.
10. Display a success or error message after saving.
11. Prevent duplicate saves caused by repeated button presses.

### Acceptance test

Open Settings → edit a value → Save & Close.

Expected result: the editor closes, the floating menu remains available, and Screen Earner continues running. Repeat this test for the Strategy editor and every other screen that uses Save & Close.

---

# SECTION 14 — COMPLETE GAME RIBBON HISTORY COLLECTION

### Objective

Screen Earner must collect and maintain the game's multiplier history, rather than relying only on the small number of multipliers currently visible in the game ribbon.

The application should maintain a rolling 30-minute history and display it in a readable history grid.

### Files to inspect

- `ScreenEarnerAccessibilityService.kt`
- `AccessibilityController.kt`
- Existing game detection and ribbon detection classes.
- Existing game history model and repository.
- `AutomationEngine.kt`
- Existing Logs and History screens.
- Any existing image or text recognition components.

### A. Read the game history

1. Detect the game's multiplier ribbon.
2. Identify the history area and its available accessibility nodes.
3. Read every accessible multiplier and its corresponding game timestamp.
4. Detect whether the game provides an expandable history control, history panel, or load-more action.
5. When available, allow the user to open the game's full history and collect the accessible records.
6. If the history is presented in a scrollable area, inspect its accessibility scroll actions and collect additional records as the user or an authorized collection workflow navigates through it.
7. Continue until the accessible history is exhausted or the configured 30-minute collection window is complete.
8. Do not assume that the first visible ribbon contains the entire history.
9. Do not claim that all history has been collected when only the visible records were accessible.
10. Do not access hidden private endpoints or bypass the game's access restrictions.

### B. Support different ribbon layouts

The history viewer must support:

- 7 or 8 rows.
- 4 or 5 columns.
- Responsive layouts on different screen sizes.
- A scrollable history view.
- A rolling 30-minute time window.

The grid should be configurable or responsive, not hardcoded to one screen size.

### C. Record structure

Create or update a persistent model containing:

- Game identifier.
- Round identifier, if available.
- Multiplier value.
- Game-provided timestamp, if available.
- Observation timestamp.
- Timestamp source.
- Collection source.
- Collection status.
- Duplicate detection key.

Use the timestamp supplied by the game whenever it is available.

If the game does not expose a timestamp, record the local observation time and mark it as an observation timestamp. Never invent a game timestamp.

### D. Prevent duplicates and missing records

1. Deduplicate using a reliable round identifier where available.
2. Otherwise use a carefully designed combination of game identity, multiplier, and timestamp.
3. Do not identify records by multiplier alone because the same multiplier can occur repeatedly.
4. Detect repeated snapshots of the same ribbon.
5. Preserve records when the screen refreshes.
6. Avoid overwriting previous rounds with newer records.
7. Record gaps in collection.
8. Record when history is incomplete or unavailable.
9. Maintain records in local persistent storage.
10. Use efficient database queries for the latest 30 minutes.

### E. History display

Add a dedicated Game Ribbon History section.

Display:

- Total records collected.
- Collection period.
- Latest multiplier.
- Latest game timestamp.
- Last collection time.
- Number of records available.
- Whether the history is complete or partial.

Provide a grid with 7–8 rows and 4–5 columns where screen space permits.

Use clear multiplier formatting and distinguish values through readable visual styling.

Provide a refresh or collection status indicator.

Do not mix historical game results with Screen Earner's own action history.

### F. Collection status

Use explicit states:

- `HISTORY_NOT_STARTED`
- `COLLECTING_VISIBLE_HISTORY`
- `EXPANDING_HISTORY`
- `SCROLLING_HISTORY`
- `HISTORY_PARTIAL`
- `HISTORY_COMPLETE`
- `HISTORY_UNAVAILABLE`
- `HISTORY_ERROR`

Display the exact reason if the game does not expose additional records.

---

# SECTION 15 — USER-DEFINED PATTERN DETECTION AND STRATEGY RULES

### Objective

Allow users to create strategies based on observed game history.

Example:

- After 8 consecutive rounds below a selected multiplier threshold, notify the user.
- Notify when a particular sequence appears.
- Notify when a configured waiting period has elapsed.
- Notify when a selected multiplier condition is reached.
- Notify when several conditions occur together.

These are rule-based observations, not guaranteed predictions.

### Files to inspect

- `AutomationEngine.kt`
- Existing Strategy screen and ViewModel.
- Existing Strategy configuration model.
- Game history model and repository.
- Risk settings.
- Notification manager and activity log.

### A. Strategy builder

Allow users to create, edit, enable, disable, and delete their own strategies.

A strategy must contain:

- Strategy name.
- Enabled status.
- Conditions.
- Multiplier thresholds.
- Number of consecutive rounds.
- Optional time window.
- Optional waiting period.
- Notification level.
- Notification message.
- Repeat-notification policy.

Support combinations of conditions using explicit AND/OR logic.

Example rule:

`WHEN 8 consecutive rounds are below 2.00x, NOTIFY USER.`

Another example:

`WHEN the selected multiplier sequence appears within 30 minutes, NOTIFY USER.`

Do not hardcode the example strategies into the application.

### B. Pattern engine

1. Evaluate patterns against stored game history.
2. Process each newly recorded round once.
3. Preserve chronological order.
4. Avoid counting the same round more than once.
5. Evaluate consecutive-round conditions correctly.
6. Reset a consecutive sequence when its condition is broken.
7. Support rolling time windows.
8. Support multiple enabled strategies simultaneously.
9. Keep strategies independent.
10. Store the last evaluated record for each strategy.
11. Store the last notification time for each strategy.
12. Prevent duplicate notifications for the same pattern occurrence.
13. Re-evaluate when new game records arrive.
14. Do not treat missing history as a valid pattern.
15. Do not generate predictions from incomplete or stale data without clearly reporting that limitation.

### C. Pattern interpretation

Use neutral language.

For example:

`PATTERN DETECTED: 8 consecutive rounds below 2.00x.`

Do not state:

`NEXT ROUND WILL BE HIGH.`

Past multiplier sequences do not establish that the next round is more likely to reach a particular multiplier.

The application should identify the user's configured conditions, not promise an outcome.

---

# SECTION 16 — NOTIFICATION SYSTEM: THE CENTRAL STRATEGY FEATURE

### Objective

Notifications are the primary way Screen Earner communicates that a user's strategy condition is approaching, has been reached, or has been invalidated.

The application must not require the user to keep the Screen Earner dashboard open to receive strategy alerts.

### Files to inspect

- Existing notification manager.
- `AndroidManifest.xml`
- `AutomationEngine.kt`
- Game history repository.
- Strategy repository.
- Existing foreground service.
- Home screen and Logs screen.

### A. Notification levels

Implement at least these levels:

1. `STRATEGY_APPROACHING`
   - A configured condition is close to being satisfied.
   - Example: 7 of 8 consecutive rounds meet the selected condition.

2. `STRATEGY_REACHED`
   - The complete configured condition has been satisfied.
   - Example: 8 of 8 consecutive rounds meet the selected condition.

3. `STRATEGY_INVALIDATED`
   - A condition that was approaching is no longer valid.

4. `HISTORY_UNAVAILABLE`
   - The game history cannot currently be read.

5. `STRATEGY_PAUSED`
   - The strategy has been paused because of a service failure, missing data, or a user action.

### B. Notification behavior

When a strategy reaches its configured condition:

- Display a clear Android notification.
- Include the strategy name.
- Include the observed pattern.
- Include the latest multiplier and timestamp where available.
- Include how many conditions were satisfied.
- Tell the user to open the game and review the situation.
- Provide an action to open Screen Earner's strategy or activity screen.

Example:

`SCREEN EARNER — STRATEGY REACHED`

`8 consecutive rounds below 2.00x detected.`

`Strategy: After 8 Losses`

`Open the game and review the current betting opportunity.`

Do not automatically place a bet from this notification.

### C. Approaching notifications

Example:

`STRATEGY APPROACHING`

`7 of 8 required rounds detected.`

`Strategy: After 8 Losses`

The user should be able to distinguish an approaching condition from a fully reached condition.

### D. Android notification implementation

1. Create appropriate notification channels.
2. Request notification permission on Android versions that require it.
3. Use unique notification identifiers for strategies and events.
4. Prevent duplicate alerts.
5. Support notification dismissal.
6. Support opening the relevant Screen Earner screen from a notification.
7. Ensure notifications can be received while the user is using Chrome.
8. Do not depend on the Activity remaining open.
9. Ensure notifications are not silently suppressed by an application-level setting.
10. Log notification permission and delivery failures.

### E. Notification controls

Add user settings for:

- Enable/disable strategy notifications.
- Approaching alerts.
- Reached alerts.
- Invalidated alerts.
- Notification sound.
- Vibration.
- Repeat interval.
- Quiet period.
- Per-strategy notification enablement.

A notification setting must control actual notification delivery, not merely its displayed switch.

### F. Notification log

Add a persistent Notification History screen.

Record:

- Notification ID.
- Strategy ID.
- Strategy name.
- Notification type.
- Message.
- Triggering game record.
- Multiplier.
- Timestamp.
- Delivery attempt status.
- User interaction status.

Display the log separately from game history and automation action history.

Do not mark a notification as delivered merely because the application created its notification object. Record actual delivery attempts and any detectable failures.

---

# SECTION 17 — NOTIFICATION-FIRST USER WORKFLOW

The expected workflow is:

1. User opens Screen Earner.
2. User selects or creates a strategy.
3. User enables the strategy.
4. User opens the supported game.
5. Screen Earner observes accessible game history.
6. New multipliers are stored with their timestamps.
7. The pattern engine evaluates newly collected records.
8. When a strategy is approaching, the user receives an approaching notification.
9. When the condition is reached, the user receives a reached notification.
10. The user opens the game and decides whether to take action.
11. Screen Earner continues recording new game results.
12. The strategy engine records whether the pattern continues or becomes invalidated.

The notification must be useful even when Screen Earner's floating menu is closed.

The notification is an alert to review the game, not a command to bet.

---

# SECTION 18 — TESTING AND ACCEPTANCE CRITERIA

### Save & Close

- Saving closes only the current editor.
- The main application remains open.
- The floating service remains active.
- Settings persist correctly.

### Ribbon history

- Read all currently visible multiplier records.
- Open the game's accessible history.
- Collect additional records through supported navigation or scrolling.
- Preserve timestamps.
- Avoid duplicate records.
- Display the latest 30-minute history.
- Show when the collection is partial or unavailable.

### Pattern detection

Test with a controlled sample history.

Example:

`1.22x, 1.55x, 1.31x, 1.80x, 1.10x, 1.45x, 1.72x, 1.90x`

For a rule requiring 8 consecutive rounds below 2.00x:

- The first seven records must trigger the approaching condition.
- The eighth record must trigger the reached condition.
- A duplicate copy of the eighth record must not trigger a second alert.
- A ninth record outside the condition must update the pattern state correctly.

### Notifications

- Test while Screen Earner Home is open.
- Test while the floating overlay is open.
- Test while Chrome is in the foreground.
- Test when the notification permission is denied.
- Test after the application process is restarted.
- Test duplicate-notification suppression.
- Test notification history persistence.

### Final delivery

List every changed Kotlin file, XML file, database model, repository, and UI component.

Report build results and actual device-test results separately.

Do not claim that full game history collection works unless it has been verified against the actual game interface.
