# Music Box Remote: project brief

An Android app that browses and plays a personal music library held on a Raspberry Pi. The Pi plays the audio into a hi-fi amplifier. The phone is a remote control only: no audio is ever streamed to or played on the phone.

The owner previously used Volumio and wants similar everyday functionality for local files. Internet radio and streaming services are out of scope.

## Who you are working with

- The owner is technically capable and has used Claude Code in VS Code before, but has never built an Android app. Explain Android-specific steps plainly and give exact commands.
- Development machine: Windows, VS Code, Android Studio installed for the SDK and emulator. Build and install from the terminal with Gradle (`.\gradlew installDebug`) and `adb`.
- Work in stages (see "Build stages"). At the end of each stage, stop, tell the owner what to test on the phone, and wait for confirmation before starting the next stage.

## The server (already built and working; do not change it)

| Item | Value |
|---|---|
| Hardware | Raspberry Pi 4, DAC case, 1TB SSD mounted read-only at `/mnt/music` |
| OS | Raspberry Pi OS Lite 64-bit (Debian 13), hostname `musicbox` |
| Player | MPD, installed from the Debian package |
| Address | `192.168.x.x` at present. It can change, and will change when the box moves to another network |
| Port | 6600, no password |
| Discovery | Advertised over mDNS as service type `_mpd._tcp`, name "Music Box". Also reachable as `musicbox.local` |
| Audio output | One ALSA output named "NanoSound DAC" with a hardware mixer |
| SSH | User `yourusername` (for the owner's use; the app never uses SSH) |

Read the MPD version from the greeting line (`OK MPD x.y.z`) when connecting and only use commands that version supports.

If anything on the Pi appears misconfigured, stop and tell the owner. Do not try to fix the Pi from this project.

### The library

- About 1TB, mostly MP3 with some WAV. It will grow to about 2TB.
- Folder layout: `<Letter>/<Artist>/<Album>/<NN> - <Title>.<ext>`, for example `B/Babes in Toyland/Fontanelle/01 - Bruise Violet.mp3`. There is also a top-level folder `00`.
- MP3 files are well tagged. WAV files may have no tags at all, so:
  - Folder browsing is a first-class way to navigate, not a fallback.
  - When a track has no tags, derive artist, album, track number and title from its path.

### Volume

The DAC's hardware volume control is logarithmic. On MPD's 0 to 100 scale, anything below roughly 60 is inaudible and 100 is normal line level. The amplifier's own knob is the main volume control.

- Default: no volume control in the app. Leave MPD volume at 100.
- Optional setting: an in-app slider mapped onto MPD's 60 to 100 range only.
- Never send a volume change the user did not explicitly make. A sudden jump to 100 with the amplifier turned up would be dangerously loud.

## Target devices

- First device: OPPO A17, Android 12 (API 31), connected by USB for development.
- Later: Pixel 8 running GrapheneOS. To keep that working, do not use Google Play Services or any Google-only library.
- `minSdk` 31. Target the current stable SDK.

## Technical choices

- Kotlin, Jetpack Compose, Material 3, single activity, Navigation Compose.
- Coroutines and Flow for all I/O and state.
- Keep dependencies few. No dependency-injection framework is needed at this size; use simple manual wiring.
- Permissions: `INTERNET` and `ACCESS_NETWORK_STATE`. Add `POST_NOTIFICATIONS` (API 33+) only when the media notification is built.

## Architecture

Three layers, each testable without the one above it.

### 1. MPD client (pure Kotlin, no Android dependencies)

- Speaks MPD's text protocol over a TCP socket.
- Two connections:
  - A command connection for requests.
  - An idle connection that sits in `idle player mixer playlist database update options` and emits change events as a Flow.
- Handles binary responses for cover art (`albumart` for a cover file in the folder, `readpicture` for embedded art), fetched in chunks.
- Reconnects automatically with backoff. After a reconnect, re-read full status so the UI is never stale.
- Connection resolution order: last known address, then `musicbox.local`, then mDNS discovery of `_mpd._tcp`. Save whichever address works.
- Parser and command code must have unit tests that run against a small fake MPD server, so they can be verified without the Pi.

### 2. Repository and cache

- Exposes player state, queue, and library as Flows.
- Library cache in a Room database, so browsing and search are instant. Invalidate and refresh it when MPD's `db_update` value (from `stats`) changes or a `database` idle event arrives.
- Cover art cached on disk, keyed by album folder.

### 3. UI

Screens:

- **Now playing**: cover, title, artist, album, progress with seek, previous, play/pause, next, shuffle and repeat toggles.
- **Queue**: current queue, tap to play, remove, reorder, clear.
- **Library**: Artists, Albums, and Folders views. Artist leads to albums, album leads to tracks.
- **Album or folder detail**: play now (replace queue), add to queue, play from a chosen track.
- **Search**: across artist, album and title.
- **Playlists**: list, load, save current queue, delete.
- **Settings**: server address and port, a "find my Music Box" discovery button, volume mode.

A mini player bar stays visible on every screen except Now playing.

Connection problems should show as a quiet status indicator while the app retries, not as error dialogs.

## Build stages

Stop after each stage for the owner to test.

0. **Scaffold.** Create the project, build it, install it on the phone, show a placeholder screen. Confirms the toolchain works end to end.
1. **Connection and Now playing.** MPD client with tests, connect to the Pi, show current track and state, working play/pause/next/previous/seek, live updates through idle, automatic reconnect.
2. **Library browsing.** Artists, albums, folders, album detail, play and add-to-queue actions, search. Add the Room cache.
3. **Queue and playlists.** Full queue management and stored playlists.
4. **Cover art.** Fetch, cache and display on all relevant screens.
5. **System integration.** Media session so lock-screen and notification controls work, mDNS discovery in Settings, volume option, general polish.

## Later, not now

- A second server: Raspberry Pi 5 with a 2TB library. The app should already cope, because the server address is a setting and nothing else is hard-coded. Do not hard-code the address, hostname, output name or library paths anywhere outside defaults in Settings.
- Multiple saved servers.

## Current state (November 2026)

All build stages (0 to 5) are complete. Version 1.0 is released: it's on GitHub under Releases (`v1.0`) and installed on both phones (OPPO A17 and Pixel 8 / GrapheneOS). Setup and install instructions are in `README.md` (the app) and `docs/pi-music-server-setup.md` (the Pi).

### Added beyond the original brief

- Untagged files: artist, album, track number and title are parsed from the path, including messier file-name patterns (`mpd/.../PathTags.kt`, with tests).
- A–Z index bars on the Artists and Albums lists.
- Stored playlists can be edited: swipe to remove (with Undo) and drag to reorder.
- A ⋮ menu on songs, albums, folders and Now playing: add to queue, or add to a playlist (existing or new).
- Playlist sharing: as a text track list, or as an `.m3u8` file (share sheet or save to a file). Import of `.m3u`/`.m3u8` files, matching entries to library files by path. Importing from plain text lists was considered and declined.
- Security hardening: line breaks are stripped from MPD arguments, app data backup is off, the service is not exported, and the app requests the Android 17 `ACCESS_LOCAL_NETWORK` permission.
- Release builds are signed with the owner's own key and shrunk with R8 (about 3MB). Debug builds install separately as "Music Box dev" (`nz.musicbox.remote.debug`).

### Things learned about the server

- MPD only serves cover files named `cover.jpg`, `cover.png` or `cover.webp` (`albumart`), or embedded art (`readpicture`). Other image names can be listed but not fetched, so the owner is renaming them.
- With `mpd.socket` enabled, MPD disables its own zeroconf ("No global port"). The Pi advertises `_mpd._tcp` through an Avahi service file instead (see the README's troubleshooting section).

### Releasing an update

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Run `.\gradlew test`, then `.\gradlew assembleRelease`.
3. Install on a plugged-in phone with `adb install -r app\build\outputs\apk\release\app-release.apk`, or attach the APK to a new GitHub release.

The signing key lives outside the project, in `%USERPROFILE%\.musicbox-signing\`. Never print or commit its password or `keystore.properties`.

### Standing rules

- The volume rule above still applies: never send a volume change the user didn't make.
- Advise on the Pi; don't change it from here.
- The repository is public. Keep personal details out of tracked files: real addresses, user names, emails, library listings. Commit with the repo's no-reply email.

### Ideas not yet done

- Multiple saved servers, for when the Pi 5 with the 2TB library arrives.
- Regenerate the list of album folders without a usable cover once the owner's tag clean-up is finished. That list is kept out of git.
