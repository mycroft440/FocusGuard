# FocusGuard - Device Owner Mode Setup Guide

## Overview

**Device Owner Mode** is an advanced Android feature that grants administrative control over the device. When FocusGuard is set as the Device Owner, it can:

- Block applications with system-level enforcement
- Restrict device settings
- Monitor and control device policies
- Provide more robust app blocking compared to standard Device Admin mode

## Prerequisites

Before setting up Device Owner Mode, you need:

1. **Android Device** running Android 5.0 (API 21) or higher
2. **Android Debug Bridge (ADB)** installed on your computer
3. **USB Cable** to connect your device to the computer
4. **FocusGuard App** installed on your device
5. **USB Debugging** enabled on your device

## Step-by-Step Setup Instructions

### Step 1: Install Android Debug Bridge (ADB)

#### On Windows:
1. Download Android SDK Platform Tools from [Google's official site](https://developer.android.com/studio/releases/platform-tools)
2. Extract the downloaded ZIP file to a folder (e.g., `C:\adb`)
3. Open Command Prompt and navigate to the folder:
   ```
   cd C:\adb
   ```

#### On macOS:
1. Install via Homebrew:
   ```bash
   brew install android-platform-tools
   ```

#### On Linux:
1. Install via package manager:
   ```bash
   sudo apt-get install android-tools-adb
   ```

### Step 2: Enable USB Debugging on Your Device

1. Open **Settings** on your Android device
2. Go to **About Phone** (or **About Device**)
3. Find **Build Number** and tap it **7 times** to enable Developer Options
4. Go back to **Settings**
5. Open **Developer Options** (usually near the bottom)
6. Enable **USB Debugging**
7. A prompt will appear asking to allow USB debugging - tap **OK**

### Step 3: Connect Your Device via USB

1. Connect your Android device to your computer using a USB cable
2. On your device, a prompt may appear asking to allow USB debugging from this computer - tap **Allow**
3. Verify the connection by opening a terminal/command prompt and running:
   ```
   adb devices
   ```
   You should see your device listed with status "device"

### Step 4: Install FocusGuard

1. If not already installed, install FocusGuard on your device
2. Open FocusGuard and tap the **"Enable Device Owner Mode"** button
3. Grant **Device Admin** permissions when prompted
4. The app will show that Device Admin is now active

### Step 5: Set FocusGuard as Device Owner

1. Open a terminal/command prompt on your computer
2. Navigate to the ADB folder (if not already there)
3. Run the following command:
   ```
   adb shell dpm set-device-owner com.focusguard/.admin.FocusGuardDeviceAdminReceiver
   ```
4. Wait for the command to complete. You should see a success message
5. If you see an error, see the **Troubleshooting** section below

### Step 6: Verify Device Owner Mode

1. Restart FocusGuard on your device
2. The **"Enable Device Owner Mode"** button should now show **"Device Owner: Active"**
3. You can now use all advanced blocking features

## Troubleshooting

### Error: "Not allowed to set the device owner"

**Cause:** Device Owner can only be set on a fresh device or after factory reset.

**Solution:**
- Factory reset your device (Settings > System > Reset > Erase all data)
- Repeat steps 1-5

### Error: "Device not found" when running `adb devices`

**Cause:** USB debugging is not enabled or the device is not properly connected.

**Solution:**
1. Verify USB Debugging is enabled on your device
2. Try a different USB cable
3. Try a different USB port on your computer
4. Restart ADB server:
   ```
   adb kill-server
   adb start-server
   ```

### Error: "Command not found" when running `adb`

**Cause:** ADB is not installed or not in your system PATH.

**Solution:**
1. Verify ADB is installed correctly
2. Use the full path to ADB executable:
   - Windows: `C:\adb\adb.exe shell dpm set-device-owner ...`
   - macOS/Linux: `/usr/local/bin/adb shell dpm set-device-owner ...`

### Device Owner Mode not showing as active

**Cause:** The command may not have executed successfully.

**Solution:**
1. Check that you ran the exact command: `adb shell dpm set-device-owner com.focusguard/.admin.FocusGuardDeviceAdminReceiver`
2. Ensure Device Admin is enabled first
3. Try factory resetting and repeating the process

## Features Enabled by Device Owner Mode

Once Device Owner Mode is active, FocusGuard can:

### 1. App Blocking
- Block applications at the system level
- Prevent users from uninstalling blocked apps
- Monitor app usage in real-time

### 2. Website Blocking
- Intercept URLs via AccessibilityService
- Prevent access to blocked websites
- Support for multiple browsers

### 3. Device Policies
- Lock the device
- Control device settings
- Manage device restrictions

### 4. Security Features
- Prevent disabling of FocusGuard
- Enforce blocking policies system-wide
- Monitor device compliance

## Removing Device Owner Mode

If you need to remove Device Owner Mode:

1. Connect your device to a computer with ADB
2. Run the following command:
   ```
   adb shell dpm remove-active-admin com.focusguard/.admin.FocusGuardDeviceAdminReceiver
   ```
3. Optionally, uninstall FocusGuard from your device

## Security Considerations

⚠️ **Important:** Device Owner Mode grants significant control over your device. Only use FocusGuard as Device Owner if you trust the application.

- Device Owner Mode can only be removed by:
  - Running the `adb shell dpm remove-active-admin` command
  - Factory resetting the device
  - Using Google Find My Mobile (if enabled)

- The Device Owner app cannot be uninstalled without first removing Device Owner status

## Additional Resources

- [Android Device Policy Manager Documentation](https://developer.android.com/reference/android/app/admin/DevicePolicyManager)
- [Android Debug Bridge (ADB) Documentation](https://developer.android.com/studio/command-line/adb)
- [Android Device Owner Setup](https://source.android.com/docs/core/admin/device-owner-setup)

## Support

If you encounter issues during the setup process:

1. Check the **Troubleshooting** section above
2. Review the error message carefully
3. Try the steps again from the beginning
4. Contact FocusGuard support for additional help

---

**Last Updated:** March 2026
**FocusGuard Version:** 1.0.0
