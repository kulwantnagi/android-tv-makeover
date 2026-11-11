# Live TV (Android TV)

A fork of the `tv/master` branch of
[kananinirav/Indian-IPTV-App](https://github.com/kananinirav/Indian-IPTV-App) (MIT, see `LICENSE`),
reworked for TV remotes and low-memory TVs.

It's a player for public M3U playlists from [iptv-org](https://github.com/iptv-org/iptv). It doesn't
host any content, and channels can go offline at any time.

## What changed from upstream

- **Categories in a sidebar:** All, News, Entertainment, Movies, Music, Sports, Kids, Infotainment,
  Business, Devotional, Other, with counts. Categories and logos come from the iptv-org India index
  playlist, matched by channel ID, then by name.
- **1080p only:** only streams whose name contains `(1080p)` are kept. Several 1080p streams of the
  same channel become one tile with backup streams. Change the check in `_buildChannels` in
  `lib/provider/channels_provider.dart`.
- **Hindi and Punjabi only:** channels are kept only if they appear on iptv-org's Hindi or Punjabi
  language playlists. Change `languages` in `lib/provider/channels_provider.dart` (ISO 639-3 codes).
- **Working streams first, dead channels hidden:** the app checks each stream's playlist in the
  background and remembers what actually played or failed on the TV (`lib/provider/stream_health.dart`).
  Channels open on their best-known stream, channels with no usable stream are left out, and every
  verdict expires after a day. A stream whose audio the TV can't decode counts as failed, and a
  surround track is swapped for the stereo one.
- **Faster:** playlists download in parallel and are parsed off the UI thread; the result is cached so
  the app opens instantly and refreshes in the background; the grid builds lazily and logos are decoded
  small and cached on disk.
- **Smooth category browsing on a 1 GB TV (Mali-450 GPU):** moving through the sidebar only moves the
  highlight; the grid switches once you pause (or press Right/OK). Each category's grid is built once
  and kept, and categories you haven't opened are pre-built while the remote is idle, so switching is
  just a visibility change. Tiles are flat (no per-tile animation controllers), the grid is one layer,
  and logos plus their light/dark result are cached on disk. Measured on the TV while paging through all
  categories: dropped frames went from 20–27% to under 1% (warm) / 3% (fresh launch), and the slowest
  frame build from 190 ms to 12 ms. Build with `--dart-define=PERF=true` to log per-frame build and
  raster times to logcat.
- **Remote-friendly UI:** dark theme, small logo tiles, deterministic D-pad movement (Right into the
  grid, Left from the first column back to the sidebar).
- **Player:** full screen with a channel banner; Up/Down or Channel +/- changes channel; a stream that
  errors or doesn't start within 12 seconds falls back to a backup stream; the screen stays awake while
  playing.
- **Icons:** light logos go on dark cards and dark logos on light cards; channels without a logo get a
  generated icon (colour from the name plus initials).
- **App icon and TV banner:** drawn by `../make_live_tv_icons.py`. The manifest uses a real 16:9 banner.
- **Build:** Gradle 8.14.3, Android Gradle Plugin 8.11.1, Kotlin 2.2.20 (needed by current Flutter).
  Plugins are pinned to NDK 27 in `android/build.gradle` so only one NDK is downloaded.

The channel catalog (`data/streams.csv`) is still read from the upstream repository.

## Build

```bash
flutter build apk --release --target-platform android-arm --android-skip-build-dependency-validation
```

Use `android-arm64` for 64-bit TVs. `../../build_live_tv.sh` builds and installs it in one step.
