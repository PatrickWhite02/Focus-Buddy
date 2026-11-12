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
2. Tap **Choose what to block** and switch on every app (social media, YouTube, web browsers...) you want blocked. To block a website, tap **Add website** and type its address (like `youtube.com`); it's added to the same list with a toggle, and you can hold a website's row to remove it. You have to pick at least one app or website before you can start a session, and the list can't be changed while a session is running.
3. Choose how long the session lasts, either way: tap the time next to **Block until** and pick a time of day (it ends the next time the clock reaches it), or tap **Block for a number of hours** and enter a length in hours and minutes (up to 24 hours). With hours, the clock starts when you confirm **Start focus session**, not when you type the number. Whichever you picked last is the one used.
4. Tap **Start focus session**. Or, instead of picking a time, tap **Block until QR scan**: it asks you to confirm, then blocks with no timer until you scan the unlock QR code.

Focus Buddy will block the apps you chose while the session is active. Opening one shows a message and sends you to the home screen.

### Blocked websites

Websites are only checked in web browsers. Focus Buddy reads the address bar of the browser in front of you, and only the address bar. If it shows a blocked site (or one of its subdomains), you get 10 seconds to leave: a banner at the bottom of the screen counts down, and if you're still on the site when it runs out, you're sent to the home screen. Leaving the site or the browser before then cancels it.

It recognizes the address bars of Chrome, Brave, Edge, Firefox, Samsung Internet, Opera and DuckDuckGo (and browsers built on them). A browser that isn't recognized won't have its websites blocked; to add one, put its address bar's view id in `Config.URL_BAR_ID_NAMES`.

### Scheduled sessions

For sessions that start by themselves, tap **Scheduled sessions**. Each day of the week has a **+**: tap it, pick a start time and an end time, and that window shows up under the day (an end time earlier than the start runs past midnight). Add as many windows to a day as you like, and tap the trash can next to one to remove it.

Whenever the clock is inside a window, a session is running, with the same blocking and the same locked settings as a manual session, and nothing has to be opened or started. You get a notification 10 minutes, 5 minutes and 1 minute before a window opens, and another when it does. For these, Focus Buddy asks for permission to send notifications and, on Android 12 and later, to set exact alarms; without the latter, Android may deliver the notifications a few minutes late. While a scheduled window is running, the main screen shows "Blocked until 12:00 PM" where a manual session would show its countdown. Like the app list, the schedule can't be edited while a session is running.

### Ending a Session Early

Want to end your session before the timer expires?

Scan the QR code using either Focus Buddy's built-in camera or your phone's default camera. This also ends a scheduled window that's currently open; later windows still start on time.

## How Focus Buddy Prevents Distractions

Focus Buddy does more than just close social media apps.

While a blocking session is active, it also prevents you from opening Focus Buddy's pages in Android Settings (App info, Accessibility and Device admin). This is intended to make it harder to force-stop the app and disable the blocking service.

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
