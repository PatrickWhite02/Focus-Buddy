# Focus Buddy

Focus Buddy is an Android productivity app that blocks access to social media apps for a set period of time. The goal is to make it harder to get distracted when you need to focus.

## Installation

When installing the app, Android will display a warning because the app isn't recognized by Google Play Protect. You will need to tap **Install anyway** to proceed. Alternatively, make a local build iff you don't trust me :) it should plug into Android Studio without fuss

## Setup

Focus Buddy requires a couple of permissions to do its job. Unfortunately, Android makes setting up accessibility permissions unnecessarily complicated.

### 1. Enable Accessibility Permissions

The app includes a tutorial to guide you through this process. If anything is confusing, let me know.

1. Open Focus Buddy and tap the accessibility setup button. When prompted, open Accessibility Settings.
2. Select **Installed apps**, then select **Focus Buddy**.
3. Android may refuse to let you enable the accessibility service because the app was installed from an unrecognized source. This is expected.
4. Return to Focus Buddy and tap the accessibility setup button again.
5. This time, select **Open apps list**.
6. Find **Focus Buddy**, tap the three-dot menu, and select **Allow restricted settings**.
7. Return to Accessibility Settings, open Focus Buddy, and enable its accessibility service. Confirm any warning Android displays.

That's it for accessibility permissions.

### 2. Enable Administrator Permissions

Return to Focus Buddy and activate the device administrator permission when prompted.

Once both permissions are enabled, you're ready to go.

## Using Focus Buddy

1. Open Focus Buddy.
2. Select how many hours you want your blocking session to last (decimals work).
3. Start the session.

Focus Buddy will block access to social media apps while the session is active. The current supported apps are:
1. Facebook/Lite
2. Youtube
3. Instagram

Adding more is easy, just edit Config.java

### Ending a Session Early

Want to end your session before the timer expires?

Scan the QR code using either Focus Buddy's built-in camera or your phone's default camera.

## How Focus Buddy Prevents Distractions

Focus Buddy does more than just close social media apps.

While a blocking session is active, it also prevents you from opening Focus Buddy's Android app settings. This is intended to make it harder to force-stop the app and disable the blocking service.

It also prevents you from uninstalling Focus Buddy while the service is running.

### Uninstalling Focus Buddy

Even when a blocking session isn't running, Android may display an **Uninstall failed** notification when you try to uninstall the app.

If this happens:

1. Tap the **Uninstall failed** notification.
2. Revoke Focus Buddy's device administrator privileges.
3. Try uninstalling the app again.

You should now be able to uninstall it normally.

## Feedback

This is a personal productivity project, and the permission setup in particular can be a pain. I did my best to make the in-app tutorial understandable, but if you run into problems or find something confusing, let me know.
