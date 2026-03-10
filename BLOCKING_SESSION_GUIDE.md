# FocusGuard - Blocking Session Guide

## Overview

**Blocking Session** is a powerful feature that allows you to lock yourself into a blocking mode for a specified number of days. Once activated, apps and websites cannot be unblocked until the countdown ends.

## Key Features

- **Day-Based Countdown**: Set blocking duration from 1 to 30 days
- **Irreversible Blocking**: Cannot unblock apps/websites before countdown ends
- **Device Owner Independence**: Blocking continues even after renouncing Device Owner Mode
- **Real-Time Status**: View remaining time and session details
- **Renounce Option**: Remove Device Owner Mode while keeping blocking active

## How to Use

### Step 1: Add Apps and Websites to Block

Before starting a blocking session, you must have at least one app or website in your blocked list:

1. Open FocusGuard
2. Go to **Blocked Apps** tab and add apps you want to block
3. Go to **Blocked Websites** tab and add websites you want to block

### Step 2: Start a Blocking Session

1. Tap the **"Start Blocking"** button on the main screen
2. Use the slider to select the duration (1-30 days)
3. Review the warning message carefully
4. Tap **"Start Blocking"** to confirm

⚠️ **Important**: Once you start the blocking session, you **CANNOT** unblock apps or websites until the countdown ends.

### Step 3: Monitor Your Session

1. Tap the **"Blocking Status"** button to view:
   - Current blocking status
   - Remaining time (days, hours, minutes)
   - Number of blocked apps and websites
   - Session start and end times

2. The remaining time updates in real-time every second

### Step 4: Renounce Device Owner Mode (Optional)

If you want to remove Device Owner Mode while keeping blocking active:

1. Open **"Blocking Status"** screen
2. Tap **"Renounce Device Owner"** button
3. Confirm the warning dialog

**Important**: 
- Device Owner Mode will be removed
- **BUT** the blocking will continue until the countdown ends
- You still cannot unblock apps/websites before the time expires

## Understanding the Blocking Mechanism

### How Blocking Works

FocusGuard uses two complementary methods to block apps and websites:

#### 1. AccessibilityService (Primary Method)
- Monitors all app launches and website access
- Immediately redirects blocked apps to home screen
- Closes blocked websites by pressing back button
- Works independently of Device Owner Mode

#### 2. Device Owner Mode (Optional Enhancement)
- Provides system-level enforcement
- Prevents uninstallation of blocked apps
- Adds additional security layer
- Can be renounced while blocking continues

### Why Blocking Continues After Renouncing Device Owner

The blocking mechanism is **independent** from Device Owner Mode:

1. **AccessibilityService** is the core blocking engine
2. **Device Owner Mode** only adds extra security
3. Even without Device Owner, AccessibilityService continues to block
4. The blocking session data is stored in the database
5. The app checks the session status on every app/website access

## Blocking Session Details

### Session Information

When you view the blocking status, you'll see:

```
Duration: X days
Blocked Apps: Y
Blocked Websites: Z
Remaining Time: X days Y hours Z minutes
Started: DD/MM/YYYY HH:MM
Ends: DD/MM/YYYY HH:MM
```

### Automatic Cleanup

- When the countdown ends, the blocking session automatically becomes inactive
- Apps and websites are no longer blocked
- You can then modify your blocked list or start a new session

## Important Warnings

⚠️ **Before Starting a Blocking Session, Understand:**

1. **Irreversible**: You cannot unblock apps/websites before the countdown ends
2. **No Exceptions**: The blocking applies 24/7 for the entire duration
3. **Device Restart**: Blocking persists even after restarting the device
4. **App Uninstall**: You cannot uninstall FocusGuard while blocking is active (if Device Owner is enabled)
5. **No Workarounds**: Disabling Accessibility Service will stop blocking but requires re-enabling it

## Troubleshooting

### Blocking Not Working

**Problem**: Apps or websites are not being blocked

**Solution**:
1. Ensure Accessibility Service is enabled (Settings → Accessibility → FocusGuard)
2. Verify apps/websites are in the blocked list
3. Check that a blocking session is active (Blocking Status screen)
4. Restart the app

### Cannot Start Blocking Session

**Problem**: "Start Blocking" button is disabled or shows error

**Solution**:
1. Add at least one app or website to the blocked list first
2. Ensure Accessibility Service is enabled
3. Check that no other blocking session is active

### Remaining Time Not Updating

**Problem**: The remaining time shows the same value

**Solution**:
1. Close and reopen the Blocking Status screen
2. Restart the app
3. Check device date and time settings

### Device Owner Renounce Failed

**Problem**: Cannot renounce Device Owner Mode

**Solution**:
1. Use ADB command: `adb shell dpm remove-active-admin com.focusguard/.admin.FocusGuardDeviceAdminReceiver`
2. Blocking will continue independently
3. Restart FocusGuard app

## Best Practices

### Setting Realistic Durations

- Start with shorter durations (3-7 days) to test the system
- Gradually increase duration as you build confidence
- Consider your schedule and commitments

### Combining with Other Tools

- Use FocusGuard alongside other productivity tools
- Set specific blocking times for focused work
- Use multiple sessions for different goals

### Monitoring Progress

- Check the Blocking Status regularly
- Note which apps/websites you miss most
- Adjust your blocked list for future sessions

## FAQ

**Q: Can I extend the blocking duration once started?**
A: No, the duration is fixed once the session starts. You must wait until it ends.

**Q: What happens if I uninstall FocusGuard?**
A: If Device Owner Mode is active, you cannot uninstall it. If not, uninstalling will stop blocking.

**Q: Can I have multiple blocking sessions?**
A: No, only one active session at a time. You must wait for the current session to end.

**Q: Does blocking work if the device is offline?**
A: Yes, blocking is entirely local and doesn't require internet connection.

**Q: Can I disable Accessibility Service to stop blocking?**
A: Technically yes, but you would need to re-enable it to continue using FocusGuard.

**Q: What if I factory reset my device?**
A: The blocking session data is lost, but you can start a new session afterward.

## Support

If you encounter issues with blocking sessions:

1. Check this guide for solutions
2. Review the Accessibility Service settings
3. Ensure the app has necessary permissions
4. Contact FocusGuard support for additional help

---

**Last Updated:** March 2026
**FocusGuard Version:** 1.0.0
