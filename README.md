# Building and installing the Music Box app

This guide covers the phone side of Music Box: setting up a Windows computer to build the Android app, preparing a phone for installs over USB, and getting the app onto it. It also explains how someone else can install the app on their own phone from the GitHub repository. The music server itself is covered in [Setting up a Raspberry Pi music server for the Music Box app](docs/pi-music-server-setup.md); set that up first.

The app is a remote control only. It browses the library on the Pi and tells the Pi what to play; no music is stored on or played through the phone.

If you only want the app on your phone and someone has given you the file `app-release.apk`, skip to section 6. You don't need a computer.

To build it yourself, allow about an hour the first time, mostly for downloads.

## What you need

| Item | Notes |
| --- | --- |
| Windows 10 or 11 computer | About 15GB free for Android Studio, the Android SDK and build files. Mac and Linux work too; see the note at the end of section 3 |
| Android phone | Android 12 or newer. Google services are not needed: the app runs on GrapheneOS |
| USB cable | It must carry data. Some cables only charge, and the computer never sees the phone through them |
| Music Box server | The Pi from the server guide, on the same WiFi as the phone |
| Internet connection | The first build downloads about 1GB of tools and libraries |
| GitHub Desktop (optional) | To download the code from GitHub and keep it backed up. desktop.github.com |

## 1. Install Android Studio and the SDK

Android Studio is Google's Android development program. We never build in it; it supplies two things the build needs: the **Android SDK** (the Android libraries and the `adb` tool that talks to phones) and a copy of **Java**.

1. Download Android Studio from developer.android.com/studio and run the installer.
2. When it first opens, the setup wizard offers **Standard** or **Custom**. Choose **Custom** if you want to pick where the SDK goes. A short path with no spaces avoids trouble; ours is `C:\AndroidSdk`. Accept the licences and let it download.
3. Open the SDK Manager: on the welcome screen, **More Actions** > **SDK Manager**.
    - On the **SDK Platforms** tab, tick **Android 17 (API 37)**, the version the app is built against.
    - On the **SDK Tools** tab, make sure **Android SDK Build-Tools** and **Android SDK Platform-Tools** are ticked.
    - Click **Apply** and accept the licences.
4. Note two folders. You will need them below.

| Folder | Ours | Default |
| --- | --- | --- |
| Android SDK | `C:\AndroidSdk` | `C:\Users\<you>\AppData\Local\Android\Sdk` |
| Android Studio's Java (the `jbr` folder) | `C:\Android\SDK\jbr` | `C:\Program Files\Android\Android Studio\jbr` |

The SDK Manager shows the SDK folder at the top of its window. The `jbr` folder is inside wherever Android Studio itself was installed.

## 2. Tell Windows where Java is

The build is run from PowerShell, and it needs the `JAVA_HOME` setting to find Java. Open PowerShell (Start menu, type *PowerShell*) and run this once, with your own `jbr` folder:

```
setx JAVA_HOME "C:\Android\SDK\jbr"
```

`setx` saves the setting for terminals opened **later**, not the one it runs in. So straight afterwards, `echo $env:JAVA_HOME` still prints nothing; this is normal and confused us at first.

Close every PowerShell window and open a new one. If you use VS Code, quit it completely (close every window) and start it again; a new terminal tab inside the old window still has the old settings. Then check:

```
echo $env:JAVA_HOME
```

It should print the `jbr` folder. To use a terminal that's already open, set it for just that terminal:

```
$env:JAVA_HOME = "C:\Android\SDK\jbr"
```

## 3. Get the code and build it

**Download the code.** In GitHub Desktop, choose **File** > **Clone repository**, then the **URL** tab, and enter:

```
https://github.com/tamarack67/musicbox-remote
```

Pick a folder to put it in. Without GitHub Desktop, use **Code** > **Download ZIP** on the GitHub page and unzip it.

