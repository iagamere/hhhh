# PS4 Download Monitor 5.2

Open in Android Studio (JDK 17), sync Gradle, run. NOT compiled by the author (no Android SDK available): expect to fix a few compile errors on first sync.

How it works (all verified from the ezRemote sources):
- Send: POST /__local__/download_url on ezRemote Client (:8080) with dest = FINAL FILE PATH (folder + unique name).
- Monitor: ezRemote Server (:6701) GET /get_download_state (live bytes/size/state); when it is down, bg_download_history.json.
- Pause / Resume / Delete, two modes (detected automatically with GET /v2/info on :6701):
  * CUSTOM server (ps4-ezremote-server-abdo, API /v2): POST /v2/pause, /v2/resume, /v2/delete {id,...} act on ONE download immediately;
    the other downloads keep running and nothing has to be restarted. Live list from GET /v2/downloads (server-measured speed, connection count,
    error text). A failed / "needs link" download is continued by sending the link again to the SAME file path (the Retry button does that).
  * ORIGINAL server: stop ezRemote Server, edit bg_download_history.json (state 3 + failed_attempts 5 / 1, or remove the entry), delete files.
    The server reads the file only at start-up, so a running server cannot be told to stop one download.
- Files: browse, rename, new folder, copy/cut/paste, extract, install, delete (ezRemote web only; no FTP).
- PKG reader: title, icon, header, entries (partial reads over ezRemote downloadFile with Range).

Files: Models, Store, EzRemote (client API), EzServer (+ BgHistory), DownloadMonitor, Ops, Pkg, Notifier, MonitorService,
Names, Fmt, Lang, and the UI (MainActivity, Ui, HomeUi, DownloadsUi, FilesUi, PkgUi, BrowserUi, SettingsUi).
Removed in 4.0: FTP, file-matching heuristics, Net size check, install-from-link, upload, text editor, save-to-phone, image preview,
artwork scan, history screen, auto-rename/auto-install, adoption toggle, "stop monitoring", speed chart, untracked .tmp list, events list.

## 5.2 (goes with ezRemote Server 2.0.0-abdo11): server choice + built-in Builder
- The Home card now shows **Server in use** (Original ezRemote / Our server + version). Tap it to choose: **Original**, **Our server** (abdo11 is bundled inside the app, no separate download) or **Build a server from my zip** (the PS4 Builder flow: the zip is built on GitHub Actions, the ELF is downloaded and sent to the PS4).
- PS4 Builder is no longer needed as a separate app.
- Sending retries by itself when the old server still answers, and a server that was already running is not mistaken for the new one.
- Choosing Original stops our server and forgets the saved ELF (otherwise the monitor would re-send it). Getting the original back may need "restart server" in ezRemote Client on the PS4: NOT verified.
- The build token is embedded in the app (scrambled, not secret: anyone can unpack the APK). NOT compiled or tested on a device.

## 5.1 (goes with ezRemote Server 2.0.0-abdo10): PS4 information
- New card on the Home screen opens **PS4 information**: screenshots and videos (thumbnails, full view, save to phone), storage (free/used per volume), installed games (title, id, version, icon from param.sfo) and device details (from the console's sysctl). Everything is read through ezRemote Server, so it works while a game runs.
- Needs the abdo10 server. The app sends it read-only folders (`/user/av_contents`, `/user/app`, `/user/appmeta`, `/system_data/priv/appmeta`) once; the Device tab lets you add more and has **Check paths**, which shows which folders the console actually answered.
- NOT verified on a console: these folder names come from public documentation. If a tab is empty, use Check paths and add the right folder.

## 5.0 (goes with ezRemote Server 2.0.0-abdo9)
- Queue control (custom server): download details show position and priority, with Start now / To top / Up / Down / To bottom and High / Normal / Low priority (`/v2/start_now`, `/v2/move`, `/v2/priority`).
- Files, covers, name checks, file sizes, rename / delete / new folder go through the server (`/v2/fs/*`, port 6701) for paths in /data and /mnt, so they work while a game runs; anything else, or an older server, falls back to ezRemote web (8080). Copy, move, extract, install and edit still need ezRemote web. The app switches file access on once; the Server screen has a switch to turn it off.
- Server token: field in the PS4 settings, sent as `X-Token` on every server call; the Server screen can set or remove the token on the server. A locked server is reported instead of looking "old".
- "Finished" and "interrupted" notifications use the card look (cover colour, name, bar).
- NOT compiled (no Android SDK here) and not run on a phone or PS4.

## 4.3 cards not silent, visible on the lock screen
- Channel `dl_v2`: IMPORTANCE_DEFAULT with no sound / vibration (so the cards are out of the "Silent" section), lock-screen visibility PUBLIC.
  The phone's own setting "show notifications on the lock screen" must also be on for this app.

## 4.3 one notification only
- Android requires a notification for the monitoring service and ColorOS shows it even on a blocked channel, so the foreground notification is now the
  LEAD download's card (`Notifier.foregroundFor / syncForeground / handoff`, `MonitorService.promote`). There is no separate "PS4 Monitor" row while a
  download card exists. When the lead card finishes / is deleted, the foreground state moves to the next card first; with no card left the quiet
  summary (id 1) is used for the few seconds until the service stops.

## 4.3 full-bleed cards (current): no grey box
- Android docs: apps targeting 31+ cannot create fully custom notifications; the system wraps them in its standard template (the grey container).
  With `targetSdk = 30` (compileSdk stays 34) a custom layout WITHOUT a style is the whole notification, so the cover-coloured surface runs edge to edge.
  `Notifier.FULL_BLEED` (layouts `notif_dl_*_fb.xml`) switches it; false (+ targetSdk 34) gives the decorated card inside the grey box again.
- The app draws its own header row (glyph, app name, time left) because the system draws none. The id-1 foreground notification stays a normal (grey) one.
- Not verified on a device by the author; ColorOS may still wrap or group notifications of one app.

## 4.3 media-session cards (tried, OFF by default)
- Tried a MediaSession notification to remove the grey container (`Notifier.mediaCard`, switch `USE_MEDIA_SESSION`). On ColorOS the system turned it into
  its own grey media player (no cover colour, no size / speed, bar stuck at 00:00 / 01:40), so the RemoteViews card (`customCard`) is the default again.
  The grey container around any normal notification is drawn by System UI and cannot be removed by the app.

## 4.3 notification redesign (cards)
- **Real progress bar.** The block characters are gone. Each download card is a custom `RemoteViews` layout (`DecoratedCustomViewStyle`, so the
  system header with the app name stays) containing a real determinate `ProgressBar` (rounded fill over a rounded track, 0..1000 for smooth steps).
- **Cover colour.** `CoverColor.palette()` derives a dark tonal surface + a vivid accent (>= 4.5:1 contrast) from the cover's dominant colour.
  The card surface, progress fill, percentage and buttons use it. Android 12+: the bar is tinted with `RemoteViews.setColorStateList`;
  Android 8-11: no public API exists to tint a ProgressBar in RemoteViews, so the bar is white on the cover-coloured card.
- **Why the old card was grey:** `setColorized(true)` is honoured by Android only for foreground-service notifications (the id-1 notification) or
  media notifications with a MediaSession. The card was neither, so System UI drew its normal grey surface. The colour is now inside the card.
- **Foreground-service notification (id 1):** shows overall progress as the system's own tinted progress bar, and is colourized (the one place Android
  allows it) while exactly one download is active.
