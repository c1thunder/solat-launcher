# Debloat your Google TV

Optional, and separate from Solat Launcher. These steps remove preinstalled apps you don't use, so the TV has more free memory and fewer things running in the background. They were done on a Sharp 4T-C55FJ1X (Google TV, Android 14), but most package names are the same on any Google TV.

## Before you start

- You need ADB set up as in the [Install](../README.md#install) section (`adb connect <TV-IP>:5555`).
- `pm uninstall -k --user 0` **hides** an app for your user. It doesn't touch the system partition, so it can't brick the TV.
- **Everything here can be undone** with one command (see [Undo](#undo)), and a factory reset restores everything.
- Only remove apps that are on your TV. If a package isn't there, the command just prints `Failure [not installed for 0]`, which is harmless.

See what's installed:

```sh
adb shell pm list packages | sort
```

## 1. Safe to remove (recommended)

Tracking, telemetry and demo apps nobody needs.

| Package | What it is |
|---|---|
| `tv.anoki.acr.anokiacroptin` | Anoki ACR: *automatic content recognition*. It identifies what's on screen, including from HDMI devices, for ad targeting. Removing it is a privacy win. |
| `fusion.android.tv.demo` | Shop demo mode |
| `com.google.android.feedback` | Google feedback reporter |
| `com.android.tv.feedbackconsent` | Feedback consent screen |
| `com.google.android.play.games` | Play Games |

```sh
for p in tv.anoki.acr.anokiacroptin fusion.android.tv.demo com.google.android.feedback com.android.tv.feedbackconsent com.google.android.play.games; do
  adb shell pm uninstall -k --user 0 $p
done
```

On Windows PowerShell:

```powershell
foreach ($p in "tv.anoki.acr.anokiacroptin","fusion.android.tv.demo","com.google.android.feedback","com.android.tv.feedbackconsent","com.google.android.play.games") {
  adb shell pm uninstall -k --user 0 $p
}
```

## 2. Remove if you don't use them

Your choice; these are normal apps that some people use.

| Package | App | Know before removing |
|---|---|---|
| `com.amazon.amazonvideo.livingroom` | Prime Video | |
| `com.google.android.youtube.tvmusic` | YouTube Music | |
| `com.google.android.youtube.tv` | YouTube | Only if you use another YouTube app. **Casting from the YouTube phone app stops working.** |
| `com.google.android.videos` | Google TV / Play Movies | |
| `com.google.android.tv.assistant` | Google Assistant | Basic voice search (`com.google.android.katniss`) still works. |
| `com.google.android.apps.tv.dreamx` | Google ambient screensaver | The basic Android screensaver stays. |
| `com.netflix.ninja` | Netflix | |

```sh
adb shell pm uninstall -k --user 0 <package>
```

## 3. Do NOT remove

These keep the TV working. Removing them can break live TV, HDMI, the remote, casting or setup.

| Package | Why it stays |
|---|---|
| `com.mediatek.wwtv.*`, `com.mediatek.tvinput` | Live TV, tuner and HDMI inputs |
| `com.android.tv.settings` | TV Settings |
| `com.android.vending` | Play Store (app updates) |
| `com.google.android.gms`, `com.google.android.gsf` | Google Play Services |
| `com.google.android.apps.mediashell` | Chromecast built-in |
| `com.google.android.katniss` | Voice search |
| Anything with `remote`, `inputmethod`, `keyboard` or `bluetooth` in the name | Remote control, Bluetooth and on-screen keyboard |
| `com.google.android.tungsten.setupwraith` | Setup. If the home screen is missing, the TV falls back to this. |
| `com.google.android.apps.tv.launcherx` | The stock Google TV home screen. Solat Launcher needs it **installed and enabled**; it just covers it up. |

## 4. Small speed tweaks

Faster menus (halves animation time):

```sh
adb shell settings put global window_animation_scale 0.5
adb shell settings put global transition_animation_scale 0.5
adb shell settings put global animator_duration_scale 0.5
```

If you use Solat Launcher, stop the stock Google TV home screen from running in the background. This frees memory on 2 GB TVs:

```sh
adb shell cmd appops set com.google.android.apps.tv.launcherx RUN_ANY_IN_BACKGROUND ignore
```

## Undo

Bring back any removed app:

```sh
adb shell cmd package install-existing <package>
```

Undo the tweaks:

```sh
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
adb shell cmd appops set com.google.android.apps.tv.launcherx RUN_ANY_IN_BACKGROUND allow
```

## Tip: check your sideloaded apps

While you're there, it's worth checking what can install other apps on your TV:

```sh
adb shell appops query-op REQUEST_INSTALL_PACKAGES allow
```

Remove anything you don't recognise with `adb uninstall <package>`.