The repository is private at present. To share it, invite the person on GitHub (the repository's **Settings** > **Collaborators**), or make it public.

**Tell the build where the SDK is.** In PowerShell, go to the project folder and create a file called `local.properties`. Use your own SDK folder, with forward slashes:

```
cd C:\path\to\musicbox-remote
```

```
Set-Content local.properties "sdk.dir=C:/AndroidSdk"
```

This file is specific to each computer, so it isn't in GitHub. If it's missing, the build stops with *SDK location not found*.

**Build.** The project includes the Gradle wrapper (`gradlew`), which downloads the right build tools by itself. Nothing else needs installing.

```
.\gradlew assembleDebug
```

The first build takes 3 to 5 minutes while it downloads. Later builds take under a minute. It ends with `BUILD SUCCESSFUL`. Warnings such as *Unable to strip libandroidx.graphics.path.so* are harmless.

The connection code has its own tests, which run against a pretend MPD server without needing the Pi:

```
.\gradlew test
```

On a Mac or Linux the commands are the same, except `./gradlew` instead of `.\gradlew`, `export JAVA_HOME=...` instead of `setx`, and `adb` is in `platform-tools` inside the SDK folder.

## 4. Get the phone ready for USB installs

A phone only accepts apps from a computer once **USB debugging** is on. It lives in a hidden **Developer options** menu. The steps are the same on every phone, but the menus are named differently.

1. **Unlock Developer options.** Find **Build number** in the phone's settings and tap it 7 times. Enter your PIN if asked. The phone says *You are now a developer*.
2. **Turn on USB debugging** in **Developer options**.
3. **Plug the phone into the computer** with the screen unlocked. If it asks what the USB connection is for, choose **File transfer**.
4. **Accept the prompt.** The phone asks *Allow USB debugging?* and shows the computer's key fingerprint. Tick **Always allow from this computer** and tap **Allow**. You only do this once per computer.

Where the settings are on the two phones we used:

| | OPPO A17 (ColorOS, Android 12) | Pixel 8 (GrapheneOS) |
| --- | --- | --- |
| Build number | **Settings** > **About device** > **Version** | **Settings** > **About phone** |
| Developer options | **Settings** > **Additional settings** > **Developer options** | **Settings** > **System** > **Developer options** |
| Extra settings | Also turn on **Disable permission monitoring**. *Install via USB* wasn't on our phone and wasn't needed | None. GrapheneOS blocks USB data while the phone is locked, so keep it unlocked while installing |

On a Samsung, **Build number** is under **Settings** > **About phone** > **Software information**. On a Xiaomi, tap **OS version** (or **MIUI version**) under **About phone**, and also turn on **Install via USB** in Developer options.

**Check that the computer sees the phone.** `adb` is not on the Windows path, so give its full location (inside the SDK folder):

```
C:\AndroidSdk\platform-tools\adb.exe devices
```

| You see | Meaning |
| --- | --- |
| A serial number then `device` | Ready |
| A serial number then `unauthorized` | The prompt in step 4 wasn't accepted. Unplug, plug in again, and look at the phone |
| Nothing | The computer can't see the phone. Try another cable, unlock the phone, or install the phone maker's USB driver |

When you've finished installing, you can turn Developer options off with the switch at the top of that menu. This is tidier for security, and the app doesn't need it. Turn it back on (tap Build number again if the menu has gone) when you next install an update over USB.

## 5. How the app got onto our phones

There are two versions of the app: a **debug** build for testing, and a **release** build for everyday use. They have different names, so both can sit side by side on one phone.

| | Debug build | Release build |
| --- | --- | --- |
| Name on the phone | Music Box dev | Music Box |
| Android's ID for it | `nz.musicbox.remote.debug` | `nz.musicbox.remote` |
| Signed with | A test key that Android Studio makes automatically on each computer | Your own key, made once |
| Size | About 33MB | About 3MB (unused code removed) |
| Use | Trying out changes | The copy you actually use |

### Signing, and why it matters

Every Android app is **signed** with a key, which proves where it came from. Android only installs an update over an existing app when both are signed with the **same key**. So:

- Updates to your release build must be signed with your key, forever. If the key is lost, the app has to be uninstalled and installed afresh. That loses only the app's settings; playlists are stored on the Pi.
- An app built on someone else's computer, or with a different key, can't update yours. Android says *App not installed*, or adb says `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall the old one first.

### Debug builds during development

During development each change went onto the OPPO with a single command, run in the project folder with the phone plugged in:

```
.\gradlew installDebug
```

This builds the app and installs it as *Music Box dev*. Open it from the app drawer.

On the OPPO, ColorOS asks for confirmation on the phone screen before every USB install. If nobody taps **Install**, `installDebug` gives up with an error. Using adb directly waits for you instead:

```
.\gradlew assembleDebug
```

```
C:\AndroidSdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

### Making the release key (once)

The project includes a script that makes the key. Run it in PowerShell from the project folder:

```
powershell -ExecutionPolicy Bypass -File tools\create-signing-key.ps1
```

`-ExecutionPolicy Bypass` is needed because Windows refuses to run downloaded script files by default.

It asks you to choose a password (at least 8 characters), then saves two files in `C:\Users\<you>\.musicbox-signing\`:

| File | Contents |
| --- | --- |
| `musicbox-release.p12` | The key itself |
| `keystore.properties` | Where the key is and its password, for the build |

This folder is deliberately outside the project so it never goes to GitHub. **Back up the folder and keep the password in a password manager.** It's the one thing in this project that can't be recreated.

### Building and installing the release

```
.\gradlew assembleRelease
```

This makes `app\build\outputs\apk\release\app-release.apk`, signed with your key. Install it with the phone plugged in:

```
C:\AndroidSdk\platform-tools\adb.exe install -r app\build\outputs\apk\release\app-release.apk
```

`-r` replaces the installed app and keeps its settings. If two phones are plugged in, `adb devices` lists their serial numbers; pick one with `-s`:

```
C:\AndroidSdk\platform-tools\adb.exe -s SERIALNUMBER install -r app\build\outputs\apk\release\app-release.apk
```

On the OPPO the test build had been installed as *Music Box* before the debug and release builds were separated, so it was uninstalled once before the release went on:

```
C:\AndroidSdk\platform-tools\adb.exe uninstall nz.musicbox.remote
```

**Without a cable.** Copy `app-release.apk` to the phone (by USB file transfer, email, cloud storage or a GitHub release) and tap it in the **Files** app. The phone asks to let Files *install unknown apps*; allow it. Updates work the same way, as long as they're signed with the same key.

**Checking a copy is genuine.** This prints the key fingerprint an APK was signed with:

```
C:\AndroidSdk\build-tools\36.0.0\apksigner.bat verify --print-certs app\build\outputs\apk\release\app-release.apk
```

Our release key's SHA-256 fingerprint starts `76568ea9` and ends `f5fd`. GrapheneOS shows the fingerprint when installing from a file.

**Numbering updates.** Before building a release to share, raise `versionCode` (by 1) and `versionName` (for example to `1.1`) in `app\build.gradle.kts`. Then **Settings** > **Apps** > **Music Box** shows which version a phone has.

## 6. Installing the app on your own phone

You need the Music Box server (the Pi) running on your home WiFi first.

### The easy way: install the ready-made app

1. Get `app-release.apk`: from the person who built it, or from the **Releases** section of the GitHub page if one has been posted there.
2. Copy it to your phone, or download it straight onto the phone.
3. Tap it in the **Files** app. Allow Files to *install unknown apps* when asked, then tap **Install**.

No computer or developer settings are needed, and later updates install the same way, over the top.

### Building it yourself from GitHub

1. Follow sections 1 to 4: install Android Studio, set `JAVA_HOME`, download the code, create `local.properties`, and turn on USB debugging.
2. Then either:
    - **Quickest:** run `.\gradlew installDebug`. You get *Music Box dev*, which works exactly like the release for everyday use.
    - **Properly signed:** make your own key with the script in section 5, then `.\gradlew assembleRelease` and `adb install`.

A copy you build yourself is signed with your key, not the original builder's, so it can't update a copy you were given. Uninstall that one first.

Without a key, `assembleRelease` makes `app-release-unsigned.apk`, which no phone will install. Use `installDebug` or make a key.

## 7. First run

**Permissions.** On first opening, the app asks for:

- **Local network access** (Android 17 and newer, which includes current GrapheneOS). Allow it, or the app can't reach the Pi. It asks again each time it opens until allowed.
- **Notifications** (Android 13 and newer). Allow it for the play, pause and skip controls on the lock screen and in the notification shade. The app works without it.

On GrapheneOS also check **Settings** > **Apps** > **Music Box** > **Permissions** > **Network** is allowed. GrapheneOS can block network access per app.

**Finding the Pi.** The app looks for the Pi itself: first the address that worked last time, then `musicbox.local`, then by searching the network. If it doesn't connect, open **Settings** in the app and tap **Find my Music Box**, or type the Pi's address and port `6600`. The library loads in about 25 seconds the first time and is instant after that.

**Volume.** By default the app has no volume control and leaves the Pi at full output: the amplifier's knob sets the volume. Settings has an optional volume slider, which only covers the audible range of the DAC (60 to 100 on MPD's scale). The app never changes the volume unless you move the slider.

**Cover art.** MPD only serves cover images named `cover.jpg`, `cover.png` or `cover.webp` in the album folder, or art embedded in the music files. Other image names (for example `folder.jpg` or `Artist - Album - cover.png`) are not shown. Rename them to `cover.jpg`.

## 8. Keeping the code backed up

The code is in a **private** GitHub repository, published from GitHub Desktop:

1. **File** > **Add local repository**, and choose the project folder.
2. **Publish repository**, with **Keep this code private** ticked.
3. After later changes are committed, click **Push origin** to send them to GitHub.

To keep your email address out of the commit history, tick **Keep my email addresses private** under **Settings** > **Emails** on github.com. Then set the no-reply address GitHub shows there for the project, in PowerShell in the project folder:

```
git config user.email "NUMBER+username@users.noreply.github.com"
```

These are deliberately **not** in GitHub, so back them up separately:

| What | Where | Why it's left out |
| --- | --- | --- |
| Signing key and password | `C:\Users\<you>\.musicbox-signing\` | Anyone with it could sign apps as you |
| `local.properties` | Project folder | Specific to each computer |
| Build output | `app\build`, `mpd\build` | Rebuilt by every build |

## 9. How we built it

We built the app with Claude Code, running in VS Code's terminal, following a written brief (`CLAUDE.md` in the project folder). Claude Code wrote the code and ran the builds and adb commands. We worked in stages: toolchain check, connection and Now playing, library, queue and playlists, cover art, then lock-screen controls and polish. The phone was tested after each stage before moving on.

Useful commands, run in the project folder:

| Command | Does |
| --- | --- |
| `claude` | Starts a new Claude Code session |
| `claude --continue` | Reopens the most recent session in this folder |
| `claude --resume` | Picks an older session from a list |

Claude Code reads `JAVA_HOME` when it starts, like any terminal. After changing it, quit VS Code completely, reopen it, and use `claude --continue`.

## Troubleshooting

These are the problems we actually hit, plus a few likely ones.

| Symptom | Cause and fix |
| --- | --- |
| `echo $env:JAVA_HOME` prints nothing after `setx` | Normal in the terminal where `setx` ran. Open a new PowerShell window, or quit VS Code completely and reopen it |
| `.\gradlew` says *JAVA_HOME is not set* | See section 2. For a quick fix in the current terminal: `$env:JAVA_HOME = "C:\Android\SDK\jbr"` |
| *SDK location not found* | `local.properties` is missing or wrong. See section 3 |
| *adb is not recognized* | adb isn't on the Windows path. Use its full location, `C:\AndroidSdk\platform-tools\adb.exe` |
| `adb devices` shows nothing | Charge-only cable, phone locked, or USB debugging off. On GrapheneOS the phone must be unlocked |
| `adb devices` shows `unauthorized` | The *Allow USB debugging?* prompt was missed or declined. Unplug and plug in again with the phone unlocked |
| `installDebug` fails on the OPPO | ColorOS was waiting for you to tap **Install** on the phone. Tap it, or use `adb install` (section 5) |
| *App not installed* or `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed copy was signed with a different key. Uninstall it, then install again |
| *Running scripts is disabled on this system* | Run the script with `powershell -ExecutionPolicy Bypass -File ...` as shown in section 5 |
| App on the phone can't reach the Pi | Phone on a guest WiFi or mobile data, local network permission denied, or (GrapheneOS) the Network permission off. Guest networks usually keep devices apart, so use the main WiFi |
| **Find my Music Box** finds nothing, but typing the address works | The Pi isn't advertising itself. If `mpd.socket` is enabled, MPD switches off its own advertising (the log says *No global port, disabling zeroconf*). Add the Avahi file below to the Pi |
| Some albums have no cover | The image isn't named `cover.jpg`, `cover.png` or `cover.webp`. Rename it, then run `mpc update` on the Pi |
| Untagged files show odd titles | The app guesses track number and title from the file name when there are no tags. `NN - Title.ext` works best |

**The Avahi file.** On the Pi, this makes the Music Box findable whatever MPD's own setting is:

```
sudo tee /etc/avahi/services/mpd.service > /dev/null << 'EOF'
<?xml version="1.0" standalone='no'?>
<!DOCTYPE service-group SYSTEM "avahi-service.dtd">
<service-group>
  <name>Music Box</name>
  <service>
    <type>_mpd._tcp</type>
    <port>6600</port>
  </service>
</service-group>
EOF
```

Avahi picks up the file within a few seconds; no restart is needed.