- **Coordinated palette (`CoverPalette.kt`, pure Kotlin, JVM-testable):** from the cover it derives surface, title colour, secondary text, progress fill,
  percentage colour, speed colour (the cover's second colour when it has one), empty track, button container, warning colour. Light covers
  (yellow, cyan, lime) get a light surface with dark text, others a dark surface with light text; mostly-black covers get a quiet, desaturated palette.
  Contrast: title >= 7:1, other text >= 4.5:1, bar fill >= 3:1. Light surfaces only on Android 12+ (the bar cannot be tinted below that).
  The analysis is cached per download and recomputed when the cover changes (signature: iconReady / titleId / title).
- Hue binning in `CoverColor.dominant` is now centred on 0 degrees, so red covers are no longer split into two half-weight bins.
- NOT compiled here either (no Android SDK): see the notes in the change report.

## 4.5
- Fix: the home screen showed "ezRemote web: Available" whenever ezRemote Server answered, even when ezRemote web (port 8080) was not reachable (the
  detail text below said so). The web status now only comes from a real check of port 8080 (Refresh, or opening the screen).

## 4.4 (downloads while a game is running)
- **Links are sent straight to ezRemote Server** (port 6701, `POST /v2/add`) when the custom server is running. Before, every download went through
  ezRemote Client's web server (port 8080); the PS4 suspends ezRemote Client while a game runs, so nothing could be added. The server is a payload and
  keeps answering. The original server, or a failure to reach the custom one, still uses the old route (8080).
- A finished download is **verified by the server's own report** when ezRemote web is not reachable (it already checks the HTTP status, that it is not a
  web page, and the final size), instead of waiting in "Checking downloaded file" until ezRemote is opened again.
- The home screen explains that "ezRemote web: unavailable" is normal while a game runs. Still needs ezRemote web: the Files tab, game covers/names
  of new downloads, and picking a free file name (while it is off, a new download with the same name as an existing file on the PS4 is not detected).
- NOT compiled by the author (no Android SDK here).

## 4.3 (music-player style notifications)
- **One card per download** (active or paused), styled like a music player: media-style notification tinted with the game's cover colour, the cover as
  the picture, the name, a progress bar made of blocks with the percentage, `downloaded / total` size, size left, time left and speed.
- **Two buttons:** Pause <-> Resume (it turns into Resume when the download is stopped, and the card stays so you can resume from it) and Delete.
  Delete asks for a second tap within 6 seconds (the card text changes and the icon turns into a warning); it then removes the download
  and its file from the PS4, the same as "delete with files" in the app.
- The foreground-service notification became one quiet line (new channel "Monitoring (service)", importance minimum); the details are in the cards.
- Finished / interrupted notifications are unchanged.
- NOT compiled by the author (no Android SDK here), like the rest of this project: expect to check the first build.

## 4.2 (custom server edition)
- **Server screen** (Settings > ezRemote Server): status (custom / original / unreachable), choose the server .elf once and send it to GoldHEN's BinLoader
  (TCP 9090) with one tap, server download settings (parallel downloads, automatic / fixed connections), server log (also read from the PS4 file
  when the server is down).
- **Self-healing:** expired links are refreshed automatically (the original link is sent again to the SAME file, up to 5 times in a row); if the
  server stops answering for 60 s while downloads are unfinished, the saved .elf is sent again (at most every 5 minutes).
- **Notifications:** one ongoing notification listing every active download (overall progress, speed, ETA), tinted with the game's cover colour;
  a heads-up alert when a download is interrupted (server not reachable 90 s / no progress 3 min; one per incident, removed when it recovers;
  setting "Alert me when a download is interrupted"); one grouped notification per finished download.
- **Cover colours:** cards, progress bars and the detail screen take the dominant colour of the game's cover.
- The custom server retries transient failures forever (only consecutive failures count), so downloads no longer stop and wait for "Resume".
