# Controller to Touch (native Android app)

A native Android app — WebView + real controller input read via Android's
own `InputDevice`/`MotionEvent`/`KeyEvent` APIs — that injects genuine
`android.view.MotionEvent`s directly into the WebView via
`dispatchTouchEvent()`. This exists as an alternative to the browser
extension for one specific reason: **Chromium (both Chrome and Edge for
Android) reserves certain gamepad shoulder buttons for its own
tab/navigation gestures**, at a layer above any web page's JavaScript, so
no browser extension can ever see or use those buttons. This app has no
browser chrome above the WebView at all — no tabs, no back/forward gesture
surface — so there's nothing to reserve those buttons for, and the
distinction that caused the extension's L1/L2 problem doesn't apply here.

## How this differs from the browser extension

| | Extension | This app |
|---|---|---|
| Runs inside | Chrome/Edge (existing browser) | Its own standalone app |
| Touch mechanism | `document.dispatchEvent(new TouchEvent(...))` in page JS | `webView.dispatchTouchEvent(MotionEvent)` from native code |
| Controller reading | Web `Gamepad API` (`navigator.getGamepads()`) | Android's native `onKeyDown`/`onGenericMotionEvent` |
| Shoulder-button reservation | Yes — inherited from Chromium's browser-chrome layer | No — there is no browser chrome to reserve for |
| `isTrusted` on resulting DOM events | Always `false` | Implementation detail of WebView/Chromium, not guaranteed either way, but architecturally closer to genuine input than page-JS-synthesized events |

## Building this without installing Android Studio

This repo includes a GitHub Actions workflow (`.github/workflows/build.yml`)
that builds a debug `.apk` entirely in the cloud, using GitHub's own
runners (which come with a JDK and, via the `android-actions/setup-android`
step, the Android SDK preinstalled). You do not need Android Studio, a
local Android SDK, or a local Gradle install.

### Steps

1. **Create a free GitHub account** if you don't have one already
   (github.com).
2. **Create a new repository** (can be private) and push this project's
   files into it. Easiest way if you're not familiar with git:
   - On github.com, click **New repository**, name it anything (e.g.
     `controller-to-touch-app`), leave it empty (no README/license), and
     create it.
   - On the new repo's page, use **"uploading an existing file"** (a link
     shown on the empty repo's page) and drag in this whole project
     folder's contents, preserving the folder structure. GitHub's web
     uploader supports dragging a full folder tree in modern browsers.
3. Once the files are pushed to the `main` branch, go to the repo's
   **Actions** tab. The `Build APK` workflow should start automatically
   (it triggers on every push to `main`). If it doesn't start, click
   **Build APK** in the left sidebar, then **Run workflow** to trigger it
   manually.
4. Wait for the run to finish (a few minutes — it's compiling a full
   Android project from scratch, including downloading the Android SDK
   components it needs).
5. Click into the completed run, scroll to **Artifacts**, and download
   **controller-to-touch-debug-apk**. This is a `.zip` containing
   `app-debug.apk` — extract it.
6. Transfer `app-debug.apk` to your Android device (same as the `.crx`
   before — email, Drive, USB, whatever's easiest) and tap it to install.
   You'll need to allow "install from unknown sources" for whichever app
   you use to open it, same as any sideloaded APK.

### If the build fails

I was not able to run an actual Android/Gradle build in the environment
that wrote this code — only structural checks (brace balance, XML
well-formedness, resource-ID cross-referencing). The GitHub Actions build
is the **first real compile** this code goes through. If it fails, copy
the error from the Actions log and we'll fix it — this is a normal part
of getting a first Android build working, not a sign anything is
fundamentally wrong with the approach.

## Using the app

1. Open the app, type a game's URL into the address bar, tap **Go**.
2. Tap the **⚙** button to open the mapping panel.
3. **+ Button**: adds a new button mapping. Press any controller
   button to bind it, then tap **Set pos** and tap where on the page
   (behind the dialog) it should touch.
4. **+ Stick zone**: adds a stick zone. Tap **Place** to set its center
   (first tap) and radius (second tap, drag-distance from center). Tap
   **Bind toggle** and press a button to control when it's active; tap
   **Mode: Hold/Latch** to switch between "active only while held" and
   "press once to start, press again to stop."
5. Tap **Done** to close the panel and start playing.

Mappings are saved per-hostname (same design as the browser extension),
so different games keep independent layouts automatically.

## Known limitations

- **Single/limited simultaneous multi-touch**: `TouchInjector` properly
  supports multiple simultaneous pointers (a stick held while a button is
  also pressed), built as one combined `MotionEvent` per Android's real
  multi-touch model. It has not been stress-tested against games needing
  many more than two or three simultaneous touch points.
- **`isTrusted` is not guaranteed `true`** just because this uses native
  `MotionEvent` injection instead of page JS — see the table above. If a
  game specifically gates on `isTrusted`, this app is a strong candidate
  to work where the extension wouldn't, but it isn't a proven guarantee
  without testing against that specific game.
- **This is a debug build** (`assembleDebug`), signed with a default debug
  key, suitable for sideloading and personal use but not for Play Store
  distribution as-is.
