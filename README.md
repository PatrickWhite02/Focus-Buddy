# Focus Buddy

Focus Buddy is an Android productivity app that blocks access to social media apps for a set period of time. The goal is to make it harder to get distracted when you need to focus.

## Installation

When installing the app, Android will display a warning because the app isn't recognized by Google Play Protect. You will need to tap **Install anyway** to proceed. Alternatively, make a local build iff you don't trust me :) it should plug into Android Studio without fuss

## Setup

Focus Buddy needs the accessibility permission to do its job. Unfortunately, Android makes setting it up unnecessarily complicated.

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

### 2. Settings (first run)

Once accessibility is on, Focus Buddy opens its **Settings** screen before the main screen. Choose what to block here, and optionally switch on **Hardcore mode** (see below). Tap **Done** when you're finished. After that, the app opens straight to the main screen, and Settings is always available from the gear icon in its top right corner.

## Using Focus Buddy

1. Open Focus Buddy.
2. Tap the gear icon (top right), then **Choose what to block** and switch on every app (social media, YouTube, web browsers...) you want blocked. To block a website, tap **Add website** and type its address (like `youtube.com`); it's added to the same list with a toggle, and you can hold a website's row to remove it. If nothing is selected, a red warning shows at the top of the main screen and **Start focus session** tells you to set it up first. Settings can't be opened, and the list can't be changed, while a session is running.
3. Tap **Start focus session**. A screen opens where you choose how long the block lasts, either way: set a time of day on the clock (it ends the next time the clock reaches it), or type a length in hours and minutes (up to 24 hours). The two stay in sync, so changing one updates the other. Or tick **Block indefinitely** (this greys out the clock and boxes): the block has no timer and lasts until you scan the unlock QR code, so make sure you can get to it first. With hours, the clock starts when you press **Start**, not when you type the number.
4. Press **Start** on that screen (or **Cancel** to go back).

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

## Hardcore mode

Off by default. Switch it on in Settings (a warning explains what it does, then Android asks you to activate Focus Buddy as a device administrator). While it's on:

- Focus Buddy can't be uninstalled.
- During a blocking session, Focus Buddy's pages in Android Settings (App info, Accessibility and Device admin) are locked, which makes it harder to force-stop the app or turn off the blocking service.

With hardcore mode off, nothing stops you opening those Settings pages during a session, so a session can be ended by switching the accessibility service off. Hardcore mode exists to prevent that.

### Uninstalling Focus Buddy

Make sure no session is running, open Settings in Focus Buddy, and switch **Hardcore mode** off. Then uninstall it normally.

If you left hardcore mode on, Android may display an **Uninstall failed** notification. Tap it, revoke Focus Buddy's device administrator privileges, and try again.

## Feedback

This is a personal productivity project, and the permission setup in particular can be a pain. I did my best to make the in-app tutorial understandable, but if you run into problems or find something confusing, let me know.
